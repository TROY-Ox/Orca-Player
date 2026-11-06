package com.troy.orcaplayer.di

import android.content.Context
import com.troy.orcaplayer.core.data.OrcaDatabase
import com.troy.orcaplayer.core.data.ProfileRepository
import com.troy.orcaplayer.core.data.ResumeRepository
import com.troy.orcaplayer.core.settings.SettingsRepository
import com.troy.orcaplayer.data.AddonRepository
import com.troy.orcaplayer.data.ContentHub
import com.troy.orcaplayer.data.LinksRepository
import com.troy.orcaplayer.data.OnlineSubtitles
import com.troy.orcaplayer.player.engine.PlaybackEngine
import com.troy.orcaplayer.sources.local.LocalMediaRepository
import com.troy.orcaplayer.sources.local.Thumbnails
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

    @Provides
    @Singleton
    fun engine(@ApplicationContext context: Context): PlaybackEngine = PlaybackEngine.get(context)

    @Provides
    @Singleton
    fun localMedia(@ApplicationContext context: Context): LocalMediaRepository = LocalMediaRepository(context)

    @Provides
    @Singleton
    fun thumbnails(@ApplicationContext context: Context): Thumbnails = Thumbnails(context)

    @Provides
    @Singleton
    fun addons(@ApplicationContext context: Context): AddonRepository = AddonRepository(context)

    @Provides
    @Singleton
    fun links(@ApplicationContext context: Context): LinksRepository = LinksRepository(context)

    @Provides
    @Singleton
    fun hub(addons: AddonRepository, profiles: ProfileRepository): ContentHub = ContentHub(addons, profiles)

    @Provides
    @Singleton
    fun onlineSubtitles(@ApplicationContext context: Context): OnlineSubtitles = OnlineSubtitles(context.cacheDir)
}
