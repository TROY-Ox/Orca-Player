package com.troy.orcaplayer.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

enum class TapZone { START, CENTER, END }

/** What the player does for each gesture. Positions are physical: media controls are not mirrored in RTL. */
interface GestureHandler {
    fun onTap()
    /** A second quick tap: undo the controls toggle from the first one. */
    fun onDoubleTapStart()
    /** [streak] is 1 on the second tap, 2 on the third and so on. */
    fun onMultiTap(zone: TapZone, streak: Int)
    fun onLongPressStart()
    fun onLongPressEnd()
    fun onSeekStart()
    fun onSeekDrag(totalDp: Float)
    fun onSeekEnd()
    fun onVerticalStart(leftHalf: Boolean)
    fun onVerticalMove(leftHalf: Boolean, upDp: Float, dtMs: Long)
    fun onVerticalEnd(leftHalf: Boolean)
    fun onPinch(zoom: Float, pan: Offset)
    fun onPinchEnd()
}

data class GestureConfig(
    val doubleTap: Boolean = true,
    val swipeSeek: Boolean = true,
    val swipeSliders: Boolean = true,
    val pinch: Boolean = true,
    val longPress: Boolean = true,
    /** System gesture areas in px (left, top, right, bottom): drags never start there. */
    val exclusion: FloatArray = FloatArray(4),
    val enabled: Boolean = true,
) {
    override fun equals(other: Any?) = other is GestureConfig && other.doubleTap == doubleTap && other.swipeSeek == swipeSeek &&
        other.swipeSliders == swipeSliders && other.pinch == pinch && other.longPress == longPress &&
        other.exclusion.contentEquals(exclusion) && other.enabled == enabled

    override fun hashCode() = listOf(doubleTap, swipeSeek, swipeSliders, pinch, longPress, exclusion.toList(), enabled).hashCode()
}

private enum class Phase { UP, DRAG_H, DRAG_V, PINCH, CANCEL }

/**
 * Tap, double tap, long press, horizontal seek, vertical sliders and pinch, in one detector.
 * A single tap acts at once; a quick second tap turns it into a skip.
 */
fun Modifier.playerGestures(config: GestureConfig, handler: GestureHandler): Modifier = pointerInput(config) {
    if (!config.enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis + 60
    var lastUp = 0L
    var streak = 0
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val ex = config.exclusion
        val inEdge = start.x < ex[0] || start.y < ex[1] || start.x > w - ex[2] || start.y > h - ex[3]
        var upTime = 0L
        val phase = withTimeoutOrNull(longPressMs) {
            var result: Phase? = null
            while (result == null) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                result = when {
                    ev.changes.count { it.pressed } >= 2 -> Phase.PINCH
                    ch == null -> Phase.CANCEL
                    !ch.pressed -> {
                        upTime = ch.uptimeMillis
                        Phase.UP
                    }
                    else -> {
                        val d = ch.position - start
                        when {
                            abs(d.x) <= slop && abs(d.y) <= slop -> null
                            abs(d.x) > abs(d.y) -> Phase.DRAG_H
                            else -> Phase.DRAG_V
                        }
                    }
                }
            }
            result
        }
        when (phase) {
            null -> {
                // Long press: speed up while held.
                if (!config.longPress) return@awaitEachGesture
                handler.onLongPressStart()
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
                handler.onLongPressEnd()
                streak = 0
            }
            Phase.UP -> {
                val zone = when {
                    start.x < w * 0.35f -> TapZone.START
                    start.x > w * 0.65f -> TapZone.END
                    else -> TapZone.CENTER
                }
                if (config.doubleTap && upTime - lastUp < doubleTapMs && lastUp != 0L) {
                    streak++
                    if (streak == 1) handler.onDoubleTapStart()
                    handler.onMultiTap(zone, streak)
                } else {
                    streak = 0
                    handler.onTap()
                }
                lastUp = upTime
            }
            Phase.DRAG_H -> {
                streak = 0
                if (!config.swipeSeek || inEdge) return@awaitEachGesture
                handler.onSeekStart()
                var total = 0f
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    total = (ch.position.x - start.x) / density
                    handler.onSeekDrag(total)
                    ch.consume()
                }
                handler.onSeekEnd()
            }
            Phase.DRAG_V -> {
                streak = 0
                if (!config.swipeSliders || inEdge) return@awaitEachGesture
                val left = start.x < w / 2f
                handler.onVerticalStart(left)
                var prevY = start.y
                var prevT = down.uptimeMillis
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val dy = (prevY - ch.position.y) / density
                    if (dy != 0f) handler.onVerticalMove(left, dy, ch.uptimeMillis - prevT)
                    prevY = ch.position.y
                    prevT = ch.uptimeMillis
                    ch.consume()
                }
                // The value is final when the finger lifts: no glide past this point.
                handler.onVerticalEnd(left)
            }
            Phase.PINCH -> {
                streak = 0
                if (!config.pinch) return@awaitEachGesture
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    if (ev.changes.none { it.pressed }) break
                    val zoom = ev.calculateZoom()
                    val pan = ev.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) handler.onPinch(zoom, pan)
                    ev.changes.forEach { it.consume() }
                }
                handler.onPinchEnd()
            }
            Phase.CANCEL -> streak = 0
        }
    }
}
