package com.troy.orcaplayer.ui.tools

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.player.subtitles.ArabicText
import com.troy.orcaplayer.player.subtitles.SubtitleCue
import com.troy.orcaplayer.player.subtitles.SubtitleEdits
import com.troy.orcaplayer.player.subtitles.SubtitleFormat
import com.troy.orcaplayer.player.subtitles.SubtitleParser
import com.troy.orcaplayer.player.subtitles.SubtitleWriter
import com.troy.orcaplayer.tools.MediaSaver
import com.troy.orcaplayer.tools.SaveKind
import com.troy.orcaplayer.tools.ToolMath
import com.troy.orcaplayer.ui.shell.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Subtitle editor (12.3): edit cue text and times, shift everything, two-point sync, search and
 * replace, split, merge, add and delete, change the encoding, and save as SRT, VTT or ASS.
 */
@Composable
fun SubtitleEditorScreen(target: ToolTarget, onBack: () -> Unit) {
    val c = Troy.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = target.subtitleUri ?: target.uri
    val sourceName = source.lastPathSegment?.substringAfterLast('/') ?: target.title
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    var encoding by remember { mutableStateOf("auto") }
    var cues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    val history = remember { mutableStateListOf<List<SubtitleCue>>() }
    var format by remember { mutableStateOf(SubtitleFormat.SRT) }
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var shiftText by remember { mutableStateOf("") }
    var editing by remember { mutableIntStateOf(-1) }
    var pointA by remember { mutableIntStateOf(-1) }
    var pointB by remember { mutableIntStateOf(-1) }
    var aTarget by remember { mutableStateOf("") }
    var bTarget by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }

    fun change(next: List<SubtitleCue>) {
        history.add(cues)
        if (history.size > 50) history.removeAt(0)
        cues = next
        dirty = true
    }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(source) {
        bytes = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(source)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null) failed = true
    }
    LaunchedEffect(bytes, encoding) {
        val b = bytes ?: return@LaunchedEffect
        val text = ArabicText.decode(b, encoding)
        val detected = SubtitleParser.detectFormat(text, sourceName)
        if (detected != SubtitleFormat.UNKNOWN) format = detected
        cues = SubtitleParser.parse(text, sourceName).cues
        history.clear()
        dirty = false
    }

    val outName = sourceName.substringBeforeLast('.').ifEmpty { "subtitles" } + "." + format.name.lowercase()
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(SubtitleWriter.write(cues, format).toByteArray()) }
                }.isSuccess
            }
            toast(context.getString(if (ok) R.string.sub_saved else R.string.sub_save_failed))
            if (ok) dirty = false
        }
    }
    fun saveToGallery() = scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.cacheDir, "sub_export").apply { mkdirs() }.resolve(outName)
                tmp.writeText(SubtitleWriter.write(cues, format))
                MediaSaver.save(context, tmp, outName, mimeOf(outName), SaveKind.SUBTITLE)
                tmp.delete()
            }.isSuccess
        }
        toast(context.getString(if (ok) R.string.sub_saved_documents else R.string.sub_save_failed))
        if (ok) dirty = false
    }

    val matches = remember(cues, query) { if (query.isBlank()) emptySet() else SubtitleEdits.search(cues, query).toSet() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.tool_subtitle_editor), onBack) {
            TroyIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.sub_undo), onClick = {
                if (history.isNotEmpty()) cues = history.removeAt(history.lastIndex)
            })
        }
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                ToolCard {
                    Text(sourceName, style = Troy.type.body, color = c.text, maxLines = 2)
                    if (failed) TroyAlert(stringResource(R.string.sub_open_failed), AlertKind.DANGER, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.sub_count, formatNumber(cues.size)), style = Troy.type.bodySmall, color = c.text2)
                    if (dirty) Text(stringResource(R.string.sub_unsaved), style = Troy.type.bodySmall, color = c.warn)
                    SectionLabel(stringResource(R.string.sub_encoding))
                    ChoiceRow {
                        ArabicText.manualEncodings.forEach { e ->
                            TroyChip(if (e == "auto") stringResource(R.string.sub_encoding_auto) else e, encoding == e, { encoding = e })
                        }
                    }
                }
            }
            item {
                ToolCard {
                    SectionLabel(stringResource(R.string.sub_shift))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TroyTextField(shiftText, { shiftText = it }, Modifier.weight(1f), placeholder = stringResource(R.string.sub_shift_hint))
                        TroyButton(stringResource(R.string.sub_apply), {
                            val s = shiftText.trim().replace(',', '.').toDoubleOrNull()
                            if (s == null) toast(context.getString(R.string.clip_bad_time)) else change(SubtitleEdits.shift(cues, (s * 1000).toLong()))
                        })
                    }
                    SectionLabel(stringResource(R.string.sub_sync))
                    Text(stringResource(R.string.sub_sync_info), style = Troy.type.bodySmall, color = c.text2)
                    SyncPoint("A", pointA, cues, aTarget) { aTarget = it }
                    SyncPoint("B", pointB, cues, bTarget) { bTarget = it }
                    TroyButton(stringResource(R.string.sub_sync_apply), {
                        val ta = ToolMath.parseTime(aTarget)
                        val tb = ToolMath.parseTime(bTarget)
                        if (pointA < 0 || pointB < 0 || pointA == pointB || ta == null || tb == null) {
                            toast(context.getString(R.string.sub_sync_missing))
                        } else {
                            change(SubtitleEdits.twoPointSync(cues, pointA, ta, pointB, tb))
                        }
                    }, enabled = pointA >= 0 && pointB >= 0)
                }
            }
            item {
                ToolCard {
                    SectionLabel(stringResource(R.string.sub_find))
                    TroyTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.sub_find_hint))
                    TroyTextField(replace, { replace = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.sub_replace_hint))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sub_matches, formatNumber(matches.size)), style = Troy.type.bodySmall, color = c.text2, modifier = Modifier.weight(1f))
                        TroyButton(stringResource(R.string.sub_replace_all), {
                            val (next, n) = SubtitleEdits.replaceAll(cues, query, replace)
                            if (n > 0) change(next)
                            toast(context.getString(R.string.sub_replaced, n))
                        }, enabled = query.isNotEmpty())
                    }
                }
            }
            item {
                ToolCard {
                    SectionLabel(stringResource(R.string.sub_save_as))
                    ChoiceRow {
                        listOf(SubtitleFormat.SRT, SubtitleFormat.VTT, SubtitleFormat.ASS).forEach { f ->
                            TroyChip(f.name, format == f, { format = f })
                        }
                    }
                    ChoiceRow {
                        TroyButton(stringResource(R.string.sub_save_file), { createDoc.launch(outName) }, kind = ButtonKind.PRIMARY, enabled = cues.isNotEmpty())
                        TroyButton(stringResource(R.string.sub_save_documents), { saveToGallery() }, enabled = cues.isNotEmpty())
                        TroyButton(stringResource(R.string.sub_add), {
                            val last = cues.lastOrNull()?.endMs ?: 0L
                            change(SubtitleEdits.add(cues, SubtitleCue(last + 500, last + 2500, "…")))
                        }, kind = ButtonKind.GHOST)
                    }
                }
            }
            itemsIndexed(cues, key = { i, cue -> "$i:${cue.startMs}" }) { i, cue ->
                CueRow(
                    index = i,
                    cue = cue,
                    match = i in matches,
                    expanded = editing == i,
                    marks = buildString { if (pointA == i) append("A"); if (pointB == i) append("B") },
                    onToggle = { editing = if (editing == i) -1 else i },
                    onSave = { change(SubtitleEdits.update(cues, i, it)); editing = -1 },
                    onSplit = { change(SubtitleEdits.split(cues, i)); editing = -1 },
                    onMerge = { change(SubtitleEdits.mergeWithNext(cues, i)); editing = -1 },
                    onDelete = { change(SubtitleEdits.delete(cues, i)); editing = -1 },
                    onMarkA = { pointA = i; aTarget = ToolMath.formatTime(cue.startMs) },
                    onMarkB = { pointB = i; bTarget = ToolMath.formatTime(cue.startMs) },
                    hasNext = i < cues.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun SyncPoint(name: String, index: Int, cues: List<SubtitleCue>, target: String, onTarget: (String) -> Unit) {
    val c = Troy.colors
    val cue = cues.getOrNull(index)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (cue == null) stringResource(R.string.sub_sync_pick, name) else stringResource(R.string.sub_sync_point, name, ToolMath.formatTime(cue.startMs), cue.text.lineSequence().first()),
            style = Troy.type.bodySmall, color = if (cue == null) c.text3 else c.text,
            maxLines = 2,
        )
        if (cue != null) TroyTextField(target, onTarget, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.sub_sync_target))
    }
}

