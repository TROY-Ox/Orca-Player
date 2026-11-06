package com.troy.orcaplayer.data

import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.model.EpisodeInfo
import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.StreamAlternate
import com.troy.orcaplayer.core.model.StreamKind
import com.troy.orcaplayer.core.model.StreamOption
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.model.UriSchemes
import com.troy.orcaplayer.sources.api.Catalog
import com.troy.orcaplayer.sources.api.Page
import com.troy.orcaplayer.sources.api.PageRequest
import com.troy.orcaplayer.sources.api.SourceCapability
import com.troy.orcaplayer.sources.api.StreamBadges
import com.troy.orcaplayer.sources.api.StreamInfo
import com.troy.orcaplayer.sources.api.StreamRanking
import com.troy.orcaplayer.sources.api.StreamSort
import com.troy.orcaplayer.sources.stremio.CatalogDecl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class HomeRow(
    val addonId: String,
    val addonName: String,
    val decl: CatalogDecl,
    val catalog: Catalog,
    val name: String,
    val style: RowStyle,
    val genre: String?,
) {
    val key: String get() = "$addonId|${decl.key}"
}

/** One title as shown in search, with every source that has it (14.4). */
data class MergedTitle(val title: Title, val sources: List<SourceRef>)

data class SearchResults(val items: List<MergedTitle> = emptyList(), val pending: Int = 0, val failed: List<String> = emptyList())

data class StreamEntry(val option: StreamOption, val info: StreamInfo, val sourceIndex: Int) {
    val playable: Boolean get() = option.kind == StreamKind.URL && UriSchemes.isAllowed(option.url)
}

data class StreamsState(val entries: List<StreamEntry> = emptyList(), val pending: Int = 0, val failed: List<String> = emptyList())

/** How the player can ask for a fresh link for a title it is playing (14.7 and resuming). */
data class StreamTarget(val type: TitleType, val videoId: String, val title: String, val episode: EpisodeInfo?)

