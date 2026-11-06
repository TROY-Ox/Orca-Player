package com.troy.orcaplayer.data

import android.content.Context
import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.core.model.Title
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.downloads.DownloadBridge
import com.troy.orcaplayer.downloads.Downloads
import com.troy.orcaplayer.downloads.EpisodeTarget
import com.troy.orcaplayer.downloads.FreshLink
import com.troy.orcaplayer.downloads.NewDownload
import com.troy.orcaplayer.downloads.logic.DownloadItem
import com.troy.orcaplayer.downloads.logic.DownloadMeta
import com.troy.orcaplayer.downloads.logic.EpisodeRef
import com.troy.orcaplayer.downloads.logic.SmartSeries
import com.troy.orcaplayer.downloads.logic.SubtitleFile
import com.troy.orcaplayer.downloads.logic.TrackChoice
import com.troy.orcaplayer.sources.api.StreamSort

/**
 * Turns titles, episodes and streams into downloads, and answers the downloader's questions:
 * fresh links for expired ones and the next episodes for smart downloads (16.1, 16.3, 16.6).
 */
class DownloadPlanner(private val context: Context, private val hub: ContentHub) : DownloadBridge {

    /** Extra headers from Settings, and the subtitle language to fetch automatically ("ar", "en" or null). */
    @Volatile var extraHeaders: Map<String, String> = emptyMap()
    @Volatile var subtitleLanguage: String? = null

    fun meta(title: Title, episode: Episode?, entry: StreamEntry?): DownloadMeta = DownloadMeta(
        title = title.name,
        type = title.type.name,
        titleId = title.id,
        videoId = episode?.id ?: title.id,
        mediaKeyId = MediaKeys.forSource(ContentHub.RESUME_SOURCE, title.id, episode?.id).id,
        sourceId = entry?.option?.source?.sourceId,
        sourceName = entry?.option?.source?.sourceName,
        year = title.year,
        poster = title.poster,
        backdrop = title.background,
        season = episode?.season,
        episode = episode?.number,
        episodeId = episode?.id,
        episodeTitle = episode?.name,
        streamName = entry?.option?.name,
        quality = entry?.info?.resolution?.let { "${it}p" },
        bingeGroup = entry?.option?.bingeGroup,
    )

    fun request(
        title: Title,
        episode: Episode?,
        entry: StreamEntry,
        profileId: String,
        subtitles: List<SubtitleOption>,
        tracks: TrackChoice? = null,
        startNow: Boolean = false,
    ): NewDownload = NewDownload(
        url = entry.option.url,
        meta = meta(title, episode, entry),
        mediaKey = MediaKeys.forSource(ContentHub.RESUME_SOURCE, title.id, episode?.id),
        profileId = profileId,
        headers = extraHeaders + entry.option.headers,
        subtitles = subtitles.map { SubtitleFile(it.url, it.language, it.label, it.headers) },
        tracks = tracks,
        startNow = startNow,
        filename = entry.option.filename,
        sizeHint = entry.info.sizeBytes ?: entry.option.videoSize,
    )

    /** A link from the URL player, the share sheet or the player itself. */
    fun request(item: PlayItem, profileId: String): NewDownload {
        val ep = item.episode
        return NewDownload(
            url = item.uri,
            meta = DownloadMeta(
                title = item.title,
                titleId = ep?.titleId,
                videoId = ep?.episodeId?.takeIf { it.isNotEmpty() } ?: ep?.titleId,
                type = if (ep != null) TitleType.SERIES.name else null,
                season = ep?.season,
                episode = ep?.number,
                episodeId = ep?.episodeId,
                episodeTitle = ep?.name,
                sourceId = item.source.sourceId,
                sourceName = item.source.sourceName,
            ),
            mediaKey = item.key,
            profileId = profileId,
            headers = item.headers,
            subtitles = item.subtitles.filter { it.url.startsWith("http") }.map { SubtitleFile(it.url, it.language, it.label, it.headers) },
        )
    }

    /** Streams that can be downloaded: plain links, not protected, allowed by their source. */
    private fun downloadable(list: List<StreamEntry>) = list.filter { it.playable && it.option.downloadable && !it.option.drmProtected }

    suspend fun streams(type: TitleType, videoId: String): List<StreamEntry> {
        var last = StreamsState()
        hub.streams(type, videoId).collect { last = it }
        return downloadable(last.entries)
    }

