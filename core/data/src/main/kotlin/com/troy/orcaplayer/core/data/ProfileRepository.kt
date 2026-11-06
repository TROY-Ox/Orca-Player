package com.troy.orcaplayer.core.data

import com.troy.orcaplayer.core.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class ProfileRepository(private val db: OrcaDatabase) {
    private val _current = MutableStateFlow(Profile.DEFAULT_ID)

    /** The active profile ID. Phase 1 always has the one default profile. */
    val currentProfileId: StateFlow<String> = _current.asStateFlow()

    val profiles: Flow<List<Profile>> = db.profiles().observeAll().map { list -> list.map { it.toModel() } }

    /** Phase 1 creates one default profile on first start. */
    suspend fun ensureDefaultProfile(defaultName: String) {
        db.profiles().insertIfMissing(
            ProfileEntity.from(Profile(Profile.DEFAULT_ID, defaultName, createdAt = System.currentTimeMillis())),
        )
    }

    suspend fun switchTo(profileId: String) {
        if (db.profiles().get(profileId) != null) _current.value = profileId
    }
}

class ResumeRepository(private val db: OrcaDatabase) {
    suspend fun positionFor(profileId: String, mediaKey: String): ResumePositionEntity? = db.resume().get(profileId, mediaKey)

    fun recent(profileId: String) = db.resume().observeRecent(profileId, 30)

    suspend fun save(position: ResumePositionEntity) = db.resume().upsert(position)

    suspend fun clear(profileId: String, mediaKey: String) = db.resume().delete(profileId, mediaKey)
}
