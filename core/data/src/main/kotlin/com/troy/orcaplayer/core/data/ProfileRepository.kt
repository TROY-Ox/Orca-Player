package com.troy.orcaplayer.core.data

import android.content.Context
import com.troy.orcaplayer.core.model.KidsRules
import com.troy.orcaplayer.core.model.PinLock
import com.troy.orcaplayer.core.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

/** Profiles (18). The active profile is remembered between starts. */
class ProfileRepository(private val db: OrcaDatabase, context: Context? = null) {
    private val prefs = context?.applicationContext?.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val _current = MutableStateFlow(prefs?.getString(KEY_CURRENT, null) ?: Profile.DEFAULT_ID)

    val currentProfileId: StateFlow<String> = _current.asStateFlow()

    val profiles: Flow<List<Profile>> = db.profiles().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun ensureDefaultProfile(defaultName: String) {
        db.profiles().insertIfMissing(
            ProfileEntity.from(Profile(Profile.DEFAULT_ID, defaultName, createdAt = System.currentTimeMillis())),
        )
        // The remembered profile was deleted elsewhere: fall back to the default one.
        if (db.profiles().get(_current.value) == null) setCurrent(Profile.DEFAULT_ID)
    }

    suspend fun get(id: String): Profile? = db.profiles().get(id)?.toModel()

    suspend fun current(): Profile? = get(_current.value)

    suspend fun switchTo(profileId: String) {
        if (db.profiles().get(profileId) != null) setCurrent(profileId)
    }

    private fun setCurrent(id: String) {
        _current.value = id
        prefs?.edit()?.putString(KEY_CURRENT, id)?.apply()
    }

    suspend fun create(name: String, avatar: String, isKids: Boolean, kids: KidsRules = KidsRules()): Profile {
        val count = db.profiles().count()
        val p = Profile(UUID.randomUUID().toString(), name.trim().ifEmpty { "?" }, avatar, isKids, createdAt = System.currentTimeMillis(), kids = kids)
        db.profiles().upsert(ProfileEntity.from(p, count))
        return p
    }

    suspend fun update(p: Profile) {
        val order = db.profiles().get(p.id)?.sortOrder ?: 0
        db.profiles().upsert(ProfileEntity.from(p, order))
    }

    /** The default profile can't be deleted, so there is always one left. */
    suspend fun delete(id: String) {
        if (id == Profile.DEFAULT_ID) return
        db.profiles().delete(id)
        prefs?.edit()?.remove(failuresKey(id))?.remove(lastFailKey(id))?.apply()
        if (_current.value == id) setCurrent(Profile.DEFAULT_ID)
    }

    suspend fun reorder(ids: List<String>) = ids.forEachIndexed { i, id -> db.profiles().setOrder(id, i) }

    suspend fun setPin(id: String, pin: String?) {
        val p = get(id) ?: return
        val updated = if (pin == null) {
            p.copy(pinHash = null, pinSalt = null)
        } else {
            val salt = PinLock.newSalt()
            p.copy(pinHash = PinLock.hash(pin, salt), pinSalt = salt)
        }
        update(updated)
    }

    // Wrong PIN attempts slow down entry (18). Kept on the device, per profile.

    fun pinWaitLeft(id: String, now: Long = System.currentTimeMillis()): Long =
        PinLock.waitLeft(prefs?.getInt(failuresKey(id), 0) ?: 0, prefs?.getLong(lastFailKey(id), 0) ?: 0, now)

    /** Checks [pin] for [id], counting failures. Returns false while a wait is still running. */
    suspend fun checkPin(id: String, pin: String): Boolean {
        val p = get(id) ?: return false
        if (!p.hasPin) return true
        if (pinWaitLeft(id) > 0) return false
        val ok = PinLock.verify(pin, p.pinHash, p.pinSalt)
        val store = prefs
        if (store != null) {
            val failures = store.getInt(failuresKey(id), 0)
            val e = store.edit()
            if (ok) {
                e.remove(failuresKey(id)).remove(lastFailKey(id))
            } else {
                e.putInt(failuresKey(id), failures + 1).putLong(lastFailKey(id), System.currentTimeMillis())
            }
            e.apply()
        }
        return ok
    }

