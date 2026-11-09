package com.troy.orcaplayer.ui.title

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.troy.orcaplayer.R
import com.troy.orcaplayer.data.TorrentProgress

/** One line for a torrent being prepared: DHT nodes and peers while finding it, then buffering, peers and speed. */
@Composable
fun torrentStatus(tp: TorrentProgress): String {
    val context = LocalContext.current
    return if (!tp.hasMetadata) {
        stringResource(R.string.torrent_status_finding, tp.dhtNodes, tp.peers, tp.seeds)
    } else {
        stringResource(R.string.torrent_status_buffering, tp.percent, tp.peers, tp.seeds, Formatter.formatShortFileSize(context, tp.downloadRate.toLong()))
    }
}