@Composable
private fun CueRow(
    index: Int,
    cue: SubtitleCue,
    match: Boolean,
    expanded: Boolean,
    marks: String,
    hasNext: Boolean,
    onToggle: () -> Unit,
    onSave: (SubtitleCue) -> Unit,
    onSplit: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onMarkA: () -> Unit,
    onMarkB: () -> Unit,
) {
    val c = Troy.colors
    var start by remember(cue, expanded) { mutableStateOf(ToolMath.formatTime(cue.startMs)) }
    var end by remember(cue, expanded) { mutableStateOf(ToolMath.formatTime(cue.endMs)) }
    var text by remember(cue, expanded) { mutableStateOf(cue.text) }
    var bad by remember(cue, expanded) { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel)
            .then(if (match) Modifier.border(1.dp, c.accent, TroyShapes.Panel) else Modifier)
            .troyFocusRing().clickable(role = Role.Button, onClick = onToggle).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatNumber(index + 1), style = Troy.type.mono, color = c.text3)
            Text("${ToolMath.formatTime(cue.startMs)} → ${ToolMath.formatTime(cue.endMs)}", style = Troy.type.mono, color = c.text2, modifier = Modifier.weight(1f))
            if (marks.isNotEmpty()) Text(marks, style = Troy.type.label, color = c.accent)
        }
        Text(
            cue.text,
            style = Troy.type.body.copy(textDirection = TextDirection.Content),
            color = c.text,
        )
        if (expanded) {
            TroyTextField(start, { start = it; bad = false }, Modifier.fillMaxWidth(), placeholder = "0:00:00.000")
            TroyTextField(end, { end = it; bad = false }, Modifier.fillMaxWidth(), placeholder = "0:00:00.000")
            TroyTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = false)
            if (bad) Text(stringResource(R.string.clip_bad_time), style = Troy.type.bodySmall, color = c.danger)
            ChoiceRow {
                TroyButton(stringResource(R.string.sub_apply), {
                    val s = ToolMath.parseTime(start)
                    val e = ToolMath.parseTime(end)
                    if (s == null || e == null || e <= s) bad = true else onSave(cue.copy(startMs = s, endMs = e, text = text))
                }, kind = ButtonKind.PRIMARY)
                TroyButton(stringResource(R.string.sub_split), onSplit)
                if (hasNext) TroyButton(stringResource(R.string.sub_merge), onMerge)
                TroyButton(stringResource(R.string.sub_mark_a), onMarkA, kind = ButtonKind.GHOST)
                TroyButton(stringResource(R.string.sub_mark_b), onMarkB, kind = ButtonKind.GHOST)
                TroyButton(stringResource(R.string.sub_delete), onDelete, kind = ButtonKind.GHOST)
            }
        }
    }
}
