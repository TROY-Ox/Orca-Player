package com.troy.orcaplayer.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import com.troy.orcaplayer.tools.MediaSaver
import com.troy.orcaplayer.tools.SaveKind
import com.troy.orcaplayer.tools.ToolMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Holds the video's SurfaceView so a frame can be copied from it. */
class VideoSurfaceRef {
    var view: SurfaceView? = null
}

/**
 * The video surface. A plain SurfaceView (the most efficient path, and HDR capable) that the
 * screenshot tool can read with PixelCopy.
 */
@Composable
fun VideoSurface(player: Player, ref: VideoSurfaceRef, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(
        factory = { ctx -> SurfaceView(ctx).also { view = it; ref.view = it } },
        modifier = modifier.fillMaxSize(),
    )
    val v = view
    DisposableEffect(player, v) {
        if (v != null) player.setVideoSurfaceView(v)
        onDispose { if (v != null) player.clearVideoSurfaceView(v) }
    }
}

object Screenshot {
    /** Copies the current frame at the video's own size. Null when the surface is empty or protected. */
    suspend fun capture(view: SurfaceView, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || !view.holder.surface.isValid) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val result = suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(view, bitmap, { code -> if (cont.isActive) cont.resume(code) }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(PixelCopy.ERROR_UNKNOWN) }
        }
        return if (result == PixelCopy.SUCCESS) bitmap else null.also { bitmap.recycle() }
    }

    /** Draws the visible subtitle lines near the bottom, white with a dark outline. */
    fun burnSubtitles(bitmap: Bitmap, lines: List<String>, rtl: Boolean) {
        if (lines.isEmpty()) return
        val canvas = Canvas(bitmap)
        val size = bitmap.height / 18f
        val text = lines.joinToString("\n")
        val width = (bitmap.width * 0.9f).toInt()
        fun layout(paint: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(if (rtl) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeWidth = size / 7f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        val outline = layout(stroke)
        val body = layout(fill)
        canvas.save()
        canvas.translate((bitmap.width - width) / 2f, bitmap.height - body.height - bitmap.height * 0.06f)
        outline.draw(canvas)
        body.draw(canvas)
        canvas.restore()
    }

    /** Saves to Pictures/ORCA Player/Screenshots and returns the new picture's URI. */
    suspend fun save(context: Context, bitmap: Bitmap, title: String, positionMs: Long, png: Boolean): Uri = withContext(Dispatchers.IO) {
        val ext = if (png) "png" else "jpg"
        val stamp = ToolMath.formatTime(positionMs).replace(':', '-')
        val name = ToolMath.outputName(title, stamp, ext)
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        val file = File(dir, "shot.$ext")
        file.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
        try {
            MediaSaver.save(context, file, name, if (png) "image/png" else "image/jpeg", SaveKind.SCREENSHOT)
        } finally {
            file.delete()
        }
    }
}