/**
 * Everything the browsing screens ask of sources: Home rows, Discover pages, unified search,
 * merged details, streams from every source and subtitles. A slow or failing source never holds
 * up the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentHub(
    private val addons: AddonRepository,
    profiles: ProfileRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val profile: StateFlow<Profile?> = combine(profiles.profiles, profiles.currentProfileId) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun active() = addons.active(profile.value)

    private fun index(id: String): Int = active().indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it }

    // Home and Discover (14.3)

    fun homeRows(list: AddonList = addons.list.value, p: Profile? = profile.value): List<HomeRow> =
        list.addons.filter { it.activeFor(p) && it.useCatalogs }.flatMap { a ->
            val client = addons.client(a)
            a.effectiveCatalogs().filter { it.second.place == CatalogPlace.HOME }.map { (decl, pref) ->
                HomeRow(a.id, a.manifest.name, decl, client.toCatalog(decl), pref.name ?: decl.name, pref.rowStyle, pref.defaultGenre)
            }
        }

    /** Every catalog that can be opened as a grid: Home and Discover-only ones. */
    fun discoverCatalogs(list: AddonList = addons.list.value, p: Profile? = profile.value): List<HomeRow> =
        list.addons.filter { it.activeFor(p) && it.useCatalogs }.flatMap { a ->
            val client = addons.client(a)
            a.effectiveCatalogs().filter { it.second.place != CatalogPlace.HIDDEN }.map { (decl, pref) ->
                HomeRow(a.id, a.manifest.name, decl, client.toCatalog(decl), pref.name ?: decl.name, pref.rowStyle, pref.defaultGenre)
            }
        }

    suspend fun page(row: HomeRow, filters: Map<String, String>, page: PageRequest): Page<Title> = withContext(Dispatchers.IO) {
        val a = addons.addon(row.addonId) ?: throw IllegalStateException("Source removed")
        addons.client(a).catalogPage(row.catalog, filters, page)
    }

    // Search (14.4)

    fun search(query: String, type: TitleType? = null): Flow<SearchResults> = channelFlow {
        val targets = active().filter { it.inSearch && it.useCatalogs }.map { it to addons.client(it) }
            .filter { SourceCapability.SEARCH in it.second.capabilities }
        val lock = Mutex()
        val perSource = LinkedHashMap<String, List<Title>>()
        val failed = mutableListOf<String>()
        var pending = targets.size
        send(SearchResults(pending = pending))
        targets.forEach { (a, client) ->
            launch {
                val result = runCatching { client.search(query, type) }
                lock.withLock {
                    pending--
                    result.onSuccess { perSource[a.id] = it }.onFailure { failed += a.manifest.name }
                    send(SearchResults(merge(perSource), pending, failed.toList()))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Merges results by IMDb ID, or by name, year and type; the user's source order decides which wins. */
    fun merge(perSource: Map<String, List<Title>>): List<MergedTitle> {
        val out = LinkedHashMap<String, MutableList<Title>>()
        perSource.entries.sortedBy { index(it.key) }.forEach { (_, titles) ->
            titles.forEach { t -> out.getOrPut(mergeKey(t)) { mutableListOf() } += t }
        }
        return out.values.map { list -> MergedTitle(list.reduce(::fill), list.map { it.source }.distinct()) }
    }

    fun mergeKey(t: Title): String = t.imdbId?.let { "imdb:$it" }
        ?: "${t.type}|${t.name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }}|${t.year ?: ""}"

    /** Fills what [a] lacks from [b]. */
    private fun fill(a: Title, b: Title): Title = a.copy(
        poster = a.poster ?: b.poster,
        background = a.background ?: b.background,
        logo = a.logo ?: b.logo,
        description = a.description ?: b.description,
        releaseInfo = a.releaseInfo ?: b.releaseInfo,
        genres = a.genres.ifEmpty { b.genres },
        rating = a.rating ?: b.rating,
        runtime = a.runtime ?: b.runtime,
        episodes = a.episodes.ifEmpty { b.episodes },
        cast = a.cast.ifEmpty { b.cast },
        directors = a.directors.ifEmpty { b.directors },
        trailers = a.trailers.ifEmpty { b.trailers },
        imdbId = a.imdbId ?: b.imdbId,
        year = a.year ?: b.year,
        language = a.language ?: b.language,
        country = a.country ?: b.country,
    )

    // Details (14.5)

    /**
     * Details from every source that describes this ID, merged in the user's source order, with
     * [fallback] (the card the user tapped) filling anything none of them have.
     */
    suspend fun details(type: TitleType, id: String, fallback: Title?): Title? = coroutineScope {
        val targets = active().filter { it.useDetails }.map { addons.client(it) }.filter { it.supports("meta", type, id) }
        val results = targets.map { c -> async(Dispatchers.IO) { runCatching { c.details(type, id) }.getOrNull() } }.awaitAll().filterNotNull()
        (results + listOfNotNull(fallback)).reduceOrNull(::fill)
    }

    // Streams (14.6)

    fun streams(type: TitleType, videoId: String): Flow<StreamsState> = channelFlow {
        val targets = active().filter { it.useStreams }.map { it to addons.client(it) }.filter { it.second.supports("stream", type, videoId) }
        val lock = Mutex()
        val all = mutableListOf<StreamEntry>()
        val failed = mutableListOf<String>()
        var pending = targets.size
        send(StreamsState(pending = pending))
        targets.forEach { (a, client) ->
            launch {
                val result = runCatching { client.streams(type, videoId) }
                lock.withLock {
                    pending--
                    result.onSuccess { list ->
                        val i = index(a.id)
                        list.forEach { s ->
                            val info = StreamBadges.parse(s.name, s.description, s.filename, url = s.url.takeIf { s.kind == StreamKind.URL }, sizeHint = s.videoSize, subtitles = s.subtitles.isNotEmpty())
                            all += StreamEntry(s.copy(downloadable = s.downloadable && a.allowDownloads), info, i)
                        }
                    }.onFailure { failed += a.manifest.name }
                    send(StreamsState(all.toList(), pending, failed.toList()))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun rank(entries: List<StreamEntry>, sort: StreamSort, quality: Int?, language: String?): List<StreamEntry> =
        StreamRanking.sort(entries, { it.info }, { it.sourceIndex }, sort, quality, language)

    // Subtitles (9.6)

    suspend fun subtitles(type: TitleType, videoId: String, hash: String?, size: Long?, filename: String?): List<SubtitleOption> = coroutineScope {
        active().filter { it.useSubtitles }.map { addons.client(it) }.filter { it.supports("subtitles", type, videoId) }
            .map { c -> async(Dispatchers.IO) { runCatching { c.subtitlesFor(type, videoId, hash, size, filename) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten().distinctBy { it.url }
    }

    // Playing

    private val targets = ConcurrentHashMap<String, StreamTarget>()

    companion object {
        /** Resume positions for addon titles are kept by title and episode, not by addon, so any source's stream resumes. */
        const val RESUME_SOURCE = "stremio"
    }

    fun targetFor(mediaKeyId: String): StreamTarget? = targets[mediaKeyId]

    /**
     * Builds the PlayRequest for [chosen], with the other playable streams as fallbacks, best
     * first and same binge group first (14.2, 14.7).
     */
    fun request(
        title: Title,
        episode: Episode?,
        chosen: StreamEntry,
        ranked: List<StreamEntry>,
        profileId: String,
        extraHeaders: Map<String, String>,
        maxAlternates: Int,
    ): PlayRequest {
        val videoId = episode?.id ?: title.id
        val key = MediaKeys.forSource(RESUME_SOURCE, title.id, episode?.id)
        val epInfo = episode?.let { EpisodeInfo(title.id, it.id, it.season, it.number, it.name) }
        targets[key.id] = StreamTarget(title.type, videoId, title.name, epInfo)
        val others = ranked.filter { it !== chosen && it.playable }
            .sortedBy { if (chosen.option.bingeGroup != null && it.option.bingeGroup == chosen.option.bingeGroup) 0 else 1 }
            .take(maxAlternates)
        val s = chosen.option
        val item = PlayItem(
            uri = s.url,
            title = title.name,
            key = key,
            source = s.source,
            headers = extraHeaders + s.headers,
            subtitle = episode?.let { e -> listOfNotNull(e.season?.let { "S$it" }, e.number?.let { "E$it" }).joinToString("") + (e.name?.let { " · $it" } ?: "") },
            episode = epInfo,
            subtitles = s.subtitles,
            canSave = s.downloadable,
            drmProtected = s.drmProtected,
            downloadable = s.downloadable,
            alternates = others.map { StreamAlternate(it.option.url, it.option.name, extraHeaders + it.option.headers, it.option.subtitles) },
        )
        return PlayRequest(listOf(item), 0, profileId)
    }

    /** Fresh streams for something already playing, skipping the stream cache's short life. */
    suspend fun freshAlternates(target: StreamTarget, exclude: Set<String>, extraHeaders: Map<String, String>): List<StreamAlternate> {
        var last = StreamsState()
        streams(target.type, target.videoId).collect { last = it }
        return rank(last.entries.filter { it.playable && it.option.url !in exclude }, StreamSort.QUALITY, null, null)
            .map { StreamAlternate(it.option.url, it.option.name, extraHeaders + it.option.headers, it.option.subtitles) }
    }
}
