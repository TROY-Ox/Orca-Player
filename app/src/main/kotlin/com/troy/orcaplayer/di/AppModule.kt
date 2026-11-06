package com.troy.orcaplayer.di

import android.content.Context
import com.troy.orcaplayer.core.data.OrcaDatabase
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): OrcaDatabase = OrcaDatabase.create(context)

    @Provides
    @Singleton
    fun profiles(db: OrcaDatabase): ProfileRepository = ProfileRepository(db)

    @Provides
    @Singleton
    fun resume(db: OrcaDatabase): ResumeRepository = ResumeRepository(db)

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): SettingsRepository = SettingsRepository(context)
}
