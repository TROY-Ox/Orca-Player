package com.troy.orcaplayer.core.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * Limits for a Kids profile (18). An empty set means "no limit of that kind"; sources marked
 * adult are always excluded, whatever is chosen here.
 */
@Serializable
data class KidsRules(
    /** "addonId|catalogKey" of the catalogs allowed on Home and Discover. */
    val catalogs: Set<String> = emptySet(),
    /** Local folder IDs allowed in the Local tab. */
    val folders: Set<String> = emptySet(),
    /** "TYPE/id" of single titles allowed. When set, other titles are hidden from rows. */
    val titles: Set<String> = emptySet(),
    /** Daily watch-time limit in minutes; 0 is no limit. */
    val dailyLimitMinutes: Int = 0,
    /** Keep the app pinned to the screen while this profile is active (Android screen pinning). */
    val screenPinning: Boolean = false,
) {
    fun allowsCatalog(key: String): Boolean = catalogs.isEmpty() || key in catalogs
    fun allowsFolder(id: String?): Boolean = folders.isEmpty() || (id != null && id in folders)
    fun allowsTitle(type: String, id: String): Boolean = titles.isEmpty() || "$type/$id" in titles
}

/** PIN hashing and the slow-down after wrong attempts (18). The PIN itself is never stored. */
object PinLock {
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun isValid(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun newSalt(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.toHex()
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.hexToBytes(), ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time compare, so the time taken doesn't hint at how much of the PIN was right. */
    fun verify(pin: String, hash: String?, salt: String?): Boolean {
        if (hash == null || salt == null) return true
        val a = hash(pin, salt)
        if (a.length != hash.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor hash[i].code)
        return diff == 0
    }

    /** How long to wait before the next try: free for 3 tries, then 30 s doubling up to 15 minutes. */
    fun lockoutMs(failures: Int): Long {
        if (failures < 3) return 0
        val shift = min(failures - 3, 5)
        return min(30_000L shl shift, 15 * 60_000L)
    }

    /** Milliseconds still to wait, or 0. */
    fun waitLeft(failures: Int, lastFailureAt: Long, now: Long): Long = max(0, lastFailureAt + lockoutMs(failures) - now)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Daily watch time for Kids profiles. */
object WatchLimit {
    /** Milliseconds left today, or null when there is no limit. */
    fun remainingMs(limitMinutes: Int, usedMs: Long): Long? =
        if (limitMinutes <= 0) null else max(0, limitMinutes * 60_000L - usedMs)

    fun reached(limitMinutes: Int, usedMs: Long): Boolean = remainingMs(limitMinutes, usedMs) == 0L
}

enum class SkipKind { INTRO, RECAP, CREDITS }

@Serializable
data class SkipRange(val kind: SkipKind, val startMs: Long, val endMs: Long) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** Skip Intro, Skip Recap and Skip Credits (17.6). */
object SkipMarks {
    private val intro = Regex("""(?i)\b(intro|opening|op|theme song|title sequence)\b""")
    private val recap = Regex("""(?i)\b(recap|previously|summary)\b""")
    private val credits = Regex("""(?i)\b(credits|ending|ed|outro|end titles)\b""")

    /** What a chapter named [name] is, if it is one of the three. */
    fun kindOfChapter(name: String): SkipKind? = when {
        recap.containsMatchIn(name) -> SkipKind.RECAP
        credits.containsMatchIn(name) -> SkipKind.CREDITS
        intro.containsMatchIn(name) -> SkipKind.INTRO
        else -> null
    }

    /** Ranges from chapter starts: each chapter ends where the next one begins. */
    fun fromChapters(chapters: List<Pair<String, Long>>, durationMs: Long): List<SkipRange> {
        val sorted = chapters.sortedBy { it.second }
        return sorted.mapIndexedNotNull { i, (name, start) ->
            val kind = kindOfChapter(name) ?: return@mapIndexedNotNull null
            val end = sorted.getOrNull(i + 1)?.second ?: durationMs
            if (end > start) SkipRange(kind, start, end) else null
        }
    }

    /** The range to offer a skip button for at [positionMs]. Chapters win over a user mark. */
    fun active(positionMs: Long, vararg sources: List<SkipRange>): SkipRange? =
        sources.asSequence().flatMap { it.asSequence() }.firstOrNull { it.contains(positionMs) && it.endMs - positionMs > 1_000 }

    /** A mark the user set: the start must come first and the range must be at least 5 seconds. */
    fun mark(kind: SkipKind, startMs: Long, endMs: Long): SkipRange? =
        if (endMs - startMs >= 5_000) SkipRange(kind, startMs, endMs) else null
}

/** When the Next Episode button and the play-next countdown appear (17.6). */
object NextUp {
    /** The position the button appears at: the start of the credits, or near the end. */
    fun showAtMs(durationMs: Long, creditsStartMs: Long?, countdownSeconds: Int): Long {
        if (durationMs <= 0) return Long.MAX_VALUE
        val lead = max(countdownSeconds * 1000L, 15_000L)
        val near = max(0, durationMs - lead)
        return creditsStartMs?.takeIf { it in 1 until durationMs } ?: near
    }

    fun secondsLeft(positionMs: Long, startedAtMs: Long, countdownSeconds: Int): Int =
        max(0, countdownSeconds - ((positionMs - startedAtMs) / 1000).toInt())

    /**
     * The episode after [currentId] in season and episode order. Specials (season 0) are only
     * followed by more specials, and regular seasons never lead into them.
     */
    fun nextEpisode(episodes: List<Episode>, currentId: String): Episode? {
        val current = episodes.firstOrNull { it.id == currentId } ?: return null
        val special = (current.season ?: 1) == 0
        val ordered = episodes.filter { ((it.season ?: 1) == 0) == special }
            .sortedWith(compareBy<Episode>({ it.season ?: 1 }, { it.number ?: Int.MAX_VALUE }))
        val i = ordered.indexOfFirst { it.id == currentId }
        return ordered.getOrNull(i + 1)
    }
}

/** Which resume positions belong on the Continue Watching row (17.5). */
object ContinueWatching {
    /** Started (past 10 s) but not watched to the threshold. */
    fun shouldShow(positionMs: Long, durationMs: Long, watchedThreshold: Double): Boolean =
        durationMs > 0 && positionMs > 10_000 && positionMs < durationMs * watchedThreshold

    fun progress(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** The item key My List uses: one entry per title whatever source it came from (17.4). */
object MyListKeys {
    fun forTitle(type: TitleType, id: String): String = "title:${type.name}/$id"
    fun forMedia(key: MediaKey): String = "media:${key.id}"
}
