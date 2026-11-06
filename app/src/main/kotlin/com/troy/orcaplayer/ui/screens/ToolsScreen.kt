package com.troy.orcaplayer.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.model.MediaKeys
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.PlayRequest
import com.troy.orcaplayer.core.model.SourceKind
import com.troy.orcaplayer.core.model.SourceRef
import com.troy.orcaplayer.core.model.ToolId
import com.troy.orcaplayer.core.model.ToolsLayout
import com.troy.orcaplayer.core.model.UriSchemes
import com.troy.orcaplayer.tools.JobsRepository
import com.troy.orcaplayer.ui.player.PlayerLaunch
import com.troy.orcaplayer.ui.shell.MainTopBar
import com.troy.orcaplayer.ui.tools.ToolActivity
import com.troy.orcaplayer.ui.tools.icon
import com.troy.orcaplayer.ui.tools.nameRes
import com.troy.orcaplayer.ui.tools.summaryRes

/** The Tools tab: every tool works on any file, without playing it first. */
@Composable
fun ToolsScreen(
    layout: ToolsLayout,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenDecoderCheck: () -> Unit,
    onOpenJobs: () -> Unit,
    onCustomize: () -> Unit,
) {
    val c = Troy.colors
    val context = LocalContext.current
    var pendingTool by rememberSaveable { mutableStateOf<ToolId?>(null) }
    var urlOpen by rememberSaveable { mutableStateOf(false) }
    val jobs by JobsRepository.jobs.collectAsStateWithLifecycle()
    val running = jobs.count { it.active }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val tool = pendingTool ?: return@rememberLauncherForActivityResult
        pendingTool = null
        if (uri == null) return@rememberLauncherForActivityResult
        // Jobs read the file after this screen is gone, so keep the grant.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        val title = if (tool == ToolId.SUBTITLE_EDITOR) name else name.substringBeforeLast('.')
        context.startActivity(ToolActivity.intent(context, tool, uri, title))
    }

    fun open(tool: ToolId) {
        when (tool) {
            ToolId.DECODER_CHECK -> onOpenDecoderCheck()
            ToolId.URL_PLAYER -> urlOpen = !urlOpen
            ToolId.DOWNLOAD -> Toast.makeText(context, R.string.tool_download_later, Toast.LENGTH_SHORT).show()
            ToolId.SUBTITLE_EDITOR -> { pendingTool = tool; picker.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream", "*/*")) }
            ToolId.AUDIO_EXTRACTOR, ToolId.MEDIA_INFO -> { pendingTool = tool; picker.launch(arrayOf("video/*", "audio/*")) }
            ToolId.CLIP_CUTTER, ToolId.CONVERTER, ToolId.COMPRESSOR -> { pendingTool = tool; picker.launch(arrayOf("video/*")) }
            else -> Unit // Player-only tools are not listed here.
        }
    }

    Column(Modifier.fillMaxSize()) {
        MainTopBar(stringResource(R.string.tab_tools), onOpenSettings, onOpenProfiles) {
            TroyIconButton(Icons.AutoMirrored.Filled.List, stringResource(R.string.jobs_title), onOpenJobs)
            TroyIconButton(Icons.Filled.Tune, stringResource(R.string.tools_customize), onCustomize)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (running > 0) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
                        .clickable(role = Role.Button, onClick = onOpenJobs).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.jobs_running, running), style = Troy.type.body, color = c.accent, modifier = Modifier.weight(1f))
                }
            }
            layout.tools.filter { it.inTab }.forEach { tool ->
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().glass(TroyShapes.Panel).troyFocusRing()
                            .clickable(role = Role.Button, onClick = { open(tool) }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(tool.icon(), contentDescription = null, tint = if (tool == ToolId.DOWNLOAD) c.text3 else c.accent, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(tool.nameRes()), style = Troy.type.title, color = c.text)
                            Text(stringResource(tool.summaryRes()), style = Troy.type.bodySmall, color = c.text2)
                        }
                    }
                    if (tool == ToolId.URL_PLAYER && urlOpen) UrlPlayer()
                }
            }
        }
    }
}

/** Plays a pasted link, with optional Referer and User-Agent headers. */
@Composable
private fun UrlPlayer() {
    val c = Troy.colors
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var referer by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf("") }
    var bad by rememberSaveable { mutableStateOf(false) }
    fun play() {
        val u = url.trim()
        if (UriSchemes.schemeOf(u) !in setOf("http", "https", "rtsp", "rtmp") || !u.contains("://")) {
            bad = true
            return
        }
        val headers = buildMap {
            referer.trim().takeIf { it.isNotEmpty() }?.let { put("Referer", it) }
            agent.trim().takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
        }
        val item = PlayItem(
            uri = u,
            title = Uri.parse(u).lastPathSegment ?: u,
            key = MediaKeys.forLink(u),
            source = SourceRef(SourceKind.LINK, "link"),
            headers = headers,
        )
        PlayerLaunch.play(context, PlayRequest(listOf(item)))
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).glass(TroyShapes.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TroyTextField(url, { url = it; bad = false }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.url_hint), onImeAction = ::play)
        TroyTextField(referer, { referer = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.url_referer))
        TroyTextField(agent, { agent = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.url_agent))
        if (bad) Text(stringResource(R.string.url_bad), style = Troy.type.bodySmall, color = c.danger)
        TroyButton(stringResource(R.string.player_play), ::play, kind = ButtonKind.PRIMARY, enabled = url.isNotBlank())
    }
}
