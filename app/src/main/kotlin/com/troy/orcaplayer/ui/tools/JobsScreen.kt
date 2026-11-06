package com.troy.orcaplayer.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.tools.JobsRepository

/** Running, queued and finished tool jobs. Jobs keep running when the app is closed. */
@Composable
fun JobsScreen(onBack: () -> Unit) {
    val c = Troy.colors
    val jobs by JobsRepository.jobs.collectAsStateWithLifecycle()
    ToolScaffold(
        stringResource(R.string.jobs_title), onBack,
        trailing = { TroyIconButton(Icons.Filled.ClearAll, stringResource(R.string.jobs_clear), onClick = { JobsRepository.clearFinished() }) },
    ) {
        if (jobs.isEmpty()) {
            ToolCard { Text(stringResource(R.string.jobs_empty), style = Troy.type.body, color = c.text2) }
        }
        val (active, done) = jobs.partition { it.active }
        if (active.isNotEmpty()) SectionLabel(stringResource(R.string.jobs_active), Modifier.fillMaxWidth())
        active.forEach { job -> ToolCard { JobRow(job) } }
        if (done.isNotEmpty()) SectionLabel(stringResource(R.string.jobs_finished), Modifier.fillMaxWidth())
        done.forEach { job -> ToolCard { JobRow(job, onRemove = { JobsRepository.remove(job.id) }) } }
    }
}
