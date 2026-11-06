package com.troy.orcaplayer.sources.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSortingTest {
    private fun media(name: String, folder: String = "f", size: Long = 1, date: Long = 1, audio: Boolean = false) = LocalMedia(
        id = name.hashCode().toLong(), uri = "content://x/$name", name = name, folderId = folder, folderName = folder,
        path = null, sizeBytes = size, durationMs = 0, width = 1920, height = 1080, dateAddedSec = date,
        mimeType = null, isAudio = audio,
    )

    @Test fun naturalOrder() {
        val sorted = LocalSorting.sort(listOf(media("Ep 10.mkv"), media("Ep 2.mkv"), media("ep 1.mkv")), LocalSort.NAME)
        assertEquals(listOf("ep 1.mkv", "Ep 2.mkv", "Ep 10.mkv"), sorted.map { it.name })
    }

    @Test fun filterAndSearch() {
        val all = listOf(media("a.mp4"), media("song.mp3", audio = true), media("b.mkv", folder = "Movies"))
        assertEquals(2, LocalSorting.filter(all, LocalFilter.VIDEO, "").size)
        assertEquals(1, LocalSorting.filter(all, LocalFilter.AUDIO, "").size)
        assertEquals(listOf("b.mkv"), LocalSorting.filter(all, LocalFilter.ALL, "movies").map { it.name })
    }

    @Test fun foldersGroup() {
        val folders = LocalSorting.folders(listOf(media("a", "x"), media("b", "x"), media("c", "y")), LocalSort.NAME)
        assertEquals(listOf("x" to 2, "y" to 1), folders.map { it.name to it.count })
    }

    @Test fun badges() {
        assertEquals("1080p", LocalSorting.resolutionBadge(1920, 1080))
        assertEquals("4K", LocalSorting.resolutionBadge(3840, 2160))
        assertEquals("720p", LocalSorting.resolutionBadge(1280, 720))
        assertEquals(null, LocalSorting.resolutionBadge(0, 0))
    }

    @Test fun sidecarNames() {
        val m = Sidecars.matches("Movie.2024.mkv", listOf("Movie.2024.srt", "Movie.2024.ar.srt", "Other.srt", "Movie.2024.nfo"))
        assertEquals(listOf("Movie.2024.srt" to null, "Movie.2024.ar.srt" to "ar"), m)
    }
}
