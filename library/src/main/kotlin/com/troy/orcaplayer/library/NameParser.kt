package com.troy.orcaplayer.library

/** What a file and its folders say about a video (20.1). */
data class ParsedName(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** A second episode in the same file ("S01E01E02"). */
    val episodeEnd: Int? = null,
    /** Set for anime-style absolute numbering ("[Group] Show - 105"). */
    val absolute: Boolean = false,
) {
    val isEpisode: Boolean get() = episode != null
}

/**
 * Reads titles, years and episode numbers from file and folder names, the way release groups,
 * media servers and people name them: "Movie (2020).mkv", "Show.S01E02.1080p.mkv",
 * "Show/Season 1/02 - Name.mkv", "[Group] Show - 05 [1080p].mkv" and similar.
 */
object NameParser {
    private val sxe = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?:-?e(\d{1,3})|-(\d{1,3})(?![0-9p]))?(?![0-9])""")
    private val nxn = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9p])""")
    private val words = Regex("""(?i)\bseason[ ._-]*(\d{1,2})[ ._-]*(?:episode|ep)[ ._-]*(\d{1,3})\b""")
    private val episodeOnly = Regex("""(?i)(?:^|[ ._\-\[(])(?:e|ep|episode)[ ._-]*(\d{1,3})(?![0-9])""")
    private val seasonFolder = Regex("""(?i)^(?:season|series|staffel|saison|temporada|s)[ ._-]*(\d{1,2})$""")
    private val specialsFolder = Regex("""(?i)^(?:specials?|extras?)$""")
    private val anime = Regex("""^\s*(?:\[[^\]]*]\s*)+(.+?)\s+-\s+(\d{1,4})(?:v\d)?(?:\s|\[|\(|$)""")
    private val leadingNumber = Regex("""^(\d{1,3})(?:\s*[-._ ]\s*|$)""")
    private val trailingNumber = Regex("""(?<![0-9])(\d{1,3})$""")
    private val resolution = Regex("""(?i)\b\d{3,4}x\d{3,4}\b""")
    private val yearRx = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")
    private val brackets = Regex("""\[[^\]]*]|\{[^}]*}""")
    private val junk = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr10\+?|hdr|dv|dolby[ .]?vision|sdr|x264|x265|h[ .]?264|h[ .]?265|hevc|avc|av1|xvid|divx|""" +
            """bluray|blu-ray|bdrip|brrip|bdremux|remux|web[ .-]?dl|web[ .-]?rip|webrip|web|hdtv|hdrip|dvdrip|dvdscr|dvd|hdcam|cam|ts|""" +
            """aac\d?(\.\d)?|ac3|eac3|ddp?\d?(\.\d)?|dts(-hd)?|truehd|atmos|flac|opus|mp3|10bit|8bit|hi10p|""" +
            """proper|repack|extended|unrated|uncut|directors?[ .]cut|imax|remastered|limited|internal|multi|dual[ .]audio|subbed|dubbed|""" +
            """nf|amzn|dsnp|hmax|atvp|hulu)\b""",
    )
    private val generic = setOf("movie", "video", "film", "sample", "main", "feature", "title", "untitled")
    private val episodeWord = Regex("""(?i)^(?:episode|ep|e)\s*$""")

    /**
     * [folders] are the parent folder names from the nearest up, for example
     * listOf("Season 1", "Breaking Bad", "TV").
     */
    fun parse(fileName: String, folders: List<String> = emptyList()): ParsedName {
        val stem = fileName.substringBeforeLast('.', fileName).let { if (it.isBlank()) fileName else it }

        anime.find(stem)?.let { m ->
            val title = clean(m.groupValues[1])
            if (title.isNotEmpty() && !yearRx.matches(m.groupValues[2])) {
                return ParsedName(title = title, season = seasonFromFolders(folders), episode = m.groupValues[2].toInt(), absolute = seasonFromFolders(folders) == null)
            }
        }

        val spaced = normalize(stem)
        val match = sxe.find(spaced)?.let { Triple(it, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: words.find(spaced)?.let { Triple(it, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: nxn.find(spaced)?.takeUnless { looksLikeResolution(spaced, it) }?.let { Triple(it, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        if (match != null) {
            val (m, season, episode) = match
            val end = (m.groupValues.getOrNull(3)?.toIntOrNull() ?: m.groupValues.getOrNull(4)?.toIntOrNull())?.takeIf { it > episode }
            // "Doctor.Who.2005.S03E01": the year belongs to the show, not its name.
            val (before, year) = spaced.substring(0, m.range.first).let { if (clean(it).isEmpty()) "" to null else titleAndYear(it) }
            val title = before.ifEmpty { showFromFolders(folders) ?: "" }
            return ParsedName(title = title, year = year, season = season, episode = episode, episodeEnd = end)
        }

        // A file inside "Season N" (or "Specials"): the number comes from the file name.
        val folderSeason = seasonFromFolders(folders)
        if (folderSeason != null) {
            val ep = episodeOnly.find(spaced)?.groupValues?.get(1)?.toInt()
                ?: leadingNumber.find(spaced.trim())?.groupValues?.get(1)?.toInt()
                ?: trailingNumber.find(spaced.trim())?.groupValues?.get(1)?.toInt()
            if (ep != null) return ParsedName(title = showFromFolders(folders) ?: clean(spaced), season = folderSeason, episode = ep)
        }

        // A movie: the title runs up to the year or the first release tag.
        val (title, year) = titleAndYear(spaced)
        val folder = folders.firstOrNull()?.let { titleAndYear(normalize(it)) }?.takeIf { it.first.isNotEmpty() }
        if (title.isEmpty() || episodeWord.matches(title) || key(title) in generic) {
            return ParsedName(title = folder?.first ?: clean(spaced), year = year ?: folder?.second)
        }
        // "Arrival (2016)/arrival.1080p.mkv": the folder knows the year.
        if (year == null && folder?.second != null) {
            val t = if (key(title).contains(key(folder.first))) title else folder.first
            return ParsedName(title = t, year = folder.second)
        }
        return ParsedName(title = title, year = year)
    }

    /** "Movie (2020)" or "Movie.2020.1080p" → ("Movie", 2020). A title that is only a year stays as it is. */
    fun titleAndYear(text: String): Pair<String, Int?> {
        val noBrackets = resolution.replace(brackets.replace(text, " "), " ")
        val years = yearRx.findAll(noBrackets).toList()
        val junkAt = junk.find(noBrackets)?.range?.first ?: noBrackets.length
        // The last year before any release tag, so "2001 A Space Odyssey 1968" keeps "2001".
        val year = years.lastOrNull { it.range.first in 1 until junkAt && clean(noBrackets.substring(0, it.range.first)).isNotEmpty() }
        val end = minOf(year?.range?.first ?: noBrackets.length, junkAt)
        val title = clean(noBrackets.substring(0, end))
        return if (title.isEmpty()) clean(noBrackets) to null else title to year?.value?.toInt()
    }

    private fun seasonFromFolders(folders: List<String>): Int? {
        val first = folders.firstOrNull()?.trim() ?: return null
        if (specialsFolder.matches(first)) return 0
        return seasonFolder.find(normalize(first).trim())?.groupValues?.get(1)?.toInt()
    }

    /** The show's name: the nearest folder that isn't a season folder. */
    private fun showFromFolders(folders: List<String>): String? = folders
        .firstOrNull { f -> val n = normalize(f).trim(); !seasonFolder.matches(n) && !specialsFolder.matches(n) }
        ?.let { titleAndYear(normalize(it)).first }
        ?.takeIf { it.isNotEmpty() }

    private fun looksLikeResolution(text: String, m: MatchResult): Boolean {
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return (a >= 10 && b >= 100) || text.substring(m.range.last + 1).startsWith("0")
    }

    /** Dots and underscores between words become spaces; dots inside numbers ("5.1") stay. */
    private fun normalize(s: String): String = buildString {
        for (i in s.indices) {
            val ch = s[i]
            val digitsAround = i > 0 && i < s.length - 1 && s[i - 1].isDigit() && s[i + 1].isDigit()
            append(if ((ch == '.' && !digitsAround) || ch == '_') ' ' else ch)
        }
    }

    private fun clean(s: String): String = brackets.replace(s, " ")
        .replace(Regex("""[()]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '–', ',', ':', '.', '_', '+', '~')
        .trim()

    /** Groups episodes of one show and finds the show again after renames: lower case, no punctuation, no articles. */
    fun key(title: String): String = title.lowercase()
        .replace('&', ' ')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .removePrefix("the ")
        .replace(" ", "")
}
