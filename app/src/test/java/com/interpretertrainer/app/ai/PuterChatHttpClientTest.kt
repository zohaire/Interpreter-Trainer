package com.interpretertrainer.app.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class PuterChatHttpClientTest {
    private class Connection(
        private val response: String = "",
        private val code: Int = 200,
        private val type: String = "application/json",
        private val failure: IOException? = null
    ) : HttpURLConnection(URL(PuterChatHttpClient.ENDPOINT)) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int { failure?.let { throw it }; return code }
        override fun getContentType() = type
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    }

    @Test fun postsToFixedEndpointAndReturnsJson() {
        val connection = Connection("{\"success\":true,\"result\":{\"message\":{\"content\":\"Hello\"}}}")
        val events = mutableListOf<Triple<String, String, Int>>()
        PuterChatHttpClient { connection }.execute("{\"auth_token\":\"test-token\"}") { kind, data, status -> events.add(Triple(kind, data, status)) }
        assertEquals("https://api.puter.com/drivers/call", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertEquals("{\"auth_token\":\"test-token\"}", connection.sent.toString("UTF-8"))
        assertEquals("result", events.single().first)
        assertTrue(events.single().second.contains("Hello"))
        assertTrue(connection.disconnected)
    }

    @Test fun preservesNdjsonAndUtf8IncludingLastUnterminatedLine() {
        val connection = Connection("{\"text\":\"Bonjour\"}\n\n{\"text\":\"العالم\"}", type = "application/x-ndjson; charset=utf-8")
        val events = mutableListOf<Pair<String, String>>()
        PuterChatHttpClient { connection }.execute("{}") { kind, data, _ -> events.add(kind to data) }
        assertEquals(listOf("started", "part", "part", "done"), events.map { it.first })
        assertEquals("{\"text\":\"العالم\"}", events[2].second)
    }

    @Test fun accountErrorsAndRedirectsAreReturnedWithoutFollowingThem() {
        for (status in listOf(302, 401, 403, 429, 503)) {
            val connection = Connection("{\"error\":{\"code\":\"denied\"}}", status)
            val events = mutableListOf<Triple<String, String, Int>>()
            PuterChatHttpClient { connection }.execute("{}") { kind, data, code -> events.add(Triple(kind, data, code)) }
            assertEquals("http_error", events.single().first)
            assertEquals(status, events.single().third)
            assertFalse(connection.instanceFollowRedirects)
        }
    }

    @Test fun distinguishesTimeoutTlsAndNetworkWithoutLeakingRequestData() {
        for ((exception, expected) in listOf(
            SocketTimeoutException("secret details") to "REQUEST_TIMEOUT",
            SSLException("secret details") to "TLS_ERROR",
            IOException("secret details") to "NETWORK_ERROR"
        )) {
            val events = mutableListOf<Pair<String, String>>()
            PuterChatHttpClient { Connection(failure = exception) }.execute("private request") { kind, data, _ -> events.add(kind to data) }
            assertEquals("transport_error" to expected, events.single())
        }
    }

    @Test fun cancellationClosesStreamAndSuppressesLaterEvents() {
        val connection = Connection("{\"text\":\"first\"}\n{\"text\":\"second\"}\n", type = "application/x-ndjson")
        val client = PuterChatHttpClient { connection }
        val events = mutableListOf<String>()
        client.execute("{}") { kind, _, _ ->
            events.add(kind)
            if (kind == "part") client.cancel()
        }
        assertEquals(listOf("started", "part"), events)
        assertTrue(connection.disconnected)
    }
}
