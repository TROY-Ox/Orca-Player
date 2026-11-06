package com.troy.orcaplayer.sources.stremio

import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.sources.api.Catalog
import com.troy.orcaplayer.sources.api.ContentSource

/**
 * What the app asks of any installed source. Stremio addons implement it directly; CloudStream
 * extensions (extended build) are described by a Stremio-style manifest so they share the
 * Addon Manager, Home rows, search, details and the stream picker (15.6, 19.4).
 */
interface AddonClient : ContentSource {
    fun toCatalog(c: CatalogDecl): Catalog

    fun supports(resource: String, type: TitleType, id: String): Boolean

    suspend fun subtitlesFor(
        type: TitleType,
        videoId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): List<SubtitleOption>

    /** Lists from a directory addon's addon_catalog resource (15.5); empty for other sources. */
    suspend fun addonCatalog(catalog: CatalogDecl): List<StremioParse.AddonListing> = emptyList()
}
