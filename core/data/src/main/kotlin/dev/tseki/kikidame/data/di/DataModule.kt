package dev.tseki.kikidame.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.tseki.kikidame.data.db.EpisodeDao
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.LocalFileDao
import dev.tseki.kikidame.data.db.ProgramDao
import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.SdkJellyfinGateway
import dev.tseki.kikidame.data.repository.DataStoreSessionRepository
import dev.tseki.kikidame.data.repository.RoomDownloadRepository
import dev.tseki.kikidame.data.repository.RoomLibraryRefreshRepository
import dev.tseki.kikidame.data.repository.RoomLibraryRepository
import dev.tseki.kikidame.data.repository.RoomLocalDataReset
import dev.tseki.kikidame.data.repository.RoomPlaybackStateRepository
import dev.tseki.kikidame.data.session.KeystoreTokenCipher
import dev.tseki.kikidame.data.settings.DataStoreAppSettings
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.session.TokenCipher
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalDataReset
import dev.tseki.kikidame.domain.PlaybackStateRepository
import dev.tseki.kikidame.domain.SessionRepository
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KikidameDatabase =
        Room.databaseBuilder(context, KikidameDatabase::class.java, "kikidame.db").build()

    @Provides
    fun provideProgramDao(db: KikidameDatabase): ProgramDao = db.programDao()

    @Provides
    fun provideEpisodeDao(db: KikidameDatabase): EpisodeDao = db.episodeDao()

    @Provides
    fun provideLocalFileDao(db: KikidameDatabase): LocalFileDao = db.localFileDao()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System

    @Provides
    @Singleton
    @SessionPreferences
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") }

    @Provides
    @Singleton
    fun provideSessionStore(@SessionPreferences dataStore: DataStore<Preferences>, cipher: TokenCipher): SessionStore =
        SessionStore(dataStore, cipher)
    @Provides
    @Singleton
    @SettingsPreferences
    fun provideSettingsPreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    @Provides
    @Singleton
    fun provideAppSettings(@SettingsPreferences dataStore: DataStore<Preferences>): AppSettingsRepository =
        DataStoreAppSettings(dataStore)
}
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SessionPreferences
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingsPreferences

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindLibraryRepository(impl: RoomLibraryRepository): LibraryRepository

    @Binds
    abstract fun bindPlaybackStateRepository(impl: RoomPlaybackStateRepository): PlaybackStateRepository

    @Binds
    abstract fun bindSessionRepository(impl: DataStoreSessionRepository): SessionRepository

    @Binds
    abstract fun bindLibraryRefreshRepository(impl: RoomLibraryRefreshRepository): LibraryRefreshRepository

    @Binds
    abstract fun bindLocalDataReset(impl: RoomLocalDataReset): LocalDataReset

    @Binds
    abstract fun bindJellyfinGateway(impl: SdkJellyfinGateway): JellyfinGateway

    @Binds
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher
    @Binds
    abstract fun bindDownloadRepository(impl: RoomDownloadRepository): DownloadRepository
    @Binds
    abstract fun bindDownloadQueue(impl: RoomDownloadRepository): DownloadQueue
}
