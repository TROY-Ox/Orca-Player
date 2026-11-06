package com.troy.orcaplayer.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, ResumePositionEntity::class, MyListEntity::class, WatchTimeEntity::class, SkipMarkEntity::class,
        LibraryItemEntity::class, LibraryShowEntity::class, LibraryWatchedEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class OrcaDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun resume(): ResumeDao
    abstract fun myList(): MyListDao
    abstract fun watchTime(): WatchTimeDao
    abstract fun skipMarks(): SkipMarkDao
    abstract fun library(): LibraryDao

    companion object {
        const val NAME = "orca.db"

        /** Phase 7: Kids rules, Continue Watching details, My List, watch time and skip marks. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN kidsRules TEXT")
                listOf("sourceKind", "titleType", "titleId", "episodeId", "subtitle", "poster").forEach {
                    db.execSQL("ALTER TABLE resume_positions ADD COLUMN $it TEXT")
                }
                db.execSQL("ALTER TABLE resume_positions ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS my_list (profileId TEXT NOT NULL, itemKey TEXT NOT NULL, name TEXT NOT NULL, " +
                        "titleType TEXT, titleId TEXT, sourceId TEXT, sourceName TEXT, poster TEXT, background TEXT, year INTEGER, uri TEXT, " +
                        "addedAt INTEGER NOT NULL, sortOrder INTEGER NOT NULL, knownEpisodes INTEGER NOT NULL, lastEpisodeId TEXT, " +
                        "PRIMARY KEY(profileId, itemKey), FOREIGN KEY(profileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_my_list_profileId ON my_list (profileId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_time (profileId TEXT NOT NULL, day TEXT NOT NULL, ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(profileId, day), FOREIGN KEY(profileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skip_marks (seriesKey TEXT NOT NULL, kind TEXT NOT NULL, startMs INTEGER NOT NULL, " +
                        "endMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(seriesKey, kind))",
                )
            }
        }

        /** Phase 9: the media library. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS library_items (id TEXT NOT NULL, uri TEXT NOT NULL, fileName TEXT NOT NULL, folder TEXT NOT NULL, " +
                        "origin TEXT NOT NULL, sizeBytes INTEGER NOT NULL, durationMs INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, " +
                        "year INTEGER, season INTEGER, episode INTEGER, showKey TEXT, tmdbId INTEGER, overview TEXT, poster TEXT, backdrop TEXT, " +
                        "rating REAL, genres TEXT, episodeTitle TEXT, still TEXT, collectionId INTEGER, collectionName TEXT, imdbId TEXT, " +
                        "manual INTEGER NOT NULL, matchedAt INTEGER NOT NULL, addedAt INTEGER NOT NULL, seenAt INTEGER NOT NULL, PRIMARY KEY(id))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_items_showKey ON library_items (showKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_items_origin ON library_items (origin)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS library_shows (showKey TEXT NOT NULL, name TEXT NOT NULL, year INTEGER, tmdbId INTEGER, " +
                        "overview TEXT, poster TEXT, backdrop TEXT, rating REAL, genres TEXT, manual INTEGER NOT NULL, matchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(showKey))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS library_watched (profileId TEXT NOT NULL, itemId TEXT NOT NULL, at INTEGER NOT NULL, " +
                        "PRIMARY KEY(profileId, itemId), FOREIGN KEY(profileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_watched_profileId ON library_watched (profileId)")
            }
        }

        fun create(context: Context): OrcaDatabase =
            Room.databaseBuilder(context.applicationContext, OrcaDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
