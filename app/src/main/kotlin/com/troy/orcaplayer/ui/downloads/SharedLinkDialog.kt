package com.troy.orcaplayer.ui.downloads

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.troy.orcaplayer.AppViewModel
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.ui.player.PlayerLaunch

/** A link shared to the app from another app: play it now or download it (16.1). */
@Composable
fun SharedLinkDialog(link: String, appVm: AppViewModel, onDone: () -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val c = Troy.colors
    val context = LocalContext.current
    val guard = rememberDownloadGuard(vm)
    var options by remember(link) { mutableStateOf(false) }
    val request = remember(link) { appVm.playLink(link) }
    if (request == null) {
        LaunchedEffect(link) { onDone() }
        return
    }
    val item = request.items.first()
    if (options) {
        DownloadOptionsDialog(
            vm = vm,
            name = item.title,
            url = item.uri,
            headers = item.headers,
            subtitles = { emptyList() },
            preferredLanguage = null,
            onDismiss = onDone,
            onConfirm = { choice ->
                vm.downloadItem(item, choice.kind, choice.tracks, choice.startNow)
                Toast.makeText(context, R.string.dl_queued, Toast.LENGTH_SHORT).show()
                onDone()
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.share_link_title), style = Troy.type.title, color = c.text) },
        text = { Text(link, style = Troy.type.bodySmall, color = c.text2, maxLines = 4) },
        confirmButton = {
            TextButton(onClick = {
                PlayerLaunch.play(context, request)
                onDone()
            }) { Text(stringResource(R.string.dl_play), color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = {
                guard(item.source, drm = false, downloadable = true) { options = true }
            }) { Text(stringResource(R.string.dl_download), color = c.accent) }
        },
    )
}