    /** Any parent (non-Kids) profile's PIN unlocks leaving Kids mode. */
    suspend fun checkParentPin(pin: String): Boolean {
        val parents = profiles.first().filter { !it.isKids && it.hasPin }
        for (p in parents) if (pinWaitLeft(p.id) == 0L && checkPin(p.id, pin)) return true
        return false
    }

    suspend fun parentHasPin(): Boolean = profiles.first().any { !it.isKids && it.hasPin }

    private fun failuresKey(id: String) = "pin_fail_$id"
    private fun lastFailKey(id: String) = "pin_last_$id"

    companion object {
        private const val KEY_CURRENT = "current"
    }
}

class ResumeRepository(private val db: OrcaDatabase) {
    suspend fun positionFor(profileId: String, mediaKey: String): ResumePositionEntity? = db.resume().get(profileId, mediaKey)

    fun recent(profileId: String) = db.resume().observeRecent(profileId, 30)

    /** Continue Watching: what wasn't removed from the row, newest first. */
    fun visible(profileId: String, limit: Int = 40) = db.resume().observeVisible(profileId, limit)

    /** Every saved position of a profile, for progress bars and "new" marks in lists. */
    fun all(profileId: String) = db.resume().observeAll(profileId)

    suspend fun save(position: ResumePositionEntity) = db.resume().upsert(position)

    suspend fun clear(profileId: String, mediaKey: String) = db.resume().delete(profileId, mediaKey)

    suspend fun hide(profileId: String, mediaKey: String) = db.resume().hide(profileId, mediaKey)

    /** Marks something watched: saved at its full length, so it leaves Continue Watching. */
    suspend fun markWatched(profileId: String, mediaKey: String, title: String, durationMs: Long = 1) {
        val old = db.resume().get(profileId, mediaKey)
        val d = old?.durationMs?.takeIf { it > 0 } ?: durationMs
        db.resume().upsert(
            (old ?: ResumePositionEntity(profileId, mediaKey, null, title, 0, d, 0)).copy(positionMs = d, durationMs = d, updatedAt = System.currentTimeMillis()),
        )
    }
}

class MyListRepository(private val db: OrcaDatabase) {
    fun items(profileId: String): Flow<List<MyListEntity>> = db.myList().observe(profileId)

    fun keys(profileId: String): Flow<Set<String>> = items(profileId).map { l -> l.map { it.itemKey }.toSet() }

    /** New items go to the top. */
    suspend fun add(item: MyListEntity) {
        val top = (db.myList().minOrder(item.profileId) ?: 0) - 1
        db.myList().upsert(item.copy(sortOrder = top, addedAt = System.currentTimeMillis()))
    }

    suspend fun remove(profileId: String, key: String) = db.myList().delete(profileId, key)

    suspend fun reorder(profileId: String, keys: List<String>) = keys.forEachIndexed { i, k -> db.myList().setOrder(profileId, k, i) }

    suspend fun everything(): List<MyListEntity> = db.myList().everything()

    suspend fun setKnownEpisodes(profileId: String, key: String, count: Int, last: String?) = db.myList().setKnownEpisodes(profileId, key, count, last)
}

/** Kids watch time and per-series skip marks. */
class PlaybackMarksRepository(private val db: OrcaDatabase) {
    fun today(): String = LocalDate.now().toString()

    fun watchedToday(profileId: String): Flow<Long> = db.watchTime().observe(profileId, today()).map { it?.ms ?: 0 }

    suspend fun addWatchTime(profileId: String, ms: Long) {
        val day = today()
        val old = db.watchTime().get(profileId, day)?.ms ?: 0
        db.watchTime().upsert(WatchTimeEntity(profileId, day, old + ms))
    }

    suspend fun pruneWatchTime() = db.watchTime().prune(LocalDate.now().minusDays(14).toString())

    suspend fun skipMarks(seriesKey: String): List<SkipMarkEntity> = db.skipMarks().forSeries(seriesKey)

    suspend fun setSkipMark(seriesKey: String, kind: String, startMs: Long, endMs: Long) =
        db.skipMarks().upsert(SkipMarkEntity(seriesKey, kind, startMs, endMs, System.currentTimeMillis()))

    suspend fun clearSkipMark(seriesKey: String, kind: String) = db.skipMarks().delete(seriesKey, kind)
}
