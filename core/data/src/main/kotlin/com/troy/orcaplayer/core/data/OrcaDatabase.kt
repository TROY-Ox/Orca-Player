package com.troy.orcaplayer.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, ResumePositionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OrcaDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun resume(): ResumeDao

    companion object {
        const val NAME = "orca.db"

        fun create(context: Context): OrcaDatabase =
            Room.databaseBuilder(context.applicationContext, OrcaDatabase::class.java, NAME).build()
    }
}
