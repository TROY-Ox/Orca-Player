package com.troy.orcaplayer.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class SavedLink(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val name: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitleUrl: String? = null,
    val folderId: String? = null,
    val logo: String? = null,
)

@Serializable
data class LinkFolder(val id: String = UUID.randomUUID().toString(), val name: String)

@Serializable
data class HistoryEntry(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitleUrl: String? = null,
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class LinksData(
    val saved: List<SavedLink> = emptyList(),
    val folders: List<LinkFolder> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    /** Recent searches from the Search tab (14.4). */
    val searches: List<String> = emptyList(),
)

/** Saved links, their folders and recent links for the URL player (13). Separate from My List. */
class LinksRepository(context: Context) {
    private val store = JsonFile(File(context.applicationContext.filesDir, "sources/links.json"), LinksData.serializer(), LinksData())
    val data: StateFlow<LinksData> = store.state

    fun addHistory(entry: HistoryEntry, limit: Int) = store.update { d ->
        if (limit <= 0) return@update d.copy(history = emptyList())
        d.copy(history = (listOf(entry) + d.history.filterNot { it.url == entry.url }).take(limit))
    }

    fun addSearch(q: String) = store.update { d -> d.copy(searches = (listOf(q) + d.searches.filterNot { it.equals(q, true) }).take(20)) }
    fun clearSearches() = store.update { it.copy(searches = emptyList()) }

    fun removeHistory(url: String) = store.update { d -> d.copy(history = d.history.filterNot { it.url == url }) }
    fun clearHistory() = store.update { it.copy(history = emptyList()) }

    fun save(link: SavedLink) = store.update { d ->
        val i = d.saved.indexOfFirst { it.id == link.id }
        d.copy(saved = if (i >= 0) d.saved.toMutableList().also { it[i] = link } else d.saved + link)
    }

    fun saveAll(links: List<SavedLink>) = store.update { d ->
        val have = d.saved.map { it.url to it.folderId }.toSet()
        d.copy(saved = d.saved + links.filter { (it.url to it.folderId) !in have })
    }

    fun delete(id: String) = store.update { d -> d.copy(saved = d.saved.filterNot { it.id == id }) }

    /** Moves a link within its folder (or among the links outside folders). */
    fun move(id: String, delta: Int) = store.update { d ->
        val link = d.saved.firstOrNull { it.id == id } ?: return@update d
        val peers = d.saved.filter { it.folderId == link.folderId }
        val i = peers.indexOf(link)
        val j = i + delta
        if (j !in peers.indices) return@update d
        val target = peers[j]
        val all = d.saved.toMutableList()
        val a = all.indexOf(link)
        val b = all.indexOf(target)
        all[a] = target
        all[b] = link
        d.copy(saved = all)
    }

    fun addFolder(name: String): LinkFolder {
        val f = LinkFolder(name = name)
        store.update { it.copy(folders = it.folders + f) }
        return f
    }

    fun renameFolder(id: String, name: String) = store.update { d -> d.copy(folders = d.folders.map { if (it.id == id) it.copy(name = name) else it }) }

    /** Deletes a folder; its links move out of it rather than being deleted. */
    fun deleteFolder(id: String) = store.update { d ->
        d.copy(folders = d.folders.filterNot { it.id == id }, saved = d.saved.map { if (it.folderId == id) it.copy(folderId = null) else it })
    }

    fun moveFolder(id: String, delta: Int) = store.update { d ->
        val i = d.folders.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in d.folders.indices) d else d.copy(folders = d.folders.toMutableList().also { it.add(j, it.removeAt(i)) })
    }
}
