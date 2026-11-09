package com.troy.orcaplayer.player.engine

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual audio delay (10.6). Media3 has no delay setting, so the position the audio sink reports
 * (the clock the video follows) is moved: a positive delay makes the sound play later than the
 * picture, a negative one earlier. Android already corrects the latency that devices report;
 * this is a correction on top.
 */
@OptIn(UnstableApi::class)
internal class AudioDelaySink(sink: AudioSink, private val delayUs: () -> Long) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p == AudioSink.CURRENT_POSITION_NOT_SET) return p
        return (p + delayUs()).coerceAtLeast(0)
    }
}

/** One audio output, as shown in the delay panel. */
data class AudioDevice(val key: String, val name: String, val wireless: Boolean)

/**
 * Remembers the delay for each audio output and applies it when that output connects (10.6).
 */
class AudioDelayController internal constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("audio_delay", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)

    private val _device = MutableStateFlow(current())
    /** The output in use now. */
    val device: StateFlow<AudioDevice> = _device.asStateFlow()

    private val _delayMs = MutableStateFlow(load(_device.value))
    /** The delay for the output in use, in milliseconds. */
    val delayMs: StateFlow<Int> = _delayMs.asStateFlow()

    @Volatile internal var delayUs: Long = _delayMs.value * 1000L
        private set

    init {
        audio?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    private fun load(d: AudioDevice) = prefs.getInt(d.key, 0)

    private fun refresh() {
        val d = current()
        if (d == _device.value) return
        _device.value = d
        apply(load(d))
    }

    private fun apply(ms: Int) {
        _delayMs.value = ms
        delayUs = ms * 1000L
    }

    /** Sets and saves the delay for the output in use. */
    fun setDelay(ms: Int) {
        val v = ms.coerceIn(MIN_MS, MAX_MS)
        apply(v)
        val key = _device.value.key
        prefs.edit().apply { if (v == 0) remove(key) else putInt(key, v) }.apply()
    }

    /** The output Android routes media to: the newest headset or speaker connection wins over the built-in speaker. */
    private fun current(): AudioDevice {
        val list = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().filter { it.isSink }
        val best = list.maxByOrNull { rank(it.type) }
        if (best == null || rank(best.type) == 0) return AudioDevice("speaker", "", false)
        val name = best.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
        val wireless = best.type in wirelessTypes
        return AudioDevice("${best.type}:$name", name, wireless)
    }

    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE headset */, 27 /* BLE speaker */, 30 /* BLE broadcast */ -> 5
        23 /* hearing aid */ -> 5
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 4
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 4
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, 29 /* HDMI eARC */ -> 3
        else -> 0
    }

    companion object {
        /**
         * Kept in the companion: as an instance property it was set only after [current] had
         * already run from the first property, so it was null then and the app crashed at start
         * whenever headphones, Bluetooth, USB audio or HDMI was connected.
         */
        private val wirelessTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 23, 26, 27, 30)

        const val MIN_MS = -1000
        const val MAX_MS = 1000
    }
}
