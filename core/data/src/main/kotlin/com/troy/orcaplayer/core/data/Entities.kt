package com.troy.orcaplayer.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.troy.orcaplayer.core.model.Profile

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String,
    val isKids: Boolean,
    val pinHash: String?,
    val pinSalt: String?,
    val createdAt: Long,
    val sortOrder: Int,
) {
    fun toModel() = Profile(id, name, avatar, isKids, pinHash, pinSalt, createdAt)

    companion object {
        fun from(p: Profile, sortOrder: Int = 0) =
            ProfileEntity(p.id, p.name, p.avatar, p.isKids, p.pinHash, p.pinSalt, p.createdAt, sortOrder)
    }
}

/** Smart resume. Keyed by profile and media identity key (never a stream URL). */
@Entity(
    tableName = "resume_positions",
    primaryKeys = ["profileId", "mediaKey"],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId"), Index("updatedAt")],
)
data class ResumePositionEntity(
    val profileId: String,
    val mediaKey: String,
    /** Secondary key for local files (the path), so a moved file can still be matched. */
    val path: String?,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)
