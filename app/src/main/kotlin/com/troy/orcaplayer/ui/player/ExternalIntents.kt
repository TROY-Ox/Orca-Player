package com.troy.orcaplayer.ui.player

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.UriSchemes
import com.troy.orcaplayer.player.engine.DecoderMode
import com.troy.orcaplayer.sources.local.LocalKeys

/**
 * "Open with" and the external-player extras used with MX Player and VLC: title, start position,
 * HTTP headers, subtitle files with names, decoder mode, and returning the final position.
 */
object ExternalIntents {
    private val urlRegex = Regex("""(?i)\b(https?|rtsp|rtmp)://[^\s<>"']+""")

    /** Builds a request from a VIEW or SEND intent sent by another app, or null when it has nothing playable. */
    fun parse(intent: Intent, resolver: ContentResolver): Pair<PlayRequest, LaunchOptions>? {
        val extras = intent.extras ?: Bundle.EMPTY
        val primary: Uri = when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = parcelable<Uri>(intent, Intent.EXTRA_STREAM)
                stream ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { urlRegex.find(it)?.value }?.let(Uri::parse)
            }
            else -> intent.data
        } ?: return null
        if (!UriSchemes.isAllowed(primary.toString())) return null

        val headers = headersFrom(extras)
        val title = extras.getString("title") ?: extras.getString("itemTitle") ?: displayName(resolver, primary)
        val position = startPosition(extras)

        // MX Player "video_list": a whole queue in one intent.
        val list = parcelableArray(intent, "video_list").filter { UriSchemes.isAllowed(it.toString()) }
        val names = extras.getStringArray("video_list.name")
        val uris = if (list.isNotEmpty()) list else listOf(primary)
        val startIndex = uris.indexOf(primary).coerceAtLeast(0)
        val items = uris.mapIndexed { i, u ->
            val name = if (u == primary) title else names?.getOrNull(i) ?: displayName(resolver, u)
            item(u, name, headers, resolver, if (i == startIndex) position else null)
        }

        val subs = parcelableArray(intent, "subs").map { it.toString() }
        val subNames = extras.getStringArray("subs.name")
        val vlcSub = extras.getString("subtitles_location")
        val subtitles = subs.mapIndexed { i, s -> s to subNames?.getOrNull(i) } + listOfNotNull(vlcSub?.let { it to null })
        val enabled = parcelableArray(intent, "subs.enable").firstOrNull()?.toString()

        val decode = numberExtra(extras, "decode_mode")?.toInt()?.let(DecoderMode::fromMxExtra)
        val options = LaunchOptions(
            decoderMode = decode,
            returnResult = extras.getBoolean("return_result", false) || intent.action == "org.videolan.vlc.player.result",
            subtitles = subtitles,
            enabledSubtitle = enabled,
        )
        return PlayRequest(items, startIndex) to options
    }

    private fun item(uri: Uri, title: String, headers: Map<String, String>, resolver: ContentResolver, startMs: Long?): PlayItem {
        val s = uri.toString()
        val local = uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE
        val key = if (local) LocalKeys.forUri(resolver, uri) ?: MediaKeys.forLink(s) else MediaKeys.forLink(s)
        return PlayItem(
            uri = s,
            title = title,
            key = key,
            source = if (local) SourceRef(SourceKind.LOCAL, "local") else SourceRef(SourceKind.LINK, "link"),
            headers = headers,
            startPositionMs = startMs,
            canSave = local,
        )
    }

    /** MX: "headers" is a String[] of name, value, name, value. Some apps send a Bundle instead. */
    private fun headersFrom(extras: Bundle): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        extras.getStringArray("headers")?.let { arr ->
            var i = 0
            while (i + 1 < arr.size) {
                out[arr[i]] = arr[i + 1]
                i += 2
            }
        }
        extras.getBundle("headers")?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { out[k] = it } } }
        extras.getBundle("android.media.intent.extra.HTTP_HEADERS")?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { out[k] = it } } }
        return out.filterKeys { it.isNotBlank() && it.none { c -> c == '\r' || c == '\n' } }
            .filterValues { it.none { c -> c == '\r' || c == '\n' } }
    }

    /** MX "position" (int ms), VLC "position" (long ms) or "from_start". */
    private fun startPosition(extras: Bundle): Long? {
        if (extras.getBoolean("from_start", false)) return 0L
        return numberExtra(extras, "position")?.toLong()?.takeIf { it >= 0 }
    }

    @Suppress("DEPRECATION")
    private fun numberExtra(extras: Bundle, key: String): Number? = extras.get(key) as? Number

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: uri.host ?: uri.toString()
    }

    private inline fun <reified T : Parcelable> parcelable(intent: Intent, key: String): T? =
        IntentCompat.getParcelableExtra(intent, key, T::class.java)

    private fun parcelableArray(intent: Intent, key: String): List<Uri> =
        IntentCompat.getParcelableArrayExtra(intent, key, Uri::class.java)?.mapNotNull { it as? Uri }.orEmpty()

    /** Result for the calling app: MX Player and VLC both read their own extras. */
    fun result(positionMs: Long, durationMs: Long, completed: Boolean): Intent = Intent("com.mxtech.intent.result.VIEW").apply {
        putExtra("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        putExtra("duration", durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        putExtra("end_by", if (completed) "playback_completion" else "user")
        putExtra("extra_position", positionMs)
        putExtra("extra_duration", durationMs)
    }
}
