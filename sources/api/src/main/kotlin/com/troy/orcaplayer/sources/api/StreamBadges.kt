package com.troy.orcaplayer.sources.api

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class HdrTag { DOLBY_VISION, HDR10_PLUS, HDR10, HLG, HDR }

@Serializable
enum class AudioTag { ATMOS, TRUEHD, DTS_X, DTS_HD, DTS, DDP, DD, AAC, OPUS, FLAC }

/** What the free-text name and description of a stream say about it (14.6). */
@Serializable
data class StreamInfo(
    /** Vertical resolution: 2160, 1440, 1080, 720, 576, 480 or 360. */
    val resolution: Int? = null,
    val hdr: Set<HdrTag> = emptySet(),
    val audio: Set<AudioTag> = emptySet(),
    /** "7.1", "5.1" or "2.0". */
    val channels: String? = null,
    /** HEVC, AVC, AV1 or VP9. */
    val codec: String? = null,
    val sizeBytes: Long? = null,
    /** ISO 639-1 codes, plus "multi" for multi-audio releases. */
    val languages: Set<String> = emptySet(),
    /** REMUX, BluRay, WEB-DL, WEBRip, HDTV, DVD, CAM or TS. */
    val origin: String? = null,
    val insecure: Boolean = false,
    /** Subtitles are mentioned (or listed with the stream). */
    val hasSubtitles: Boolean = false,
)

/**
 * The one parser that turns stream names and descriptions into badges. Real addons write things
 * like "Torrentio\n4k HDR", "💾 2.3 GB 👤 12", "1080p | WEB-DL | DDP5.1 Atmos" or "🇸🇦 / 🇬🇧".
 */
