package com.interpretertrainer.app.media

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class MediaLinkKind {
    DIRECT_MEDIA,
    YOUTUBE,
    VIMEO,
    WEB_PAGE
}

data class ResolvedMediaLink(
    val normalizedUrl: String,
    val playbackUrl: String,
    val kind: MediaLinkKind,
    val displayName: String
) {
    val usesNativePlayer: Boolean get() = kind == MediaLinkKind.DIRECT_MEDIA
}

object MediaLinkResolver {
    private val directExtensions = setOf(
        "mp4", "m4v", "webm", "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus",
        "m3u8", "mpd", "ts", "flac"
    )

    fun resolve(rawInput: String): Result<ResolvedMediaLink> = runCatching {
        val extracted = extractLink(rawInput)
        require(extracted.isNotBlank()) { "Paste a media or webpage link first." }

        val normalized = normalizeScheme(extracted)
        val uri = URI(normalized)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https") { "Use a secure https:// link." }

        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: error("That link does not contain a valid website address.")

        youtubeId(uri, host)?.let { id ->
            return@runCatching ResolvedMediaLink(
                normalizedUrl = normalized,
                playbackUrl = "https://www.youtube.com/embed/$id?playsinline=1&rel=0",
                kind = MediaLinkKind.YOUTUBE,
                displayName = "YouTube"
            )
        }

        vimeoId(uri, host)?.let { id ->
            return@runCatching ResolvedMediaLink(
                normalizedUrl = normalized,
                playbackUrl = "https://player.vimeo.com/video/$id",
                kind = MediaLinkKind.VIMEO,
                displayName = "Vimeo"
            )
        }

        val extension = uri.path.orEmpty()
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)

        if (extension in directExtensions) {
            ResolvedMediaLink(
                normalizedUrl = normalized,
                playbackUrl = normalized,
                kind = MediaLinkKind.DIRECT_MEDIA,
                displayName = uri.path.orEmpty().substringAfterLast('/').ifBlank { host }
            )
        } else {
            ResolvedMediaLink(
                normalizedUrl = normalized,
                playbackUrl = normalized,
                kind = MediaLinkKind.WEB_PAGE,
                displayName = host
            )
        }
    }

    private fun extractLink(input: String): String {
        val trimmed = input.trim().trim('"', '\'')
        val explicit = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(trimmed)?.value
        return (explicit ?: trimmed)
            .trim()
            .trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'')
    }

    private fun normalizeScheme(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun youtubeId(uri: URI, host: String): String? {
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            isDomain(host, "youtube.com") || isDomain(host, "youtube-nocookie.com") -> when {
                uri.path == "/watch" -> queryParameters(uri.rawQuery)["v"]
                segments.firstOrNull() in setOf("shorts", "embed", "live") -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
    }

    private fun vimeoId(uri: URI, host: String): String? {
        if (!isDomain(host, "vimeo.com")) return null
        return uri.path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .lastOrNull { it.all(Char::isDigit) }
    }

    private fun queryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isBlank()) return@mapNotNull null
            val value = part.substringAfter('=', "")
            decode(key) to decode(value)
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun isDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
