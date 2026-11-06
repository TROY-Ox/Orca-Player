@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.troy.orcaplayer.ui.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.core.design.util.formatNumber
import com.troy.orcaplayer.tools.JobSpec
import com.troy.orcaplayer.tools.JobState
import com.troy.orcaplayer.tools.JobStatus
import com.troy.orcaplayer.tools.JobsRepository
import com.troy.orcaplayer.tools.MediaSaver
import com.troy.orcaplayer.tools.ToolJobService
import com.troy.orcaplayer.ui.shell.BackTopBar

/** Common frame of every tool screen: back bar, scrolling column of cards. */
@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title, onBack, trailing)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun ToolCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().glass(TroyShapes.Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Starts a background job. Asks once for the notification permission (Android 13+) so progress
 * shows, and for storage on Android 9 and older so the result can be saved. The job starts either way.
 */
@Composable
fun rememberJobStarter(onStarted: (JobState) -> Unit): (JobSpec) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<JobSpec>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending[0]?.let { onStarted(ToolJobService.enqueue(context, it)) }
        pending[0] = null
    }
    return remember(launcher) {
        { spec ->
            val needed = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (MediaSaver.needsLegacyPermission) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) {
                onStarted(ToolJobService.enqueue(context, spec))
            } else {
                pending[0] = spec
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}

/** Live state of one job: progress, then Open and Share when it is done. */
@Composable
fun JobProgressCard(jobId: String?) {
    if (jobId == null) return
    val jobs by JobsRepository.jobs.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == jobId } ?: return
    ToolCard { JobRow(job) }
}

@Composable
fun JobRow(job: JobState, onRemove: (() -> Unit)? = null) {
    val c = Troy.colors
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(job.outputName ?: job.spec.title, style = Troy.type.body, color = c.text, maxLines = 2)
        val statusText = when (job.status) {
            JobStatus.QUEUED -> stringResource(R.string.job_queued)
            JobStatus.RUNNING -> if (job.progress >= 0) stringResource(R.string.job_progress, formatNumber(job.progress)) else stringResource(R.string.job_working)
            JobStatus.DONE -> stringResource(R.string.job_done)
            JobStatus.FAILED -> stringResource(R.string.job_failed)
            JobStatus.CANCELLED -> stringResource(R.string.job_cancelled)
        }
        if (job.status == JobStatus.FAILED) {
            TroyAlert(statusText + (job.error?.let { " · $it" } ?: ""), AlertKind.DANGER, Modifier.fillMaxWidth())
        } else {
            Text(statusText, style = Troy.type.bodySmall, color = c.text2)
        }
        if (job.status == JobStatus.RUNNING) ProgressLine(if (job.progress >= 0) job.progress / 100f else null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val out = job.outputUri?.let(Uri::parse)
            if (job.active) {
                TroyButton(stringResource(R.string.job_cancel_button), { ToolJobService.cancel(context, job.id) }, kind = ButtonKind.GHOST)
            }
            if (job.status == JobStatus.DONE && out != null) {
                TroyButton(stringResource(R.string.tool_open), { openUri(context, out, mimeOf(job.outputName)) }, kind = ButtonKind.PRIMARY)
                TroyButton(stringResource(R.string.tool_share), { shareUri(context, out, mimeOf(job.outputName)) })
            }
            if (!job.active && onRemove != null) {
                TroyButton(stringResource(R.string.job_remove), onRemove, kind = ButtonKind.GHOST)
            }
        }
    }
}

/** A thin bar; with no value it shows an empty track (the work is running, progress unknown). */
@Composable
fun ProgressLine(fraction: Float?) {
    val c = Troy.colors
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.hair)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
    }
}

@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun Gap() = Spacer(Modifier.height(4.dp))

fun mimeOf(name: String?): String {
    val n = name?.lowercase().orEmpty()
    return when {
        n.endsWith(".mp4") -> "video/mp4"
        n.endsWith(".m4a") -> "audio/mp4"
        n.endsWith(".png") -> "image/png"
        n.endsWith(".jpg") -> "image/jpeg"
        n.endsWith(".srt") -> "application/x-subrip"
        n.endsWith(".vtt") -> "text/vtt"
        n.endsWith(".ass") -> "text/x-ssa"
        else -> "*/*"
    }
}

fun shareUri(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openUri(context: Context, uri: Uri, mime: String) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        shareUri(context, uri, mime)
    }
}
