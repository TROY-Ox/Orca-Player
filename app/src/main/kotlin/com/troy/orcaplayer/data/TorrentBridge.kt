package com.troy.orcaplayer.data

import com.troy.orcaplayer.core.model.StreamOption
import kotlinx.coroutines.flow.StateFlow

/** What the torrent streamer is doing, for the "Connecting to peers" line. */
data class TorrentProgress(
    /** False while the torrent's file list (metadata) is still being fetched. */
    val hasMetadata: Boolean = false,
    val peers: Int = 0,
    val seeds: Int = 0,
    /** Nodes in the DHT routing table; 0 means the DHT can't be reached (often a network that blocks UDP). */
    val dhtNodes: Int = 0,
    /** How much of the first part of the video is ready, 0..100. */
    val percent: Int = 0,
    /** Bytes per second. */
    val downloadRate: Int = 0,
)

/**
 * Plays torrent streams by downloading them and serving the video file on a local address (extended
 * build only). The standard build sets no resolver, so torrent streams stay hidden there.
 */
interface TorrentResolver {
    val progress: StateFlow<TorrentProgress?>

    /** Fetches the torrent, starts downloading the video file and returns an http://127.0.0.1 URL that plays it. */
    suspend fun resolve(option: StreamOption): String

    /** Stops the torrent being prepared or played. */
    fun stop()

    /** True until the user has accepted the one-time torrent notice. */
    fun needsNotice(): Boolean

    fun acceptNotice()
}

object TorrentBridge {
    @Volatile var resolver: TorrentResolver? = null
}
