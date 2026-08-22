package com.interpretertrainer.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class PreparedAttachment(
    val name: String,
    val kind: String,
    val text: String? = null,
    val error: String? = null
)

internal object LocalDocumentExtractor {
    private const val MAX_CHARS = 260_000
    private const val MAX_ENTRY_BYTES = 6 * 1024 * 1024
    private const val MAX_ZIP_BYTES = 36 * 1024 * 1024

    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm",
        "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py",
        "rb", "php", "go", "rs", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "sql", "yaml", "yml", "toml", "ini", "cfg", "log", "srt", "vtt", "rtf"
    )

    fun prepare(context: Context, uris: List<Uri>): List<PreparedAttachment> = uris.map { uri ->
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            return@map PreparedAttachment(name, "vision")
        }
        if (mime.startsWith("audio/")) {
            return@map PreparedAttachment(name, "audio")
        }

        runCatching {
            val extracted = when {
                mime.startsWith("text/") || ext in textExtensions -> readText(context, uri, ext)
                ext == "pdf" || mime == "application/pdf" -> readPdf(context, uri)
                ext == "docx" -> readDocx(context, uri)
                ext == "pptx" -> readPptx(context, uri)
                ext == "xlsx" -> readXlsx(context, uri)
                ext in setOf("odt", "ods", "odp") -> readOpenDocument(context, uri)
                ext == "epub" -> readEpub(context, uri)
                else -> sniffReadableText(context, uri)
            }.trim().take(MAX_CHARS)

            if (extracted.isBlank()) {
                PreparedAttachment(
                    name = name,
                    kind = "document",
                    error = "No readable text was found. Scanned/image-only PDFs need OCR; legacy .doc/.xls/.ppt files should be saved as DOCX/XLSX/PPTX."
                )
            } else {
                PreparedAttachment(name = name, kind = "document", text = extracted)
            }
        }.getOrElse { error ->
            PreparedAttachment(
                name = name,
                kind = "document",
                error = error.message ?: "This file could not be read locally."
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty().ifBlank { "attachment" }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "attachment" } ?: "attachment"
    }

    private fun open(context: Context, uri: Uri): InputStream =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Unable to open attachment" }

    private fun readText(context: Context, uri: Uri, ext: String): String {
        val raw = open(context, uri).use { it.readBytesLimited(MAX_ENTRY_BYTES) }
        val decoded = raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return if (ext == "rtf") stripRtf(decoded) else decoded
    }

    private fun stripRtf(value: String): String = value
        .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ")
        .replace("{", " ")
        .replace("}", " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return open(context, uri).use { input ->
            PDDocument.load(input).use { document ->
                PDFTextStripper().getText(document)
            }
        }
    }

    private fun readDocx(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) { it == "word/document.xml" }
        val xml = entries["word/document.xml"] ?: error("DOCX document.xml is missing")
        return extractXmlText(xml, setOf("t"), setOf("p"))
    }

    private fun readPptx(context: Context, uri: Uri): String {
        val slides = zipEntries(context, uri) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        return slides.entries
            .sortedBy { slideNumber(it.key) }
            .joinToString("\n\n") { (name, bytes) ->
                "[${name.substringAfterLast('/').substringBeforeLast('.')}]\n" + extractXmlText(bytes, setOf("t"), setOf("p"))
            }
    }

    private fun readOpenDocument(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) { it == "content.xml" }
        val xml = entries["content.xml"] ?: error("OpenDocument content.xml is missing")
        return extractXmlText(xml, setOf("p", "h", "span"), setOf("p", "h"), captureElementText = true)
    }

    private fun readEpub(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) {
            val lower = it.lowercase(Locale.ROOT)
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
        }
        return entries.entries
            .sortedBy { it.key }
            .joinToString("\n\n") { (_, bytes) ->
                @Suppress("DEPRECATION")
                Html.fromHtml(bytes.toString(Charsets.UTF_8)).toString()
            }
    }

    private fun readXlsx(context: Context, uri: Uri): String {
        val entries = zipEntries(context, uri) {
            it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml"))
        }
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return entries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") }
            .sortedBy { slideNumber(it.key) }
            .joinToString("\n\n") { (name, bytes) ->
                "[${name.substringAfterLast('/').substringBeforeLast('.')}]\n" + parseWorksheet(bytes, shared)
            }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
        val result = mutableListOf<String>()
        var inItem = false
        var inText = false
        val item = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "si" -> { inItem = true; item.setLength(0) }
                    "t" -> if (inItem) inText = true
                }
                XmlPullParser.TEXT -> if (inItem && inText) item.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> inText = false
                    "si" -> { result += item.toString(); inItem = false }
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseWorksheet(bytes: ByteArray, shared: List<String>): String {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
        val out = StringBuilder()
        var cellRef = ""
        var cellType = ""
        var value: String? = null
        var inline = StringBuilder()
        var captureV = false
        var captureT = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r").orEmpty()
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = null
                        inline = StringBuilder()
                    }
                    "v" -> captureV = true
                    "t" -> captureT = true
                }
                XmlPullParser.TEXT -> {
                    if (captureV) value = (value ?: "") + parser.text
                    if (captureT) inline.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "v" -> captureV = false
                    "t" -> captureT = false
                    "c" -> {
                        val raw = inline.toString().ifBlank { value.orEmpty() }
                        val rendered = if (cellType == "s") raw.toIntOrNull()?.let(shared::getOrNull).orEmpty() else raw
                        if (rendered.isNotBlank()) out.append(if (cellRef.isBlank()) "• " else "$cellRef: ").append(rendered).append('\n')
                    }
                }
            }
            parser.next()
        }
        return out.toString()
    }

    private fun extractXmlText(
        bytes: ByteArray,
        textTags: Set<String>,
        paragraphTags: Set<String>,
        captureElementText: Boolean = false
    ): String {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
        val out = StringBuilder()
        var captureDepth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val name = if (parser.eventType == XmlPullParser.START_TAG || parser.eventType == XmlPullParser.END_TAG) {
                parser.name.substringAfter(':')
            } else ""
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (name in textTags) captureDepth++
                XmlPullParser.TEXT -> if (captureDepth > 0) out.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (name in textTags && captureDepth > 0) captureDepth--
                    if (name in paragraphTags) out.append('\n')
                }
            }
            parser.next()
        }
        return out.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun sniffReadableText(context: Context, uri: Uri): String {
        val bytes = open(context, uri).use { it.readBytesLimited(512 * 1024) }
        if (bytes.isEmpty()) return ""
        val zeroes = bytes.count { it == 0.toByte() }
        val controls = bytes.count { value ->
            val v = value.toInt() and 0xff
            v < 9 || (v in 14..31)
        }
        if (zeroes > bytes.size / 100 || controls > bytes.size / 20) return ""
        return bytes.toString(Charsets.UTF_8)
    }

    private fun zipEntries(context: Context, uri: Uri, accept: (String) -> Boolean): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0
        open(context, uri).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && accept(entry.name)) {
                        val bytes = zip.readBytesLimited(MAX_ENTRY_BYTES)
                        total += bytes.size
                        if (total > MAX_ZIP_BYTES) error("Document is too large to extract safely")
                        result[entry.name] = bytes
                    }
                    zip.closeEntry()
                }
            }
        }
        return result
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) error("Attachment is too large to read safely")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun slideNumber(path: String): Int = Regex("(\\d+)(?=\\.xml$)")
        .find(path)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}
