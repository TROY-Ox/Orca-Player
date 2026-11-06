package com.troy.orcaplayer.sources.stremio

import com.troy.orcaplayer.core.model.TitleType
import java.net.URLEncoder

object StremioUrls {
    /**
     * Turns what the user pasted into a manifest URL: `stremio://` becomes `https://`, a bare
     * host gets `https://`, and a base URL without `manifest.json` gets it added.
     * Returns null when the text isn't a web link at all.
     */
    fun normalizeManifestUrl(input: String): String? {
        var s = input.trim().trim('"', '\'', '<', '>')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        s = when {
            s.startsWith("stremio://", ignoreCase = true) -> "https://" + s.substring("stremio://".length)
            s.startsWith("https://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true) -> s
            s.contains("://") -> return null
            else -> "https://$s"
        }
        val noQuery = s.substringBefore('?').substringBefore('#')
        val hostPart = noQuery.substringAfter("://").substringBefore('/')
        if (hostPart.isEmpty() || !hostPart.contains('.') && !hostPart.startsWith("localhost") && !hostPart.contains(':')) return null
        if (!noQuery.endsWith("manifest.json")) {
            s = noQuery.trimEnd('/') + "/manifest.json"
        }
        return s
    }

    /** The base every resource URL is built on: the manifest URL without `/manifest.json`. */
    fun baseOf(manifestUrl: String): String =
        manifestUrl.substringBefore('?').substringBefore('#').removeSuffix("manifest.json").trimEnd('/')

    /** The URL a configurable addon's settings page lives at. */
    fun configureUrl(manifestUrl: String): String = baseOf(manifestUrl) + "/configure"

    /** `{base}/{resource}/{type}/{id}[/{extras}].json`, with each part URL-encoded. */
    fun resource(base: String, resource: String, type: String, id: String, extras: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base.trimEnd('/'))
            .append('/').append(enc(resource))
            .append('/').append(enc(type))
            .append('/').append(enc(id))
        val usable = extras.filter { it.value.isNotEmpty() }
        if (usable.isNotEmpty()) {
            sb.append('/').append(usable.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })
        }
        return sb.append(".json").toString()
    }

    /** Encodes like JavaScript's encodeURIComponent, which is what the SDK expects (spaces as %20). */
    fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    fun typeName(type: TitleType): String = when (type) {
        TitleType.MOVIE -> "movie"
        TitleType.SERIES -> "series"
        TitleType.CHANNEL -> "channel"
        TitleType.TV -> "tv"
        TitleType.OTHER -> "other"
    }

    fun typeOf(name: String?): TitleType = when (name?.lowercase()) {
        "movie", "movies", "film" -> TitleType.MOVIE
        "series", "show", "anime" -> TitleType.SERIES
        "channel" -> TitleType.CHANNEL
        "tv" -> TitleType.TV
        else -> TitleType.OTHER
    }
}
