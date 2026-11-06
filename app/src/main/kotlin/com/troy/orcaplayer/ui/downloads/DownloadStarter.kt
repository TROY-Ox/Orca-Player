package com.troy.orcaplayer.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.Episode
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.SubtitleOption
import com.troy.orcaplayer.downloads.StreamTracks
import com.troy.orcaplayer.downloads.TrackOption
import com.troy.orcaplayer.downloads.logic.DownloadKind
import com.troy.orcaplayer.downloads.logic.TrackChoice

/**
 * Applies the saving rule and the kids rule before a download starts, with the one-time reminder
 * (3.7, 16.8), and asks for notification permission once on Android 13 and newer.
 */
class DownloadGuard internal constructor(private val run: (SourceRef, Boolean, Boolean, () -> Unit) -> Unit) {
    operator fun invoke(source: SourceRef, drm: Boolean, downloadable: Boolean, start: () -> Unit) = run(source, drm, downloadable, start)
}

@Composable
fun rememberDownloadGuard(vm: DownloadsViewModel): DownloadGuard {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    pending?.let { action ->
        val c = Troy.colors
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.save_reminder_title), style = Troy.type.title, color = c.text) },
            text = { Text(stringResource(R.string.save_reminder_body), style = Troy.type.body, color = c.text2) },
            confirmButton = {
                TextButton(onClick = {
                    vm.acceptReminder()
                    pending = null
                    askNotifications()
                    action()
                }) { Text(stringResource(R.string.save_reminder_accept), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.save_reminder_cancel), color = c.text2) } },
            containerColor = Troy.glass.panel.copy(alpha = 1f),
        )
    }
    return remember(vm) {
        DownloadGuard { source, drm, downloadable, start ->
            when (vm.check(source, drm, downloadable)) {
                DownloadCheck.OK -> {
                    askNotifications()
                    start()
                }
                DownloadCheck.REMINDER -> pending = start
                DownloadCheck.DRM -> Toast.makeText(context, R.string.save_denied_drm, Toast.LENGTH_LONG).show()
                DownloadCheck.SOURCE -> Toast.makeText(context, R.string.save_denied_source, Toast.LENGTH_LONG).show()
                DownloadCheck.SETTINGS -> Toast.makeText(context, R.string.save_denied_settings, Toast.LENGTH_LONG).show()
                DownloadCheck.KIDS -> Toast.makeText(context, R.string.dl_kids_blocked, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** What the options dialog hands back. */
data class DownloadChoice(val kind: DownloadKind?, val tracks: TrackChoice?, val subtitles: List<SubtitleOption>, val startNow: Boolean)

/**
 * Options before one download (16.1, 16.4, 16.5): the quality, audio and subtitle tracks of an
 * HLS or DASH stream, which subtitles to save next to a file, and "Start now".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadOptionsDialog(
    vm: DownloadsViewModel,
    name: String,
    url: String,
    headers: Map<String, String>,
    subtitles: suspend () -> List<SubtitleOption>,
    preferredLanguage: String?,
    onDismiss: () -> Unit,
    onConfirm: (DownloadChoice) -> Unit,
) {
    val c = Troy.colors
    var loading by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf<DownloadKind?>(null) }
    var tracks by remember { mutableStateOf<StreamTracks?>(null) }
    var video by remember { mutableStateOf<TrackOption?>(null) }
    val audios = remember { mutableStateListOf<TrackOption>() }
    val texts = remember { mutableStateListOf<TrackOption>() }
    val subs = remember { mutableStateListOf<SubtitleOption>() }
    val chosenSubs = remember { mutableStateListOf<String>() }
    var startNow by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        val k = vm.kindOf(url) ?: vm.detectKind(url, headers)
        kind = k
        if (k != DownloadKind.FILE) {
            val t = vm.tracks(url, k, headers)
            tracks = t
            if (t != null) {
                val wanted = vm.settings.value[com.troy.orcaplayer.core.settings.SourceSettings.PreferredQuality].toIntOrNull()
                video = t.videos.firstOrNull { wanted == null || (it.height ?: 0) <= wanted } ?: t.videos.firstOrNull()
                audios.addAll(t.audios)
                texts.addAll(t.texts.filter { preferredLanguage == null || it.language?.startsWith(preferredLanguage, true) == true })
            }
        } else {
            val list = runCatching { subtitles() }.getOrDefault(emptyList())
            subs.addAll(list)
            chosenSubs.addAll(list.filter { preferredLanguage != null && it.language?.startsWith(preferredLanguage, true) == true }.take(1).map { it.url })
        }
        loading = false
    }

    val blocked = tracks?.drmProtected == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dl_options_title), style = Troy.type.title, color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = Troy.type.body, color = c.text2)
                when {
                    loading -> Text(stringResource(R.string.dl_options_loading), style = Troy.type.bodySmall, color = c.text3)
                    blocked -> Text(stringResource(R.string.save_denied_drm), style = Troy.type.bodySmall, color = c.danger)
                    else -> {
                        val t = tracks
                        if (t != null && t.videos.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.dl_options_quality))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                t.videos.forEach { v ->
                                    TroyChip(v.height?.let { "${it}p" } ?: stringResource(R.string.dl_quality_unknown), video == v, { video = v })
                                }
                            }
                        }
                        if (t != null && t.audios.size > 1) {
                            SectionLabel(stringResource(R.string.dl_options_audio))
                            t.audios.forEach { a -> CheckRow(a.label ?: a.language ?: a.key, a in audios) { if (a in audios) audios.remove(a) else audios.add(a) } }
                        }
                        if (t != null && t.texts.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.dl_options_subtitles))
                            t.texts.forEach { s -> CheckRow(s.label ?: s.language ?: s.key, s in texts) { if (s in texts) texts.remove(s) else texts.add(s) } }
                        }
                        if (kind == DownloadKind.FILE && subs.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.dl_options_subtitles))
                            subs.forEach { s ->
                                CheckRow(listOfNotNull(s.label, s.language).joinToString(" · ").ifEmpty { s.url.substringAfterLast('/') }, s.url in chosenSubs) {
                                    if (s.url in chosenSubs) chosenSubs.remove(s.url) else chosenSubs.add(s.url)
                                }
                            }
                        }
                        if (vm.hasSchedule) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.dl_start_now), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                                TroyToggle(startNow, { startNow = it })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !blocked,
                onClick = {
                    val t = tracks
                    val choice = if (t != null) t.choice(video, audios.toList(), texts.toList()) else null
                    onConfirm(DownloadChoice(kind, choice, subs.filter { it.url in chosenSubs }, startNow))
                },
            ) { Text(stringResource(R.string.dl_download), color = if (!loading && !blocked) c.accent else c.text3) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    val c = Troy.colors
    Row(
        Modifier.fillMaxWidth().troyFocusRing().clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.text3, checkmarkColor = c.onAccent))
        Text(text, style = Troy.type.body, color = c.text)
    }
}

/** Qualities offered for "download the best stream" (16.1). Null means the best available. */
val downloadQualities: List<Int?> = listOf(null, 2160, 1080, 720, 480)

/**
 * A movie, one episode, a whole season or chosen episodes, with one quality choice (16.1), and
 * smart downloads for a series (16.6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkDownloadDialog(
    title: String,
    episodes: List<Episode>?,
    initialQuality: Int?,
    smart: Boolean?,
    onDismiss: () -> Unit,
    onConfirm: (quality: Int?, chosen: List<Episode>, smart: Boolean) -> Unit,
) {
    val c = Troy.colors
    var quality by remember { mutableStateOf(initialQuality) }
    val chosen = remember { mutableStateListOf<String>().apply { episodes?.let { list -> addAll(list.map { it.id }) } } }
    var smartOn by remember { mutableStateOf(smart == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Troy.type.title, color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.dl_options_quality))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    downloadQualities.forEach { q ->
                        TroyChip(q?.let { "${it}p" } ?: stringResource(R.string.dl_quality_best), quality == q, { quality = q })
                    }
                }
                if (episodes != null && episodes.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.dl_episodes), Modifier.weight(1f))
                        TextButton(onClick = {
                            if (chosen.size == episodes.size) chosen.clear() else { chosen.clear(); chosen.addAll(episodes.map { it.id }) }
                        }) { Text(stringResource(if (chosen.size == episodes.size) R.string.dl_select_none else R.string.dl_select_all), color = c.accent) }
                    }
                    episodes.forEach { e ->
                        val label = listOfNotNull(e.number?.let { stringResource(R.string.title_episode_number, it) }, e.name).joinToString(" · ")
                        CheckRow(label.ifEmpty { e.id }, e.id in chosen) { if (e.id in chosen) chosen.remove(e.id) else chosen.add(e.id) }
                    }
                }
                if (smart != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.dl_smart), style = Troy.type.body, color = c.text)
                            Text(stringResource(R.string.dl_smart_summary), style = Troy.type.bodySmall, color = c.text2)
                        }
                        TroyToggle(smartOn, { smartOn = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(quality, episodes.orEmpty().filter { it.id in chosen }, smartOn) }) {
                Text(stringResource(R.string.dl_download), color = c.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}