object StreamBadges {
    private val sizeRe = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|GiB|MB|MiB|KB)\b""", RegexOption.IGNORE_CASE)
    private val ddRe = Regex("""\bdd ?[257][. ]?[01]""")
    private val channelsRe = Regex("""(?<!\d)(?<!\d\.)([257])[ .]?([01])(?:ch)?(?![\d])""", RegexOption.IGNORE_CASE)

    private val languageNames: Map<String, String> = mapOf(
        "english" to "en", "arabic" to "ar", "french" to "fr", "spanish" to "es", "latino" to "es", "castellano" to "es",
        "german" to "de", "italian" to "it", "portuguese" to "pt", "russian" to "ru", "turkish" to "tr",
        "hindi" to "hi", "japanese" to "ja", "korean" to "ko", "chinese" to "zh", "persian" to "fa", "farsi" to "fa",
        "urdu" to "ur", "dutch" to "nl", "polish" to "pl", "swedish" to "sv", "greek" to "el", "hebrew" to "he",
        "indonesian" to "id", "malay" to "ms", "thai" to "th", "vietnamese" to "vi", "tamil" to "ta", "telugu" to "te",
        "عربي" to "ar", "العربية" to "ar", "مدبلج" to "ar",
    )
    private val languageCodes: Map<String, String> = mapOf(
        "eng" to "en", "ara" to "ar", "arb" to "ar", "fre" to "fr", "fra" to "fr", "spa" to "es", "ger" to "de", "deu" to "de",
        "ita" to "it", "por" to "pt", "rus" to "ru", "tur" to "tr", "hin" to "hi", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh", "per" to "fa", "fas" to "fa",
    )
    private val flags: Map<String, String> = mapOf(
        "GB" to "en", "US" to "en", "SA" to "ar", "EG" to "ar", "AE" to "ar", "MA" to "ar", "DZ" to "ar", "IQ" to "ar",
        "SY" to "ar", "LB" to "ar", "JO" to "ar", "KW" to "ar", "QA" to "ar", "TN" to "ar", "SD" to "ar",
        "FR" to "fr", "ES" to "es", "MX" to "es", "DE" to "de", "IT" to "it", "PT" to "pt", "BR" to "pt", "RU" to "ru",
        "TR" to "tr", "IN" to "hi", "JP" to "ja", "KR" to "ko", "CN" to "zh", "IR" to "fa", "NL" to "nl", "PL" to "pl",
        "SE" to "sv", "GR" to "el", "IL" to "he", "ID" to "id", "TH" to "th", "VN" to "vi", "PK" to "ur",
    )

    fun parse(vararg texts: String?, url: String? = null, sizeHint: Long? = null, subtitles: Boolean = false): StreamInfo {
        val raw = texts.filterNotNull().joinToString("\n")
        val t = raw.lowercase(Locale.ROOT)
        // Tokens split on anything that isn't a letter or digit, so "DDP5.1" and "[ENG]" both work.
        val tokens = t.split(Regex("""[^\p{L}\p{N}+]+""")).filter { it.isNotEmpty() }.toSet()

        val resolution = when {
            "2160p" in tokens || "4k" in tokens || "uhd" in tokens || t.contains("2160") -> 2160
            "1440p" in tokens || "2k" in tokens -> 1440
            "1080p" in tokens || "1080i" in tokens || "fhd" in tokens || t.contains("1080") -> 1080
            "720p" in tokens || "hd" in tokens && "fhd" !in tokens && "uhd" !in tokens -> 720
            "576p" in tokens -> 576
            "480p" in tokens || "sd" in tokens -> 480
            "360p" in tokens -> 360
            else -> null
        }

        val hdr = buildSet {
            if ("dv" in tokens || t.contains("dolby vision") || t.contains("dovi") || "dolbyvision" in tokens) add(HdrTag.DOLBY_VISION)
            if (t.contains("hdr10+") || "hdr10plus" in tokens) add(HdrTag.HDR10_PLUS)
            else if ("hdr10" in tokens) add(HdrTag.HDR10)
            if ("hlg" in tokens) add(HdrTag.HLG)
            if (isEmpty() && "hdr" in tokens) add(HdrTag.HDR)
        }

        val audio = buildSet {
            if ("atmos" in tokens) add(AudioTag.ATMOS)
            if ("truehd" in tokens || t.contains("true-hd")) add(AudioTag.TRUEHD)
            if (t.contains("dts:x") || t.contains("dts-x") || "dtsx" in tokens) add(AudioTag.DTS_X)
            else if (t.contains("dts-hd") || "dtshd" in tokens || t.contains("dts-ma")) add(AudioTag.DTS_HD)
            else if ("dts" in tokens || t.contains("dts")) add(AudioTag.DTS)
            if (t.contains("ddp") || t.contains("dd+") || t.contains("e-ac-3") || t.contains("eac3") || t.contains("e-ac3")) add(AudioTag.DDP)
            else if ("dd" in tokens || ddRe.containsMatchIn(t) || t.contains("ac3") || t.contains("ac-3") || t.contains("dolby digital")) add(AudioTag.DD)
            if ("aac" in tokens || t.contains("aac")) add(AudioTag.AAC)
            if ("opus" in tokens) add(AudioTag.OPUS)
            if ("flac" in tokens) add(AudioTag.FLAC)
        }

        val channels = channelsRe.find(t)?.let { m ->
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if ((a == "7" || a == "5") && b == "1" || a == "2" && b == "0") "$a.$b" else null
        }

        val codec = when {
            "hevc" in tokens || "x265" in tokens || "h265" in tokens || t.contains("h.265") -> "HEVC"
            "av1" in tokens -> "AV1"
            "vp9" in tokens -> "VP9"
            "x264" in tokens || "h264" in tokens || "avc" in tokens || t.contains("h.264") -> "AVC"
            else -> null
        }

        val size = sizeHint?.takeIf { it > 0 } ?: sizeRe.findAll(raw).mapNotNull { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val mult = when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "tb" -> 1024.0 * 1024 * 1024 * 1024
                "gb", "gib" -> 1024.0 * 1024 * 1024
                "mb", "mib" -> 1024.0 * 1024
                else -> 1024.0
            }
            (n * mult).toLong()
        }.maxOrNull()

        val languages = buildSet {
            languageNames.forEach { (k, v) -> if (t.contains(k)) add(v) }
            tokens.forEach { tok -> languageCodes[tok]?.let(::add) }
            if ("multi" in tokens || t.contains("multi-audio") || "dual" in tokens) add("multi")
            flagsIn(raw).forEach { cc -> flags[cc]?.let(::add) }
        }

        val origin = when {
            "remux" in tokens -> "REMUX"
            "bluray" in tokens || t.contains("blu-ray") || "bdrip" in tokens || "brrip" in tokens -> "BluRay"
            t.contains("web-dl") || "webdl" in tokens -> "WEB-DL"
            "webrip" in tokens || t.contains("web-rip") -> "WEBRip"
            "hdtv" in tokens -> "HDTV"
            "dvdrip" in tokens || "dvd" in tokens -> "DVD"
            "cam" in tokens || "camrip" in tokens || "hdcam" in tokens -> "CAM"
            "telesync" in tokens || "hdts" in tokens -> "TS"
            else -> null
        }

        return StreamInfo(
            resolution = resolution,
            hdr = hdr,
            audio = audio,
            channels = channels,
            codec = codec,
            sizeBytes = size,
            languages = languages,
            origin = origin,
            insecure = url?.startsWith("http:", ignoreCase = true) == true,
            hasSubtitles = subtitles || "subs" in tokens || "subtitles" in tokens || "sub" in tokens || t.contains("مترجم"),
        )
    }

    /** Regional-indicator flag emoji as two-letter country codes. */
    fun flagsIn(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val cps = text.codePoints().toArray()
        while (i + 1 < cps.size) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                out += String(charArrayOf('A' + (a - 0x1F1E6), 'A' + (b - 0x1F1E6)))
                i += 2
            } else {
                i++
            }
        }
        return out
    }
}

/** Ordering of the stream picker. */
@Serializable
enum class StreamSort { QUALITY, SIZE_LARGEST, SIZE_SMALLEST, LANGUAGE, SOURCE }

object StreamRanking {
    /**
     * Sorts by the user's choice. [preferredQuality] (for example 1080) puts that resolution first
     * when sorting by quality, then higher, then lower. [preferredLanguage] lifts streams in that
     * language. [sourceOrder] is the user's source order (index of the source ID).
     */
    fun <T> sort(
        items: List<T>,
        info: (T) -> StreamInfo,
        sourceIndex: (T) -> Int,
        sort: StreamSort,
        preferredQuality: Int? = null,
        preferredLanguage: String? = null,
    ): List<T> {
        fun qualityKey(i: StreamInfo): Int {
            val r = i.resolution ?: 0
            if (preferredQuality == null || r == 0) return -r
            return when {
                r == preferredQuality -> -100_000
                r > preferredQuality -> -50_000 + (r - preferredQuality)
                else -> -r
            }
        }
        fun langKey(i: StreamInfo): Int = if (preferredLanguage != null && (preferredLanguage in i.languages || "multi" in i.languages)) 0 else 1
        fun hdrKey(i: StreamInfo): Int = if (i.hdr.isEmpty()) 1 else 0
        fun sizeKey(i: StreamInfo): Long = i.sizeBytes ?: 0

        val cmp: Comparator<T> = when (sort) {
            StreamSort.QUALITY -> compareBy<T>({ langKey(info(it)) }, { qualityKey(info(it)) }, { hdrKey(info(it)) }, { -sizeKey(info(it)) }, { sourceIndex(it) })
            StreamSort.SIZE_LARGEST -> compareBy({ langKey(info(it)) }, { -sizeKey(info(it)) }, { sourceIndex(it) })
            StreamSort.SIZE_SMALLEST -> compareBy({ langKey(info(it)) }, { info(it).sizeBytes ?: Long.MAX_VALUE }, { sourceIndex(it) })
            StreamSort.LANGUAGE -> compareBy({ langKey(info(it)) }, { info(it).languages.isEmpty() }, { qualityKey(info(it)) }, { sourceIndex(it) })
            StreamSort.SOURCE -> compareBy({ sourceIndex(it) }, { qualityKey(info(it)) })
        }
        return items.sortedWith(cmp)
    }
}
