package com.troy.orcaplayer.sources.local

/** One video or audio file found on the device. */
data class LocalMedia(
    /** MediaStore ID, or a hash of the document URI for folders added through the file picker. */
    val id: Long,
    /** content:// URI that the player opens. */
    val uri: String,
    val name: String,
    val folderId: String,
    val folderName: String,
    /** File path when MediaStore reports one (used to find subtitles next to the video). */
    val path: String?,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** Seconds since 1970. */
    val dateAddedSec: Long,
    val mimeType: String?,
    val isAudio: Boolean,
) {
    /** "4K", "1080p", "720p" and so on, or null when unknown. */
    val resolutionBadge: String? get() = LocalSorting.resolutionBadge(width, height)

    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

data class LocalFolder(
    val id: String,
    val name: String,
    val count: Int,
    val totalBytes: Long,
    val latestDateSec: Long,
    /** A video in the folder, used for the folder thumbnail. */
    val cover: LocalMedia?,
)

enum class LocalSort(val key: String) {
    NAME("name"), DATE("date"), SIZE("size"), LENGTH("length");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NAME
    }
}

enum class LocalFilter(val key: String) {
    VIDEO("video"), AUDIO("audio"), ALL("all");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: VIDEO
    }
}

/** Pure sorting, filtering and grouping, kept free of Android types so it can be unit tested. */
object LocalSorting {
    fun resolutionBadge(width: Int, height: Int): String? {
        val short = minOf(width, height)
        val long = maxOf(width, height)
        if (short <= 0) return null
        return when {
            short >= 2000 || long >= 3800 -> "4K"
            short >= 1400 || long >= 2500 -> "1440p"
            short >= 1000 || long >= 1900 -> "1080p"
            short >= 700 || long >= 1260 -> "720p"
            short >= 460 -> "480p"
            else -> "${short}p"
        }
    }

    /** Natural order, so "Episode 2" comes before "Episode 10". */
    val naturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun filter(items: List<LocalMedia>, filter: LocalFilter, query: String): List<LocalMedia> {
        val q = query.trim()
        return items.filter { m ->
            val typeOk = when (filter) {
                LocalFilter.VIDEO -> !m.isAudio
                LocalFilter.AUDIO -> m.isAudio
                LocalFilter.ALL -> true
            }
            typeOk && (q.isEmpty() || m.name.contains(q, ignoreCase = true) || m.folderName.contains(q, ignoreCase = true))
        }
    }

    fun sort(items: List<LocalMedia>, sort: LocalSort, descending: Boolean = false): List<LocalMedia> {
        val c: Comparator<LocalMedia> = when (sort) {
            LocalSort.NAME -> compareBy(naturalOrder) { it.name }
            LocalSort.DATE -> compareByDescending<LocalMedia> { it.dateAddedSec }.thenBy(naturalOrder) { it.name }
            LocalSort.SIZE -> compareByDescending<LocalMedia> { it.sizeBytes }.thenBy(naturalOrder) { it.name }
            LocalSort.LENGTH -> compareByDescending<LocalMedia> { it.durationMs }.thenBy(naturalOrder) { it.name }
        }
        val sorted = items.sortedWith(c)
        return if (descending) sorted.reversed() else sorted
    }

    fun folders(items: List<LocalMedia>, sort: LocalSort): List<LocalFolder> {
        val list = items.groupBy { it.folderId }.map { (id, files) ->
            LocalFolder(
                id = id,
                name = files.first().folderName,
                count = files.size,
                totalBytes = files.sumOf { it.sizeBytes },
                latestDateSec = files.maxOf { it.dateAddedSec },
                cover = files.firstOrNull { !it.isAudio } ?: files.first(),
            )
        }
        return when (sort) {
            LocalSort.NAME, LocalSort.LENGTH -> list.sortedWith(compareBy(naturalOrder) { it.name })
            LocalSort.DATE -> list.sortedByDescending { it.latestDateSec }
            LocalSort.SIZE -> list.sortedByDescending { it.totalBytes }
        }
    }
}
