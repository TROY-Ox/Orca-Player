package com.troy.orcaplayer.ui.links

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.glass
import com.troy.orcaplayer.core.design.component.materialize
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.design.theme.TroyShapes
import com.troy.orcaplayer.sources.api.MediaLinks

/**
 * Looks at the clipboard when the app comes to the front (13). On Android 12+ the clip's
 * description says whether it is a link without reading it, so the "pasted from" notice only
 * appears when the clip is very likely a link.
 */
object ClipboardLinks {
    private var lastStamp = Long.MIN_VALUE
    private var lastText: String? = null

    fun check(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val desc = cm.primaryClipDescription ?: return null
        val stamp = if (Build.VERSION.SDK_INT >= 26) desc.timestamp else 0L
        if (stamp != 0L && stamp == lastStamp) return null
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return null
        if (Build.VERSION.SDK_INT >= 31) {
            if (desc.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE) return null
            if (desc.getConfidenceScore(TextClassifier.TYPE_URL) < 0.5f) return null
        }
        lastStamp = stamp
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull() ?: return null
        if (text == lastText) return null
        lastText = text
        val link = MediaLinks.firstLink(text) ?: return null
        return link.takeIf { MediaLinks.isLikelyMedia(it) || MediaLinks.kindOf(it) == MediaLinks.Kind.STREMIO_MANIFEST }
    }
}

@Composable
fun ClipboardOffer(enabled: Boolean, onPlay: (String) -> Unit, onInstall: (String) -> Unit) {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var offer by remember { mutableStateOf<String?>(null) }
    // Android 10+ lets an app read the clipboard only while it has focus.
    LaunchedEffect(focused, enabled) {
        if (focused && enabled) ClipboardLinks.check(context)?.let { offer = it }
    }
    val link = offer ?: return
    val c = Troy.colors
    val addon = MediaLinks.kindOf(link) == MediaLinks.Kind.STREMIO_MANIFEST
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(16.dp).padding(bottom = 72.dp).widthIn(max = 560.dp).fillMaxWidth().materialize().glass(TroyShapes.Panel, elevation = 16.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(if (addon) R.string.clip_offer_addon else R.string.clip_offer_play), style = Troy.type.body, color = c.text)
            Text(link, style = Troy.type.mono, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TroyButton(stringResource(R.string.clip_not_now), { offer = null }, kind = ButtonKind.GHOST)
                TroyButton(
                    stringResource(if (addon) R.string.addons_preview else R.string.player_play),
                    { offer = null; if (addon) onInstall(link) else onPlay(link) },
                    kind = ButtonKind.PRIMARY,
                )
            }
        }
    }
}
