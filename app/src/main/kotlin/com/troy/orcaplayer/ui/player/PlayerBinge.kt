package com.troy.orcaplayer.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import com.troy.orcaplayer.core.data.PlaybackMarksRepository
import com.troy.orcaplayer.core.model.NextUp
import com.troy.orcaplayer.core.model.PlayItem
import com.troy.orcaplayer.core.model.Profile
import com.troy.orcaplayer.core.model.SkipKind
import com.troy.orcaplayer.core.model.SkipMarks
import com.troy.orcaplayer.core.model.SkipRange
import com.troy.orcaplayer.core.model.TitleType
import com.troy.orcaplayer.core.model.WatchLimit
import com.troy.orcaplayer.core.settings.PlayerSettings
import com.troy.orcaplayer.core.settings.SettingsValues
import com.troy.orcaplayer.core.settings.SourceSettings
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.sources.api.StreamSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A "Skip intro" style button for the range playing now. */
data class SkipOffer(val kind: SkipKind, val endMs: Long)

/** The Next Episode button; [secondsLeft] is the countdown, or null for a plain button. */
data class NextOffer(val label: String, val secondsLeft: Int?)

/** A mark being set: the start is known, the end comes with the next tap. */
data class MarkInProgress(val kind: SkipKind, val startMs: Long)

/**
 * Skip buttons, the Next Episode button and countdown (17.6), and the Kids daily watch limit (18).
 * Driven by the player's 250 ms tick.
 */
