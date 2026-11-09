package com.troy.orcaplayer.data

import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleMergeTest {
    private fun t(src: String, id: String, name: String, year: Int? = null, imdb: String? = null, type: TitleType = TitleType.MOVIE) =
        Title(id, SourceRef(SourceKind.STREMIO_ADDON, src, src), type, name, imdbId = imdb, year = year)

    @Test
    fun mergesByImdbAndByNormalisedName() {
        val groups = TitleMerge.group(
            listOf(
                listOf(t("a", "tt0133093", "The Matrix", 1999, "tt0133093")),
                listOf(t("b", "cs-1", "Matrix (1999) [1080p]", null)),
                listOf(t("c", "x9", "the matrix", 1999), t("c", "x10", "The Matrix Reloaded", 2003)),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b", "c"), groups[0].map { it.source.sourceId })
    }

    @Test
    fun keepsDifferentImdbIdsAndFarYearsApart() {
        val groups = TitleMerge.group(
            listOf(
                listOf(t("a", "tt1", "Dune", 1984, "tt1")),
                listOf(t("b", "tt2", "Dune", 2021, "tt2")),
                listOf(t("c", "z", "Dune", 2021)),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("b", "c"), groups[1].map { it.source.sourceId })
    }

    @Test
    fun moviesAndSeriesStayApart() {
        val groups = TitleMerge.group(listOf(listOf(t("a", "1", "Fargo", 1996)), listOf(t("b", "2", "Fargo", 1996, type = TitleType.SERIES))))
        assertEquals(2, groups.size)
    }

    @Test
    fun arabicNamesIgnoreHarakatAndAlefForms() {
        assertEquals(TitleMerge.normalizeName("الأَسَد"), TitleMerge.normalizeName("الاسد"))
    }
}
