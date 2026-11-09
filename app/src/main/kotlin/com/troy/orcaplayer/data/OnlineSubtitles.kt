package com.troy.orcaplayer.data

import com.troy.orcaplayer.BuildConfig
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** A subtitle that can be fetched, from any online source (9.6). */
data class OnlineSubtitle(
    val id: String,
    val language: String?,
    val label: String,
    val source: SourceRef,
    /** Direct link, or null when it needs a download step first (OpenSubtitles). */
    val url: String?,
    val headers: Map<String, String> = emptyMap(),
    /** 0..100: how well it matches the video (hash match 100, release name match 70, title match 40). */
    val match: Int = 40,
    val downloads: Int = 0,
    val openSubtitlesFileId: Long? = null,
)

/**
 * OpenSubtitles.com with the user's own API key, and downloading of subtitle files. The file
 * hash is the OpenSubtitles hash (size plus the sums of the first and last 64 KB).
 */
class OnlineSubtitles(private val cacheDir: File) {
    private val api = "https://api.opensubtitles.com/api/v1"
    private val ref = SourceRef(SourceKind.STREMIO_ADDON, "opensubtitles", "OpenSubtitles")
    private val ua = "ORCA Player v${BuildConfig.VERSION_NAME}"

    suspend fun searchOpenSubtitles(
        apiKey: String,
        languages: List<String>,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        movieHash: String? = null,
        query: String? = null,
    ): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val params = buildList {
            imdbId?.removePrefix("tt")?.toLongOrNull()?.let { add((if (season != null) "parent_imdb_id" else "imdb_id") to it.toString()) }
            season?.let { add("season_number" to it.toString()) }
            episode?.let { add("episode_number" to it.toString()) }
            movieHash?.let { add("moviehash" to it) }
            if (imdbId == null && movieHash == null) query?.let { add("query" to it) }
            if (languages.isNotEmpty()) add("languages" to languages.distinct().sorted().joinToString(","))
        }.sortedBy { it.first }
        if (params.none { it.first != "languages" }) return@withContext emptyList()
        val url = "$api/subtitles?" + params.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
        val body = request(url, "GET", apiKey, null)
        val root = runCatching { StoreJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext emptyList()
        (root["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            runCatching {
                val attrs = (el as JsonObject)["attributes"]!!.jsonObject
                val file = (attrs["files"] as JsonArray).first().jsonObject
                val fileId = (file["file_id"] as JsonPrimitive).longOrNull ?: return@runCatching null
                val lang = (attrs["language"] as? JsonPrimitive)?.contentOrNull
                val release = (attrs["release"] as? JsonPrimitive)?.contentOrNull ?: (file["file_name"] as? JsonPrimitive)?.contentOrNull ?: ""
                val hashMatch = (attrs["moviehash_match"] as? JsonPrimitive)?.booleanOrNull == true
                OnlineSubtitle(
                    id = "os:$fileId",
                    language = lang,
                    label = release.ifEmpty { lang ?: "?" },
                    source = ref,
                    url = null,
                    match = if (hashMatch) 100 else if (imdbId != null) 60 else 40,
                    downloads = (attrs["download_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                    openSubtitlesFileId = fileId,
                )
            }.getOrNull()
        }
    }

    private val freeRef = SourceRef(SourceKind.STREMIO_ADDON, "org.stremio.opensubtitlesv3", "OpenSubtitles v3")

    /**
     * Stremio's public OpenSubtitles v3 service: no account or key, answers IMDb IDs ("tt…" or
     * "tt…:season:episode") and returns files already converted to UTF-8. This is what makes
     * Arabic subtitles available even when the user has set nothing up.
     */
    suspend fun searchFree(series: Boolean, imdbVideoId: String, languages: List<String>): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        if (!imdbVideoId.startsWith("tt")) return@withContext emptyList()
        val type = if (series) "series" else "movie"
        val url = "https://opensubtitles-v3.strem.io/subtitles/$type/" + URLEncoder.encode(imdbVideoId, "UTF-8") + ".json"
        val text = runCatching { getText(url) }.getOrNull() ?: return@withContext emptyList()
        val root = runCatching { StoreJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@withContext emptyList()
        (root["subtitles"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val link = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val lang = (o["lang"] as? JsonPrimitive)?.contentOrNull
            if (languages.isNotEmpty() && languages.none { l -> sameLanguage(lang, l) }) return@mapNotNull null
            OnlineSubtitle(
                id = link,
                language = lang,
                label = "OpenSubtitles " + (lang ?: "").uppercase(),
                source = freeRef,
                url = link,
                match = 55,
            )
        }
    }

    private fun sameLanguage(code: String?, preferred: String): Boolean {
        val a = code?.lowercase()?.substringBefore('-') ?: return false
        val b = preferred.lowercase().substringBefore('-')
        if (a == b) return true
        val three = mapOf("ar" to "ara", "en" to "eng", "fr" to "fre", "es" to "spa", "de" to "ger", "tr" to "tur", "pt" to "por",
            "it" to "ita", "ru" to "rus", "hi" to "hin", "ja" to "jpn", "ko" to "kor", "zh" to "chi", "fa" to "per", "ur" to "urd", "id" to "ind")
        return three[b] == a || three[a] == b || (b == "fr" && a == "fra") || (b == "de" && a == "deu") || (b == "zh" && a == "zho") || (b == "fa" && a == "fas")
    }

    private fun getText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { String(it.readNBytesCompat(4 * 1024 * 1024), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** Fetches the subtitle file to the cache and returns it. */
    suspend fun download(sub: OnlineSubtitle, apiKey: String): File = withContext(Dispatchers.IO) {
        val link = sub.url ?: run {
            val id = sub.openSubtitlesFileId ?: throw IOException("No link")
            val body = request("$api/download", "POST", apiKey, "{\"file_id\":$id}")
            val root = StoreJson.parseToJsonElement(body).jsonObject
            (root["link"] as? JsonPrimitive)?.contentOrNull ?: throw IOException((root["message"] as? JsonPrimitive)?.contentOrNull ?: "Download refused")
        }
        fetch(link, sub.headers, sub.label)
    }

    /** Downloads a subtitle link (stream subtitles, subtitle addons) to the cache. */
    suspend fun fetch(url: String, headers: Map<String, String>, label: String): File = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            var bytes = conn.inputStream.use { input ->
                val buf = input.readNBytesCompat(10 * 1024 * 1024 + 1)
                if (buf.size > 10 * 1024 * 1024) throw IOException("Subtitle file too large")
                buf
            }
            // Some sources send gzip-compressed files.
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            }
            val ext = url.substringBefore('?').substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub", "ttml") } ?: "srt"
            val dir = File(cacheDir, "online-subs").apply { mkdirs() }
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val safe = label.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60).ifBlank { "subtitle" }
            File(dir, "$safe-$digest.$ext").also { it.writeBytes(bytes) }
        } finally {
            conn.disconnect()
        }
    }

    private fun request(url: String, method: String, apiKey: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { String(it.readNBytesCompat(2 * 1024 * 1024), Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { (StoreJson.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
                throw IOException(msg ?: "HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The OpenSubtitles hash of a local file, or null when it can't be read or is too small. */
        fun hashOf(file: File): String? = runCatching {
            val size = file.length()
            if (size < 131072) return null
            RandomAccessFile(file, "r").use { raf ->
                var hash = size
                val buf = ByteArray(65536)
                fun sum(offset: Long) {
                    raf.seek(offset)
                    raf.readFully(buf)
                    val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    while (bb.hasRemaining()) hash += bb.long
                }
                sum(0)
                sum(size - 65536)
                "%016x".format(hash)
            }
        }.getOrNull()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
