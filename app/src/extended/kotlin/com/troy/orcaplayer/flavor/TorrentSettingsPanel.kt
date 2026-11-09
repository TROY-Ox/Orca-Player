package com.troy.orcaplayer.flavor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes

/** Torrent streaming settings (extended build): cache size and whether sharing stops with playback. */
@Composable
fun TorrentSettingsPanel() {
    val c = Troy.colors
    val s = Flavor.torrents.settings
    var cache by remember { mutableStateOf(s.cacheGb) }
    var stopSeeding by remember { mutableStateOf(s.stopSeeding) }
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.torrent_settings_title))
        Text(stringResource(R.string.torrent_settings_summary), style = Troy.type.bodySmall, color = c.text2)
        Text(stringResource(R.string.torrent_cache_size), style = Troy.type.body, color = c.text)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TorrentSettings.CACHE_CHOICES) { gb ->
                TroyChip(stringResource(R.string.torrent_cache_gb, gb), cache == gb, {
                    cache = gb
                    s.cacheGb = gb
                })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.torrent_stop_seeding), style = Troy.type.body, color = c.text)
                Text(stringResource(R.string.torrent_stop_seeding_summary), style = Troy.type.bodySmall, color = c.text2)
            }
            TroyToggle(stopSeeding, onCheckedChange = {
                stopSeeding = it
                s.stopSeeding = it
            })
        }
    }
}
