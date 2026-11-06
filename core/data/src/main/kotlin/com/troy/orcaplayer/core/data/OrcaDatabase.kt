package com.troy.orcaplayer.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, ResumePositionEntity::class, MyListEntity::class, WatchTimeEntity::class, SkipMarkEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class OrcaDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun resume(): ResumeDao
    abstract fun myList(): MyListDao
    abstract fun watchTime(): WatchTimeDao
    abstract fun skipMarks(): SkipMarkDao

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

        fun create(context: Context): OrcaDatabase =
            Room.databaseBuilder(context.applicationContext, OrcaDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
