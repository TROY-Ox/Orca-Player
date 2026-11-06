package com.troy.orcaplayer.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.troy.orcaplayer.core.model.KidsRules
import com.troy.orcaplayer.core.model.Profile
import kotlinx.serialization.json.Json

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
    /** [KidsRules] as JSON. */
    val kidsRules: String? = null,
) {
    fun toModel() = Profile(
        id, name, avatar, isKids, pinHash, pinSalt, createdAt,
        kids = kidsRules?.let { runCatching { json.decodeFromString(KidsRules.serializer(), it) }.getOrNull() } ?: KidsRules(),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(p: Profile, sortOrder: Int = 0) =
            ProfileEntity(p.id, p.name, p.avatar, p.isKids, p.pinHash, p.pinSalt, p.createdAt, sortOrder, json.encodeToString(KidsRules.serializer(), p.kids))
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
    /** What Continue Watching needs to show and reopen it (17.5). */
    val sourceKind: String? = null,
    val titleType: String? = null,
    val titleId: String? = null,
    val episodeId: String? = null,
    val subtitle: String? = null,
    val poster: String? = null,
    /** "Remove from row": hidden from Continue Watching until it is played again. */
    val hidden: Boolean = false,
)

/** My List: one favourites list per profile for every source and the library (17.4). */
@Entity(
    tableName = "my_list",
    primaryKeys = ["profileId", "itemKey"],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId")],
)
data class MyListEntity(
    val profileId: String,
    val itemKey: String,
    val name: String,
    /** A Title's type, or null for a file or link. */
    val titleType: String? = null,
    val titleId: String? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val year: Int? = null,
    /** For files and links: the URI to play. */
    val uri: String? = null,
    val addedAt: Long,
    val sortOrder: Int = 0,
    /** Episodes known at the last new-episode check (17.8); -1 when never checked. */
    val knownEpisodes: Int = -1,
    val lastEpisodeId: String? = null,
)

/** Watch time per profile and day, for the Kids daily limit (18). */
@Entity(
    tableName = "watch_time",
    primaryKeys = ["profileId", "day"],
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
)
data class WatchTimeEntity(val profileId: String, val day: String, val ms: Long)

/** A skip range the user marked once for a series ("Mark intro", 17.6). */
@Entity(tableName = "skip_marks", primaryKeys = ["seriesKey", "kind"])
data class SkipMarkEntity(val seriesKey: String, val kind: String, val startMs: Long, val endMs: Long, val updatedAt: Long)