class PlayerBinge(
    private val scope: CoroutineScope,
    /** For saves, which must finish even after the player screen closes. */
    private val io: CoroutineScope,
    private val engine: PlaybackEngine,
    private val hub: ContentHub,
    private val marks: PlaybackMarksRepository,
) {
    private val _skip = MutableStateFlow<SkipOffer?>(null)
    val skip: StateFlow<SkipOffer?> = _skip.asStateFlow()
    private val _next = MutableStateFlow<NextOffer?>(null)
    val next: StateFlow<NextOffer?> = _next.asStateFlow()
    private val _marking = MutableStateFlow<MarkInProgress?>(null)
    val marking: StateFlow<MarkInProgress?> = _marking.asStateFlow()
    private val _limitReached = MutableStateFlow(false)
    val limitReached: StateFlow<Boolean> = _limitReached.asStateFlow()

    private var ranges: List<SkipRange> = emptyList()
    private var seriesKey: String? = null
    private var index = -1
    private var dismissedFor = -1
    private var countdownStart: Long? = null
    private var resolved: PlayItem? = null
    private var resolving: Job? = null
    private var appended = false

    private var lastTick = 0L
    private var unsaved = 0L
    private var todayMs = 0L
    private var todayFor: String? = null
    private var dayLoaded: String? = null
    private var limitOverridden = false

    val canMark: Boolean get() = seriesKey != null
    val hasMarks: Boolean get() = ranges.isNotEmpty()

    fun onItem(item: PlayItem?, newIndex: Int) {
        index = newIndex
        _skip.value = null
        _next.value = null
        _marking.value = null
        countdownStart = null
        resolved = null
        appended = false
        resolving?.cancel()
        ranges = emptyList()
        seriesKey = item?.episode?.titleId?.let { "series:$it" }
        val key = seriesKey ?: return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { marks.skipMarks(key) }.getOrDefault(emptyList()) }
            if (seriesKey == key) {
                ranges = loaded.mapNotNull { m -> runCatching { SkipRange(SkipKind.valueOf(m.kind), m.startMs, m.endMs) }.getOrNull() }
            }
        }
    }

    fun tick(p: Player, item: PlayItem?, v: SettingsValues, profile: Profile?) {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 0 else (now - lastTick).coerceIn(0, 1_000)
        lastTick = now
        if (profile != null) trackWatchTime(p, dt, profile)
        if (item == null || p.isCurrentMediaItemLive) {
            _skip.value = null
            _next.value = null
            return
        }
        val pos = p.currentPosition
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return

        _skip.value = if (v[PlayerSettings.SkipButtons]) SkipMarks.active(pos, ranges)?.let { SkipOffer(it.kind, it.endMs) } else null

        val countdown = v[PlayerSettings.NextCountdown].toIntOrNull() ?: 0
        val credits = ranges.firstOrNull { it.kind == SkipKind.CREDITS }?.startMs
        val showAt = NextUp.showAtMs(duration, credits, countdown)
        if (pos < showAt || dismissedFor == index) {
            _next.value = null
            countdownStart = null
            return
        }
        val queued = p.hasNextMediaItem()
        if (!queued && resolved == null) {
            resolveNext(item, v, profile?.id)
            _next.value = null
            return
        }
        val nextItem = if (queued) engine.itemAt(p.nextMediaItemIndex) else resolved
        val label = listOfNotNull(nextItem?.subtitle, nextItem?.title).firstOrNull() ?: ""
        val auto = v[PlayerSettings.AutoNext] && countdown > 0
        if (auto && countdownStart == null) countdownStart = pos
        val left = if (auto) NextUp.secondsLeft(pos, countdownStart ?: pos, countdown) else null
        _next.value = NextOffer(label, left)
        if (left == 0 && p.isPlaying) playNext(p)
    }

    /** At the end of the queue: play the episode found meanwhile, when auto play is on. */
    fun onEnded(p: Player, v: SettingsValues): Boolean {
        if (!v[PlayerSettings.AutoNext] || resolved == null || dismissedFor == index) return false
        playNext(p)
        return true
    }

    fun playNext(p: Player) {
        _next.value = null
        countdownStart = null
        when {
            p.hasNextMediaItem() -> p.seekToNextMediaItem()
            resolved != null && !appended -> {
                appended = true
                engine.append(resolved!!)
                p.seekToNextMediaItem()
                p.playWhenReady = true
            }
        }
    }

    fun dismissNext() {
        dismissedFor = index
        _next.value = null
        countdownStart = null
    }

    fun skip(p: Player) {
        val s = _skip.value ?: return
        p.seekTo(s.endMs)
        _skip.value = null
    }

    fun startMark(kind: SkipKind, positionMs: Long) {
        _marking.value = MarkInProgress(kind, positionMs)
    }

    /** Saves the mark for every episode of the series. Returns false when the range is too short. */
    fun endMark(positionMs: Long): Boolean {
        val m = _marking.value ?: return false
        _marking.value = null
        val range = SkipMarks.mark(m.kind, m.startMs, positionMs) ?: return false
        val key = seriesKey ?: return false
        ranges = ranges.filter { it.kind != m.kind } + range
        io.launch { runCatching { marks.setSkipMark(key, m.kind.name, range.startMs, range.endMs) } }
        return true
    }

    fun cancelMark() {
        _marking.value = null
    }

    fun clearMarks() {
        val key = seriesKey ?: return
        ranges = emptyList()
        io.launch { SkipKind.entries.forEach { runCatching { marks.clearSkipMark(key, it.name) } } }
    }

    private fun resolveNext(item: PlayItem, v: SettingsValues, profileId: String?) {
        if (resolving?.isActive == true || profileId == null) return
        val target = hub.targetFor(item.key.id) ?: return
        val ep = target.episode ?: return
        val forIndex = index
        resolving = scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val title = hub.details(TitleType.SERIES, ep.titleId, null) ?: return@runCatching null
                    val nextEp = NextUp.nextEpisode(title.episodes, ep.episodeId) ?: return@runCatching null
                    var entries = emptyList<com.troy.orcaplayer.data.StreamEntry>()
                    hub.streams(TitleType.SERIES, nextEp.id, com.troy.orcaplayer.data.altStreamIds(title.imdbId, nextEp.season, nextEp.number)).collect { entries = it.entries }
                    val sort = runCatching { StreamSort.valueOf(v[SourceSettings.StreamSort].uppercase()) }.getOrDefault(StreamSort.QUALITY)
                    val ranked = hub.rank(entries.filter { it.playable }, sort, null, null)
                    val direct = target.bingeGroup?.let { g -> ranked.firstOrNull { it.option.bingeGroup == g } } ?: ranked.firstOrNull()
                    // No direct link: a torrent stream, when the torrent streamer is there and its notice was accepted.
                    val chosen = direct ?: run {
                        val resolver = com.troy.orcaplayer.data.TorrentBridge.resolver?.takeIf { !it.needsNotice() } ?: return@run null
                        val torrents = hub.rank(entries.filter { it.viaTorrent }, sort, null, null)
                        val t = target.bingeGroup?.let { g -> torrents.firstOrNull { it.option.bingeGroup == g } } ?: torrents.firstOrNull() ?: return@run null
                        val url = resolver.resolve(t.option)
                        t.copy(option = t.option.copy(url = url, kind = com.troy.orcaplayer.core.model.StreamKind.URL, downloadable = false, mimeType = null))
                    } ?: return@runCatching null
                    hub.request(
                        title, nextEp, chosen, ranked, profileId,
                        SourceSettings.parseHeaders(v[SourceSettings.DefaultHeaders]),
                        (v[SourceSettings.FallbackRetries].toIntOrNull() ?: 3) + 2,
                    ).items.first()
                }.getOrNull()
            }
            if (index == forIndex) resolved = found
        }
    }

    private fun trackWatchTime(p: Player, dt: Long, profile: Profile) {
        val day = marks.today()
        if (todayFor != profile.id || dayLoaded != day) {
            todayFor = profile.id
            dayLoaded = day
            todayMs = 0
            unsaved = 0
            _limitReached.value = false
            io.launch {
                runCatching { marks.pruneWatchTime() }
                val stored = runCatching { marks.watchedToday(profile.id).first() }.getOrDefault(0)
                if (todayFor == profile.id) todayMs += stored
            }
        }
        if (p.isPlaying) {
            todayMs += dt
            unsaved += dt
        }
        if (unsaved >= 30_000 || (!p.isPlaying && unsaved > 0)) flush(profile.id)
        val limit = if (profile.isKids) profile.kids.dailyLimitMinutes else 0
        if (!limitOverridden && WatchLimit.reached(limit, todayMs)) {
            if (!_limitReached.value) {
                _limitReached.value = true
                flush(profile.id)
            }
            if (p.playWhenReady) p.pause()
        }
    }

    /** A parent unlocked: no limit for the rest of this session. */
    fun overrideLimit() {
        limitOverridden = true
        _limitReached.value = false
    }

    fun flush(profileId: String) {
        val ms = unsaved
        if (ms <= 0) return
        unsaved = 0
        io.launch { runCatching { marks.addWatchTime(profileId, ms) } }
    }
}
