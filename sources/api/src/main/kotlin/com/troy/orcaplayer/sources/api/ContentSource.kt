package com.troy.orcaplayer.sources.api

import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.StreamOption
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import kotlinx.serialization.Serializable

/**
 * Implemented by Stremio addons, CloudStream extensions and the local library, so Home rows,
 * search, details, the stream picker and the downloader work with every source.
 *
 * Sources never talk to the player. They return data; the app turns it into a PlayRequest.
 */
interface ContentSource {
    val ref: SourceRef
    val capabilities: Set<SourceCapability>

    suspend fun catalogs(): List<Catalog>

    suspend fun catalogPage(catalog: Catalog, filters: Map<String, String> = emptyMap(), page: PageRequest = PageRequest()): Page<Title>

    suspend fun search(query: String, type: TitleType? = null): List<Title>

    suspend fun details(type: TitleType, titleId: String): Title?

    suspend fun streams(type: TitleType, videoId: String): List<StreamOption>

    suspend fun subtitles(type: TitleType, videoId: String): List<SubtitleOption> = emptyList()
}

enum class SourceCapability { CATALOGS, SEARCH, DETAILS, STREAMS, SUBTITLES }

@Serializable
data class Catalog(
    val sourceId: String,
    val id: String,
    val type: TitleType,
    val name: String,
    val filters: List<CatalogFilter> = emptyList(),
    val supportsSearch: Boolean = false,
)

@Serializable
data class CatalogFilter(val key: String, val label: String, val options: List<String> = emptyList(), val required: Boolean = false)

@Serializable
data class PageRequest(val skip: Int = 0, val limit: Int = 100)

@Serializable
data class Page<T>(val items: List<T>, val next: PageRequest?)

/** Thrown by a source when it can't answer; callers show an error for that source only. */
class SourceException(val source: SourceRef, message: String, cause: Throwable? = null) : Exception(message, cause)
