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
    fun profiles(db: OrcaDatabase, @ApplicationContext context: Context): ProfileRepository = ProfileRepository(db, context)

    @Provides
    @Singleton
    fun resume(db: OrcaDatabase): ResumeRepository = ResumeRepository(db)

    @Provides
    @Singleton
    fun myList(db: OrcaDatabase): com.troy.orcaplayer.core.data.MyListRepository = com.troy.orcaplayer.core.data.MyListRepository(db)

    @Provides
    @Singleton
    fun playbackMarks(db: OrcaDatabase): com.troy.orcaplayer.core.data.PlaybackMarksRepository = com.troy.orcaplayer.core.data.PlaybackMarksRepository(db)

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
    fun library(@ApplicationContext context: Context, db: OrcaDatabase): com.troy.orcaplayer.library.Library =
        com.troy.orcaplayer.library.Library(context, db.library())

    @Provides
    @Singleton
    fun backup(@ApplicationContext context: Context, db: OrcaDatabase, settings: SettingsRepository): com.troy.orcaplayer.backup.BackupManager =
        com.troy.orcaplayer.backup.BackupManager(context, db, settings)

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
    fun liveTv(@ApplicationContext context: Context): com.troy.orcaplayer.data.LiveTvRepository = com.troy.orcaplayer.data.LiveTvRepository(context)

    @Provides
    @Singleton
    fun hub(addons: AddonRepository, profiles: ProfileRepository): ContentHub = ContentHub(addons, profiles)

    @Provides
    @Singleton
    fun onlineSubtitles(@ApplicationContext context: Context): OnlineSubtitles = OnlineSubtitles(context.cacheDir)

    @Provides
    @Singleton
    fun downloadPlanner(@ApplicationContext context: Context, hub: ContentHub): com.troy.orcaplayer.data.DownloadPlanner =
        com.troy.orcaplayer.data.DownloadPlanner(context, hub)
}
