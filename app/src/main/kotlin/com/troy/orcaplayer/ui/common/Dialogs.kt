package com.troy.orcaplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.theme.Troy

/** A themed question with one action, for explanations before a permission or a step that can't be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    dismiss: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = Troy.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Troy.type.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, style = Troy.type.body, color = c.text2)
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) c.danger else c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss ?: stringResource(R.string.dialog_cancel), color = c.text2) } },
        containerColor = Troy.glass.panel.copy(alpha = 1f),
    )
}
