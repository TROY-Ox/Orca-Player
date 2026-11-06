package com.troy.orcaplayer.sources.stremio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** The one thing the addon client needs from the network, so tests can fake it. */
fun interface HttpClient {
    /** Throws [IOException] on network errors and timeouts. Any HTTP status is returned, not thrown. */
    suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse
}

class ResponseTooLarge(limit: Long) : IOException("Response is larger than ${limit / 1024} KB")

/**
 * A plain HttpURLConnection client: follows redirects (also https to http, which addons do), gzip
 * is handled by the platform, and the body is cut off at [maxBytes] so one bad addon can't fill
 * memory.
 */
class UrlConnectionClient(
    private val userAgent: String,
    private val maxBytes: Long = 8L * 1024 * 1024,
) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse = withContext(Dispatchers.IO) {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", "application/json, */*;q=0.5")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location") ?: return@withContext HttpResponse(code, "")
                    current = URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                conn.contentLengthLong.takeIf { it > maxBytes }?.let { throw ResponseTooLarge(maxBytes) }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw ResponseTooLarge(maxBytes)
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                val respHeaders = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
                return@withContext HttpResponse(code, String(bytes, Charsets.UTF_8), respHeaders)
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
