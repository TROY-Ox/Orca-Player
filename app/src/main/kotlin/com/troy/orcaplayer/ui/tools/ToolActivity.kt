package com.troy.orcaplayer.ui.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.AppViewModel
import com.troy.orcaplayer.core.design.theme.OrcaTheme
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.core.model.ToolId
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.system.SystemSignals
import com.troy.orcaplayer.themeState
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts one tool on one file: from the Tools tab (any file, without playing it) or from the
 * player's Tools panel (the video that is playing).
 */
@AndroidEntryPoint
class ToolActivity : AppCompatActivity() {
    private val appVm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        appVm.updateSignals(SystemSignals.read(this))
        val tool = intent.getStringExtra(EXTRA_TOOL)?.let { runCatching { ToolId.valueOf(it) }.getOrNull() }
        val uri = intent.data
        if (tool == null || uri == null) {
            finish()
            return
        }
        val target = ToolTarget(
            uri = uri,
            title = intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: "",
            headers = (intent.getSerializableExtraCompat(EXTRA_HEADERS) ?: emptyMap()),
            positionMs = intent.getLongExtra(EXTRA_POSITION, 0),
            subtitleUri = intent.getStringExtra(EXTRA_SUBTITLE)?.let(Uri::parse),
        )
        setContent {
            val values by appVm.settings.collectAsStateWithLifecycle()
            val signals by appVm.signals.collectAsStateWithLifecycle()
            val isArabic = LocalConfiguration.current.locales[0]?.language == "ar"
            OrcaTheme(themeState(values ?: SettingsValues.Defaults, signals, isArabic)) {
                Box(Modifier.fillMaxSize().background(Troy.colors.bg)) {
                    when (tool) {
                        ToolId.CLIP_CUTTER -> ClipCutterScreen(target, ::finish)
                        ToolId.SUBTITLE_EDITOR -> SubtitleEditorScreen(target, ::finish)
                        ToolId.AUDIO_EXTRACTOR -> ConvertScreen(target, ConvertMode.EXTRACT, ::finish)
                        ToolId.CONVERTER -> ConvertScreen(target, ConvertMode.CONVERT, ::finish)
                        ToolId.COMPRESSOR -> ConvertScreen(target, ConvertMode.COMPRESS, ::finish)
                        ToolId.MEDIA_INFO -> MediaInfoScreen(target, ::finish)
                        else -> finish()
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun Intent.getSerializableExtraCompat(key: String): Map<String, String>? =
        (getSerializableExtra(key) as? HashMap<String, String>)

    companion object {
        const val EXTRA_TOOL = "tool"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_POSITION = "position"
        const val EXTRA_SUBTITLE = "subtitle"

        fun intent(context: Context, tool: ToolId, uri: Uri, title: String, headers: Map<String, String> = emptyMap(), positionMs: Long = 0, subtitle: Uri? = null): Intent =
            Intent(context, ToolActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_TOOL, tool.name)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADERS, HashMap(headers))
                .putExtra(EXTRA_POSITION, positionMs)
                .apply { subtitle?.let { putExtra(EXTRA_SUBTITLE, it.toString()) } }
    }
}

/** The file a tool works on. */
data class ToolTarget(
    val uri: Uri,
    val title: String,
    val headers: Map<String, String>,
    val positionMs: Long,
    val subtitleUri: Uri?,
) {
    val isStream: Boolean get() = uri.scheme == "http" || uri.scheme == "https"
}