    /** The best stream at or under [height], from the same binge group when there is one (16.1). */
    suspend fun best(type: TitleType, videoId: String, height: Int?, bingeGroup: String?): StreamEntry? {
        val ranked = hub.rank(streams(type, videoId), StreamSort.QUALITY, height, null)
        val fitting = ranked.filter { height == null || (it.info.resolution ?: 0) <= height }.ifEmpty { ranked }
        return bingeGroup?.let { g -> fitting.firstOrNull { it.option.bingeGroup == g } } ?: fitting.firstOrNull()
    }

    /** The stream's own subtitles plus addon subtitles in the preferred language. */
    suspend fun subtitles(type: TitleType, videoId: String, entry: StreamEntry?): List<SubtitleOption> {
        val own = entry?.option?.subtitles.orEmpty()
        val lang = subtitleLanguage ?: return own
        val fromAddons = runCatching { hub.subtitles(type, videoId, entry?.option?.videoHash, entry?.option?.videoSize, entry?.option?.filename) }.getOrDefault(emptyList())
        val wanted = (own + fromAddons).filter { it.language?.let { l -> l.take(2).equals(lang, true) || l.startsWith(lang3(lang), true) } == true }
        return wanted.distinctBy { it.url }.take(2).ifEmpty { own }
    }

    private fun lang3(two: String) = when (two) {
        "ar" -> "ara"
        "en" -> "eng"
        else -> two
    }

    /**
     * Downloads the movie, or each episode, with one quality preference, keeping to the binge
     * group of the first episode found. Returns how many were queued.
     */
    suspend fun downloadBest(title: Title, episodes: List<Episode?>, height: Int?, profileId: String): Int {
        var group: String? = null
        var count = 0
        for (ep in episodes) {
            val videoId = ep?.id ?: title.id
            val entry = best(title.type, videoId, height, group) ?: continue
            if (group == null) group = entry.option.bingeGroup
            val subs = subtitles(title.type, videoId, entry)
            Downloads.enqueue(context, request(title, ep, entry, profileId, subs))
            count++
        }
        return count
    }

    // DownloadBridge

    override suspend fun freshLink(item: DownloadItem): FreshLink? {
        val m = item.meta
        val type = runCatching { TitleType.valueOf(m.type ?: "") }.getOrNull() ?: if (m.isEpisode) TitleType.SERIES else TitleType.MOVIE
        val videoId = m.videoId ?: return null
        val height = m.quality?.filter { it.isDigit() }?.toIntOrNull()
        val list = streams(type, videoId).filter { it.option.url != item.url }
        if (list.isEmpty()) return null
        val pick = m.bingeGroup?.let { g -> list.firstOrNull { it.option.bingeGroup == g } }
            ?: list.firstOrNull { it.option.name == m.streamName && it.option.source.sourceId == m.sourceId }
            ?: list.firstOrNull { height != null && it.info.resolution == height && it.option.source.sourceId == m.sourceId }
            ?: list.firstOrNull { height != null && it.info.resolution == height }
            ?: list.first()
        return FreshLink(pick.option.url, extraHeaders + pick.option.headers)
    }

    override suspend fun episodes(series: SmartSeries): List<EpisodeTarget> {
        val t = hub.details(TitleType.SERIES, series.titleId, null) ?: return emptyList()
        return t.episodes.map { EpisodeTarget(EpisodeRef(it.id, it.season, it.number), MediaKeys.forSource(ContentHub.RESUME_SOURCE, t.id, it.id).id) }
    }

    override suspend fun buildEpisode(series: SmartSeries, episode: EpisodeTarget): NewDownload? {
        val t = hub.details(TitleType.SERIES, series.titleId, null) ?: return null
        val ep = t.episodes.firstOrNull { it.id == episode.ref.id } ?: return null
        val entry = best(TitleType.SERIES, ep.id, series.height, series.bingeGroup) ?: return null
        return request(t, ep, entry, series.profileId, subtitles(TitleType.SERIES, ep.id, entry))
    }

    fun smartSeries(title: Title, height: Int?, profileId: String) = SmartSeries(
        seriesKey = title.id,
        title = title.name,
        titleId = title.id,
        profileId = profileId,
        height = height,
    )
}
