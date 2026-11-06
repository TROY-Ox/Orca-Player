package com.troy.orcaplayer.downloads.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DownloadState(
    val items: List<DownloadItem> = emptyList(),
    val config: DownloadConfig = DownloadConfig(),
    val smart: List<SmartSeries> = emptyList(),
    /** When the queue started waiting for the start time, or 0. */
    val scheduleWaitingSince: Long = 0,
    /** Episodes watched to the end, so smart downloads never fetch them again. */
    val watched: Set<String> = emptySet(),
)

/** Live numbers that change many times a second and aren't saved. */
data class LiveStats(val speed: Long = 0)

/**
 * The download list, kept in one JSON file so it survives app kills and reboots. Progress is
 * written at most every few seconds; state changes are written at once.
 */
class DownloadStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _live = MutableStateFlow<Map<String, LiveStats>>(emptyMap())
    val live: StateFlow<Map<String, LiveStats>> = _live.asStateFlow()

    private var lastWrite = 0L

    private fun load(): DownloadState {
        val saved = runCatching { json.decodeFromString<DownloadState>(file.readText()) }.getOrDefault(DownloadState())
        // Downloads that were running when the app stopped go back to the queue and resume.
        return saved.copy(items = saved.items.map { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.QUEUED) else it })
    }

    val items: List<DownloadItem> get() = _state.value.items
    val config: DownloadConfig get() = _state.value.config

    fun get(id: String): DownloadItem? = _state.value.items.firstOrNull { it.id == id }

    @Synchronized
    fun add(item: DownloadItem): DownloadItem {
        val placed = item.copy(order = Queue.nextOrder(_state.value.items), createdAt = item.createdAt.takeIf { it > 0 } ?: clock())
        _state.update { s ->
            val waiting = if (s.config.startMinute != null && !placed.startNow && s.scheduleWaitingSince == 0L) clock() else s.scheduleWaitingSince
            s.copy(items = s.items + placed, scheduleWaitingSince = waiting)
        }
        persist(force = true)
        return placed
    }

    /** Changes one item. Progress-only changes are saved lazily unless [force]. */
    @Synchronized
    fun update(id: String, force: Boolean = true, change: (DownloadItem) -> DownloadItem) {
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) change(it) else it }) }
        persist(force)
    }

    @Synchronized
    fun updateAll(change: (DownloadItem) -> DownloadItem) {
        _state.update { s -> s.copy(items = s.items.map(change)) }
        persist(force = true)
    }

    @Synchronized
    fun remove(id: String) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
        _live.update { it - id }
        persist(force = true)
    }

    @Synchronized
    fun move(id: String, toIndex: Int) {
        val orders = Queue.reorder(_state.value.items, id, toIndex)
        if (orders.isEmpty()) return
        _state.update { s -> s.copy(items = s.items.map { orders[it.id]?.let { o -> it.copy(order = o) } ?: it }) }
        persist(force = true)
    }

    @Synchronized
    fun setConfig(config: DownloadConfig) {
        _state.update { s ->
            val waiting = when {
                config.startMinute == null -> 0L
                s.scheduleWaitingSince == 0L && s.items.any { it.active && !it.startNow } -> clock()
                config.startMinute != s.config.startMinute && s.scheduleWaitingSince != 0L -> clock()
                else -> s.scheduleWaitingSince
            }
            s.copy(config = config, scheduleWaitingSince = waiting)
        }
        persist(force = true)
    }

    @Synchronized
    fun setScheduleWaiting(since: Long) {
        _state.update { it.copy(scheduleWaitingSince = since) }
        persist(force = true)
    }

    @Synchronized
    fun setSmart(series: SmartSeries, on: Boolean) {
        _state.update { s ->
            val rest = s.smart.filterNot { it.seriesKey == series.seriesKey && it.profileId == series.profileId }
            s.copy(smart = if (on) rest + series else rest)
        }
        persist(force = true)
    }

    fun smartFor(seriesKey: String, profileId: String): SmartSeries? =
        _state.value.smart.firstOrNull { it.seriesKey == seriesKey && it.profileId == profileId }

    @Synchronized
    fun markWatched(mediaKeyId: String) {
        if (mediaKeyId in _state.value.watched) return
        _state.update { s -> s.copy(watched = (s.watched + mediaKeyId).let { if (it.size > 2000) it.drop(it.size - 2000).toSet() else it }) }
        persist(force = true)
    }

    fun setSpeed(id: String, speed: Long) {
        _live.update { it + (id to LiveStats(speed)) }
    }

    fun clearSpeed(id: String) {
        _live.update { it - id }
    }

    /** Writes the list now, for example before the process may be stopped. */
    fun flush() = persist(force = true)

    @Synchronized
    private fun persist(force: Boolean) {
        val now = clock()
        if (!force && now - lastWrite < 3_000) return
        lastWrite = now
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(DownloadState.serializer(), _state.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
