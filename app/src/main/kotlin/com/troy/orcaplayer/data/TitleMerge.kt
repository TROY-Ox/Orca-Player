package com.troy.orcaplayer.data

import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Groups the same film or show coming from several sources under one card.
 *
 * Two results are the same title when they share an IMDb ID, or when their names match after
 * normalising (case, accents, punctuation, a leading article, a year or quality tag in brackets)
 * and their years are equal, one apart, or unknown on either side. Results whose IMDb IDs differ
 * are never merged, whatever their names.
 */
object TitleMerge {
    private val bracketed = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val trailingYear = Regex("""\s+(19|20)\d{2}$""")
    private val qualityTags = Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr|web-?dl|webrip|bluray|brrip|hdrip|dvdrip|cam|ts|hdcam|x264|x265|hevc)\b""")
    private val article = Regex("""^(the|a|an|le|la|les|el|los|las|der|die|das)\s+""")
    private val marks = Regex("""\p{Mn}+""")
    // Arabic: drop tatweel and harakat, unify alef and teh marbuta forms.
    private val arabicHarakat = Regex("""[ً-ٰٟـ]""")

    fun normalizeName(name: String): String {
        var s = name.trim().lowercase(Locale.ROOT)
        s = bracketed.replace(s, " ")
        s = qualityTags.replace(s, " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = trailingYear.replace(s, "")
        s = article.replace(s, "")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).let { marks.replace(it, "") }
        s = arabicHarakat.replace(s, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
        s = s.replace("&", "and")
        return s.filter { it.isLetterOrDigit() }
    }

    /** Movies with movies, series with series; live channels and "tv" are one family. */
    fun family(type: TitleType): String = when (type) {
        TitleType.TV, TitleType.CHANNEL -> "live"
        else -> type.name
    }

    private fun yearsMatch(a: Int?, b: Int?): Boolean = a == null || b == null || abs(a - b) <= 1

    private class Group(first: Title) {
        val members = mutableListOf(first)
        var imdb: String? = first.imdbId
        var year: Int? = first.year
        val family = family(first.type)
        val name = normalizeName(first.name)

        fun accepts(t: Title): Boolean {
            if (imdb != null && imdb == t.imdbId) return true
            if (imdb != null && t.imdbId != null) return false
            return family == family(t.type) && name.isNotEmpty() && name == normalizeName(t.name) && yearsMatch(year, t.year)
        }

        fun add(t: Title) {
            members += t
            if (imdb == null) imdb = t.imdbId
            if (year == null) year = t.year
        }
    }

    /**
     * [perSource] is each source's result list, in the user's source order (first wins for the
     * card's own data). Returns the groups in first-seen order; each group lists its members in
     * source order.
     */
    fun group(perSource: List<List<Title>>): List<List<Title>> {
        val groups = mutableListOf<Group>()
        val byName = HashMap<String, MutableList<Group>>()
        val byImdb = HashMap<String, Group>()
        perSource.forEach { titles ->
            titles.forEach { t ->
                val norm = normalizeName(t.name)
                val g = t.imdbId?.let(byImdb::get)
                    ?: byName[norm]?.firstOrNull { it.accepts(t) }
                if (g != null) {
                    // The same source listing one title twice keeps a single entry.
                    if (g.members.none { it.source.sourceId == t.source.sourceId && it.id == t.id }) g.add(t)
                    g.imdb?.let { byImdb.putIfAbsent(it, g) }
                } else {
                    val n = Group(t)
                    groups += n
                    byName.getOrPut(norm) { mutableListOf() } += n
                    t.imdbId?.let { byImdb.putIfAbsent(it, n) }
                }
            }
        }
        return groups.map { it.members.toList() }
    }
}
