package com.troy.orcaplayer.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class ClipMode {
    /** Copies the range; the start snaps to the keyframe before it. */
    FAST,
    /** Re-encodes only up to the next keyframe, copies the rest (Media3 trim optimization). */
    SMART,
    /** Full re-encode with exact cut points. */
    PRECISE,
}

/** What a background job does. Every job reads [sourceUri] and writes one new file. */
@Serializable
sealed interface JobSpec {
    val sourceUri: String
    val title: String
    val headers: Map<String, String>
}

@Serializable
@SerialName("clip")
data class ClipJob(
    override val sourceUri: String,
    override val title: String,
    val startMs: Long,
    val endMs: Long,
    val mode: ClipMode = ClipMode.SMART,
    val audioOnly: Boolean = false,
    override val headers: Map<String, String> = emptyMap(),
) : JobSpec

/** Extracts the audio as AAC in M4A; copied without re-encoding when it already is AAC. */
@Serializable
@SerialName("extract")
data class ExtractJob(
    override val sourceUri: String,
    override val title: String,
    override val headers: Map<String, String> = emptyMap(),
) : JobSpec

@Serializable
@SerialName("convert")
data class ConvertJob(
    override val sourceUri: String,
    override val title: String,
    /** MIME type of the output video codec, or null to keep (copy) it when possible. */
    val videoMime: String? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
    val keepAudio: Boolean = true,
    val keepVideo: Boolean = true,
    override val headers: Map<String, String> = emptyMap(),
) : JobSpec

@Serializable
@SerialName("compress")
data class CompressJob(
    override val sourceUri: String,
    override val title: String,
    val videoBitrate: Int,
    val height: Int? = null,
    override val headers: Map<String, String> = emptyMap(),
) : JobSpec

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class JobState(
    val id: String,
    val spec: JobSpec,
    val status: JobStatus = JobStatus.QUEUED,
    /** 0..100, or -1 when unknown. */
    val progress: Int = -1,
    val outputUri: String? = null,
    val outputName: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}

/** The jobs list: running and queued jobs, then the last finished ones (kept across restarts). */
object JobsRepository {
    private const val KEEP = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val _jobs = MutableStateFlow<List<JobState>>(emptyList())
    val jobs: StateFlow<List<JobState>> = _jobs.asStateFlow()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "tool_jobs.json")
        file = f
        val saved = runCatching { json.decodeFromString<List<JobState>>(f.readText()) }.getOrDefault(emptyList())
        // Jobs that were running when the app was killed can't resume.
        _jobs.value = saved.map { if (it.active) it.copy(status = JobStatus.FAILED, error = "interrupted") else it }
    }

    fun add(spec: JobSpec): JobState {
        val job = JobState(UUID.randomUUID().toString(), spec)
        _jobs.update { listOf(job) + it }
        persist()
        return job
    }

    fun update(id: String, change: (JobState) -> JobState) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
        val job = _jobs.value.firstOrNull { it.id == id }
        // Progress ticks aren't written to disk; state changes are.
        if (job != null && job.status != JobStatus.RUNNING) persist()
    }

    fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id && !it.active } }
        persist()
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.active } }
        persist()
    }

    fun next(): JobState? = _jobs.value.lastOrNull { it.status == JobStatus.QUEUED }

    fun get(id: String): JobState? = _jobs.value.firstOrNull { it.id == id }

    private fun persist() {
        val f = file ?: return
        val list = _jobs.value
        val keep = list.filter { it.active } + list.filterNot { it.active }.take(KEEP)
        runCatching { f.writeText(json.encodeToString(keep)) }
    }
}
