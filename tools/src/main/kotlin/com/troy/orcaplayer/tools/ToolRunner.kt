package com.troy.orcaplayer.tools

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Device encoder checks for the converter. */
object Encoders {
    fun supports(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)
}

/** Keyframe lookups for the fast clip mode. */
object Keyframes {
    /** The last keyframe at or before [ms], or null when the file can't be read this way (for example HLS). */
    fun previousSync(context: Context, uri: Uri, ms: Long, headers: Map<String, String> = emptyMap()): Long? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, headers)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return@runCatching null
            ex.selectTrack(track)
            ex.seekTo(ms * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            ex.sampleTime.takeIf { it >= 0 }?.div(1000)
        } finally {
            ex.release()
        }
    }.getOrNull()
}

/**
 * Runs queued jobs one at a time with Media3 Transformer (hardware encoders, and stream copy
 * when nothing needs re-encoding). Must be used from the main thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class ToolRunner(private val context: Context, private val onIdle: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transformer: Transformer? = null
    private var currentId: String? = null
    private var output: File? = null
    private val holder = ProgressHolder()

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val id = currentId ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                JobsRepository.update(id) { it.copy(progress = holder.progress) }
            }
            main.postDelayed(this, 500)
        }
    }

    val busy: Boolean get() = currentId != null

    fun kick() {
        if (busy) return
        val job = JobsRepository.next()
        if (job == null) {
            onIdle()
            return
        }
        start(job)
    }

    fun cancel(id: String) {
        if (id == currentId) {
            transformer?.cancel()
            finish(id) { it.copy(status = JobStatus.CANCELLED) }
        } else {
            JobsRepository.update(id) { if (it.status == JobStatus.QUEUED) it.copy(status = JobStatus.CANCELLED) else it }
        }
    }

    private fun start(job: JobState) {
        currentId = job.id
        JobsRepository.update(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0) }
        io.launch {
            val prepared = runCatching { prepare(job.spec) }
            withContext(Dispatchers.Main) {
                prepared.fold(
                    onSuccess = { (item, builder, ext) -> run(job, item, builder, ext) },
                    onFailure = { e -> finish(job.id) { it.copy(status = JobStatus.FAILED, error = e.message ?: e.javaClass.simpleName) } },
                )
            }
        }
    }

    private data class Prepared(val item: EditedMediaItem, val builder: Transformer.Builder, val ext: String)

    /** Builds the edited item off the main thread (the fast clip mode reads keyframes). */
    private fun prepare(spec: JobSpec): Prepared {
        val uri = Uri.parse(spec.sourceUri)
        val mediaItem = MediaItem.Builder().setUri(uri)
        val builder = Transformer.Builder(context)
        val effects = mutableListOf<Effect>()
        var removeAudio = false
        var removeVideo = false
        var ext = "mp4"
        when (spec) {
            is ClipJob -> {
                var start = spec.startMs
                var atKeyframe = false
                if (spec.mode == ClipMode.FAST && !spec.audioOnly) {
                    Keyframes.previousSync(context, uri, spec.startMs, spec.headers)?.let { start = it; atKeyframe = true }
                }
                mediaItem.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .setEndPositionMs(spec.endMs)
                        .setStartsAtKeyFrame(atKeyframe)
                        .build(),
                )
                if (spec.mode == ClipMode.SMART && !spec.audioOnly) builder.experimentalSetTrimOptimizationEnabled(true)
                if (spec.audioOnly) {
                    removeVideo = true
                    builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    ext = "m4a"
                }
            }
            is ExtractJob -> {
                removeVideo = true
                builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                ext = "m4a"
            }
            is ConvertJob -> {
                spec.videoMime?.let { builder.setVideoMimeType(it) }
                spec.height?.let { effects += Presentation.createForHeight(it) }
                spec.frameRate?.let { effects += FrameDropEffect.createDefaultFrameDropEffect(it.toFloat()) }
                removeAudio = !spec.keepAudio
                removeVideo = !spec.keepVideo
                if (removeVideo) {
                    builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    ext = "m4a"
                }
            }
            is CompressJob -> {
                builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                builder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                        .setEnableFallback(true)
                        .build(),
                )
                spec.height?.let { effects += Presentation.createForHeight(it) }
            }
        }
        val edited = EditedMediaItem.Builder(mediaItem.build())
            .setRemoveAudio(removeAudio)
            .setRemoveVideo(removeVideo)
            .setEffects(Effects(emptyList(), effects))
            .build()
        return Prepared(edited, builder, ext)
    }

    private fun run(job: JobState, item: EditedMediaItem, builder: Transformer.Builder, ext: String) {
        if (currentId != job.id) return // cancelled while preparing
        val dir = File(context.cacheDir, "tool_jobs").apply { mkdirs() }
        val out = File(dir, job.id + "." + ext)
        output = out
        val t = builder.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                publish(job, out, ext)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                out.delete()
                finish(job.id) { it.copy(status = JobStatus.FAILED, error = exportException.errorCodeName) }
            }
        }).build()
        transformer = t
        runCatching { t.start(item, out.absolutePath) }.onFailure { e ->
            finish(job.id) { it.copy(status = JobStatus.FAILED, error = e.message) }
            return
        }
        main.postDelayed(poll, 500)
    }

    private fun publish(job: JobState, file: File, ext: String) {
        main.removeCallbacks(poll)
        io.launch {
            val spec = job.spec
            val audio = ext == "m4a"
            val name = when (spec) {
                is ClipJob -> ToolMath.clipName(spec.title, spec.startMs, spec.endMs, ext)
                is ExtractJob -> ToolMath.outputName(spec.title, "audio", ext)
                is ConvertJob -> ToolMath.outputName(spec.title, "converted", ext)
                is CompressJob -> ToolMath.outputName(spec.title, "compressed", ext)
            }
            val kind = when {
                audio -> SaveKind.AUDIO
                spec is ClipJob -> SaveKind.CLIP
                else -> SaveKind.VIDEO
            }
            val result = runCatching { MediaSaver.save(context, file, name, if (audio) "audio/mp4" else "video/mp4", kind) }
            file.delete()
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { uri -> finish(job.id) { it.copy(status = JobStatus.DONE, progress = 100, outputUri = uri.toString(), outputName = name) } },
                    onFailure = { e -> finish(job.id) { it.copy(status = JobStatus.FAILED, error = e.message) } },
                )
            }
        }
    }

    private fun finish(id: String, change: (JobState) -> JobState) {
        main.removeCallbacks(poll)
        JobsRepository.update(id, change)
        if (currentId == id) {
            currentId = null
            transformer = null
            output?.takeIf { JobsRepository.get(id)?.status != JobStatus.DONE }?.delete()
            output = null
        }
        main.post { kick() }
    }

    fun release() {
        transformer?.cancel()
        main.removeCallbacks(poll)
    }
}
