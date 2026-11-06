package com.troy.orcaplayer.core.settings

import java.text.Normalizer

/** One searchable row: the resolved, translated strings for a setting. */
data class SearchEntry(
    val key: String,
    val title: String,
    val summary: String = "",
    val section: String = "",
    val keywords: String = "",
)

/**
 * Settings search. Matches every word of the query against the title, summary, section and
 * keywords, ignoring case, Latin accents and Arabic diacritics. Title matches rank first.
 */
object SettingsSearch {
    private val arabicMarks = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val latinMarks = Regex("\\p{Mn}+")

    fun normalize(text: String): String {
        val noArabicMarks = arabicMarks.replace(text, "")
        val decomposed = Normalizer.normalize(noArabicMarks, Normalizer.Form.NFD)
        return latinMarks.replace(decomposed, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
            .lowercase()
            .trim()
    }

    fun search(query: String, entries: List<SearchEntry>): List<SearchEntry> {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries
            .mapNotNull { e ->
                val title = normalize(e.title)
                val rest = normalize("${e.summary} ${e.section} ${e.keywords}")
                var score = 0
                for (w in words) {
                    score += when {
                        title.startsWith(w) -> 4
                        title.contains(w) -> 3
                        rest.contains(w) -> 1
                        else -> return@mapNotNull null
                    }
                }
                e to score
            }
            .sortedWith(compareByDescending<Pair<SearchEntry, Int>> { it.second }.thenBy { it.first.title })
            .map { it.first }
    }
}
