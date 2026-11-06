package com.troy.orcaplayer.downloads.logic

import com.troy.orcaplayer.core.model.MediaKey
import kotlinx.serialization.Serializable

/** A plain file over HTTP, or an adaptive stream saved with Media3's download system (16.4). */
@Serializable
enum class DownloadKind { FILE, HLS, DASH }

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, WAITING, FAILED, COMPLETED }

/** Why a queued download is not running right now. */
@Serializable
enum class WaitReason { NETWORK, WIFI, CHARGING, SCHEDULE, STORAGE, RETRY }

@Serializable
enum class DownloadError {
    NETWORK,
    HTTP,
    /** The link expired and no fresh one could be found. */
    EXPIRED,
    NO_SPACE,
    STORAGE,
    NOT_ALLOWED,
    UNKNOWN,
}

/** Where a finished file goes (16.5). */
@Serializable
enum class DownloadPlace {
    /** Movies/ORCA Player/Downloads, visible to other apps. */
    PUBLIC,
    /** A folder the user picked with the system folder picker. */
    FOLDER,
    /** The app's own storage, not visible to other apps. */
    PRIVATE,
}

/** What a download is, so it shows properly offline and can ask its source for a fresh link. */
@Serializable
data class DownloadMeta(
    /** Movie or series name. */
    val title: String,
    val type: String? = null,
    val titleId: String? = null,
    /** The ID used to ask sources for streams: the movie ID, or the episode ID. */
    val videoId: String? = null,
    /** Shared with resume and history, so a downloaded episode resumes where the stream left off. */
    val mediaKeyId: String? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeId: String? = null,
    val episodeTitle: String? = null,
    val streamName: String? = null,
    val quality: String? = null,
    val bingeGroup: String? = null,
) {
    val isEpisode: Boolean get() = episodeId != null || episode != null
}

@Serializable
data class SubtitleFile(
    val url: String,
    val language: String? = null,
    val label: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Where the saved copy is, once downloaded. */
    val savedUri: String? = null,
)

/** One byte range of a multi-connection download. [end] is inclusive. */
@Serializable
data class Segment(val start: Long, val end: Long, val done: Long = 0) {
    val length: Long get() = end - start + 1
    val complete: Boolean get() = done >= length
}

/** The tracks picked for an HLS or DASH download, as "period.group.stream" keys. */
@Serializable
data class TrackChoice(
    val keys: List<String> = emptyList(),
    /** For example "1080p · English, Arabic". */
    val label: String? = null,
    val height: Int? = null,
)

@Serializable
data class DownloadItem(
    val id: String,
    val kind: DownloadKind,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val meta: DownloadMeta,
    val profileId: String = "default",
    val place: DownloadPlace = DownloadPlace.PUBLIC,
    /** The tree URI when [place] is [DownloadPlace.FOLDER]. */
    val folderUri: String? = null,
    val fileName: String,
    val mimeType: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val waitReason: WaitReason? = null,
    val error: DownloadError? = null,
    val errorDetail: String? = null,
    val bytesDone: Long = 0,
    /** -1 when the server didn't say. */
    val totalBytes: Long = -1,
    /** HLS and DASH: 0..100, or -1 when unknown. */
    val percent: Float = -1f,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Null until the first response says whether the server supports byte ranges. */
    val rangeSupported: Boolean? = null,
    val segments: List<Segment> = emptyList(),
    val attempts: Int = 0,
    val refreshes: Int = 0,
    val nextAttemptAt: Long = 0,
    /** Position in the queue; lower runs first. */
    val order: Long = 0,
    val createdAt: Long = 0,
    val completedAt: Long = 0,
    /** Started with "Start now", so it ignores the schedule and charging-only. */
    val startNow: Boolean = false,
    /** The file's content or file URI while downloading and when finished. */
    val outputUri: String? = null,
    val outputPath: String? = null,
    val subtitles: List<SubtitleFile> = emptyList(),
    val tracks: TrackChoice? = null,
    /** Downloaded by smart downloads for its series. */
    val smart: Boolean = false,
    /** Shown once: the server can't resume, so the download started again from zero. */
    val restartedNotice: Boolean = false,
    /** Set once an "Export as MP4" copy exists. */
    val exportedUri: String? = null,
    /** Resume, history and per-video settings key, shared with streaming the same title. */
    val mediaKey: MediaKey? = null,
) {
    val active: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.WAITING
    val isStream: Boolean get() = kind != DownloadKind.FILE

    /** 0..1, or null when the size is unknown. */
    val fraction: Float?
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            isStream -> percent.takeIf { it >= 0 }?.let { it / 100f }
            totalBytes > 0 -> (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            else -> null
        }

    /** The series this episode belongs to, for grouping and smart downloads. */
    val seriesKey: String? get() = if (meta.isEpisode) meta.titleId ?: meta.title else null
}

/** Downloader options, pushed from Settings (16.2, 16.3, 16.5). */
@Serializable
data class DownloadConfig(
    val parallel: Int = 2,
    val multiConnection: Boolean = false,
    /** Bytes per second for all downloads together, or 0 for no limit. */
    val speedLimit: Long = 0,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** Minutes after midnight, or null to start right away. */
    val startMinute: Int? = null,
    val maxRetries: Int = 5,
    /** Downloads pause when free space would drop below this. */
    val reserveBytes: Long = 500L * 1024 * 1024,
    val notifyFinished: Boolean = true,
    val place: DownloadPlace = DownloadPlace.PUBLIC,
    val folderUri: String? = null,
    val movieTemplate: String = Naming.MOVIE_TEMPLATE,
    val episodeTemplate: String = Naming.EPISODE_TEMPLATE,
    val saveSubtitles: Boolean = true,
    val smartDelete: SmartDelete = SmartDelete.ASK,
    val smartKeep: Int = 2,
)

@Serializable
enum class SmartDelete { OFF, ASK, AUTO }

/** Smart downloads switched on for one series (16.6). */
@Serializable
data class SmartSeries(
    val seriesKey: String,
    val title: String,
    val type: String = "series",
    val titleId: String,
    val profileId: String,
    val height: Int? = null,
    val bingeGroup: String? = null,
)
