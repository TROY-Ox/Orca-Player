package com.troy.orcaplayer.sources.cloudstream.host

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.troy.orcaplayer.sources.cloudstream.DetailsDto
import com.troy.orcaplayer.sources.cloudstream.EpisodeDto
import com.troy.orcaplayer.sources.cloudstream.ExtensionFile
import com.troy.orcaplayer.sources.cloudstream.ItemDto
import com.troy.orcaplayer.sources.cloudstream.LinkDto
import com.troy.orcaplayer.sources.cloudstream.LinksDto
import com.troy.orcaplayer.sources.cloudstream.LoadResult
import com.troy.orcaplayer.sources.cloudstream.MainPageDto
import com.troy.orcaplayer.sources.cloudstream.ProviderInfo
import com.troy.orcaplayer.sources.cloudstream.RepoFormat
import com.troy.orcaplayer.sources.cloudstream.SearchDto
import com.troy.orcaplayer.sources.cloudstream.SectionDto
import com.troy.orcaplayer.sources.cloudstream.SectionInfo
import com.troy.orcaplayer.sources.cloudstream.SubtitleDto
import dalvik.system.PathClassLoader
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/**
 * Runs inside the ":extensions" process only. Loads .cs3 files with a class loader from
 * app-private, read-only files and turns the extension API's objects into plain data.
 */
class PluginRuntime(private val context: Context) {
    private val providers = LinkedHashMap<String, MainAPI>()
    private val loaded = HashMap<String, String>() // internalName -> path

    @Synchronized
    fun load(files: List<ExtensionFile>): LoadResult {
        val failures = LinkedHashMap<String, String>()
        val infos = mutableListOf<ProviderInfo>()
        for (f in files) {
            // Each file is loaded once per process; updates restart the process.
            if (loaded[f.internalName] == f.path) {
                infos += providers.filterKeys { it.startsWith(f.internalName + ".") }.map { (k, api) -> info(k, f.internalName, api) }
                continue
            }
            val result = runCatching { loadOne(f) }
            result.onSuccess { infos += it }.onFailure { e -> failures[f.internalName] = describe(e) }
        }
        return LoadResult(infos, failures)
    }

    private fun loadOne(f: ExtensionFile): List<ProviderInfo> {
        val file = File(f.path)
        require(file.isFile) { "File missing" }
        // Android 14 refuses to load code from writable files.
        if (file.canWrite()) file.setReadOnly()
        val manifest = ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("No manifest.json in the extension")
            zip.getInputStream(entry).bufferedReader().readText()
        }
        val obj = RepoFormat.json.parseToJsonElement(manifest).jsonObject
        val className = obj["pluginClassName"]?.jsonPrimitive?.content ?: error("No pluginClassName in the manifest")
        val loader = PathClassLoader(file.absolutePath, javaClass.classLoader)
        val instance = loader.loadClass(className).getDeclaredConstructor().newInstance()
        val before = APIHolder.allProviders.toList().toSet()
        // Older extensions extend the Android Plugin; newer ones, built against the cross-platform
        // library, extend BasePlugin directly and have no load(Context).
        when (instance) {
            is Plugin -> {
                instance.filename = file.absolutePath
                instance.load(context)
            }
            is com.lagradost.cloudstream3.plugins.BasePlugin -> {
                instance.filename = file.absolutePath
                instance.load()
            }
            else -> error("$className is not a CloudStream plugin")
        }
        val added = APIHolder.allProviders.toList().filter { it !in before }
        loaded[f.internalName] = f.path
        return added.map { api ->
            val key = RepoFormat.providerKey(f.internalName, api.name)
            providers[key] = api
            info(key, f.internalName, api)
        }
    }

    private fun info(key: String, internalName: String, api: MainAPI) = ProviderInfo(
        key = key,
        internalName = internalName,
        name = api.name,
        language = api.lang,
        types = api.supportedTypes.map { it.name },
        hasMainPage = api.hasMainPage,
        sections = if (api.hasMainPage) api.mainPage.map { SectionInfo(it.name, it.data, it.horizontalImages) } else emptyList(),
        mainUrl = api.mainUrl,
    )

    private fun api(key: String): MainAPI = providers[key] ?: error("Extension not loaded")

    suspend fun mainPage(key: String, section: Int, page: Int): MainPageDto {
        val api = api(key)
        val s = api.mainPage.getOrNull(section) ?: error("No such section")
        val r = api.getMainPage(page, MainPageRequest(s.name, s.data, s.horizontalImages)) ?: return MainPageDto()
        return MainPageDto(r.items.map { l -> SectionDto(l.name, l.list.map(::item)) }, r.hasNext)
    }

    suspend fun search(key: String, query: String): SearchDto =
        SearchDto(api(key).search(query).orEmpty().map(::item))

    suspend fun details(key: String, url: String): DetailsDto {
        val r = api(key).load(url) ?: error("Nothing found")
        return details(r)
    }

    suspend fun links(key: String, data: String, isCasting: Boolean): LinksDto {
        val links = java.util.Collections.synchronizedList(mutableListOf<LinkDto>())
        val subs = java.util.Collections.synchronizedList(mutableListOf<SubtitleDto>())
        api(key).loadLinks(
            data, isCasting,
            { s -> subs += SubtitleDto(s.url, s.lang) },
            { l ->
                links += LinkDto(
                    url = l.url, name = l.name, source = l.source, referer = l.referer.takeIf { it.isNotBlank() },
                    quality = l.quality.takeIf { it in 1..4320 }, type = l.type.name, headers = l.headers,
                )
            },
        )
        return LinksDto(links.toList(), subs.distinctBy { it.url })
    }

    private fun item(s: SearchResponse) = ItemDto(url = s.url, name = s.name, type = s.type?.name, poster = s.posterUrl)

    private fun details(r: LoadResponse): DetailsDto {
        val base = DetailsDto(
            url = r.url, name = r.name, type = r.type.name, poster = r.posterUrl, background = r.backgroundPosterUrl,
            plot = r.plot, year = r.year, tags = r.tags.orEmpty(), durationMin = r.duration, ids = r.syncData.toMap(),
        )
        return when (r) {
            is MovieLoadResponse -> base.copy(dataUrl = r.dataUrl)
            is LiveStreamLoadResponse -> base.copy(dataUrl = r.dataUrl)
            is TvSeriesLoadResponse -> base.copy(episodes = r.episodes.map { episode(it, false) })
            is AnimeLoadResponse -> {
                // Subbed first, then dubbed; both lists are kept, dubbed ones marked.
                val order = listOf(DubStatus.Subbed, DubStatus.None, DubStatus.Dubbed)
                val eps = order.flatMap { st -> r.episodes[st].orEmpty().map { episode(it, st == DubStatus.Dubbed) } }
                base.copy(episodes = eps)
            }
            is TorrentLoadResponse -> base.copy(torrent = true)
            else -> base
        }
    }

    private fun episode(e: com.lagradost.cloudstream3.Episode, dubbed: Boolean) = EpisodeDto(
        data = e.data, name = e.name, season = e.season, episode = e.episode, poster = e.posterUrl, description = e.description, dubbed = dubbed,
    )

    companion object {
        fun describe(e: Throwable): String = when (e) {
            is LinkageError -> "Incompatible with this app (${e.javaClass.simpleName}: ${e.message?.take(160)})"
            else -> (e.message ?: e.javaClass.simpleName).take(240)
        }
    }
}
