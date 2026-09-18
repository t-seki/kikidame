package dev.tseki.jellyfinradio.data.di

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
import dev.tseki.jellyfinradio.data.db.EpisodeDao
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.LocalFileDao
import dev.tseki.jellyfinradio.data.db.ProgramDao
import dev.tseki.jellyfinradio.data.jellyfin.JellyfinGateway
import dev.tseki.jellyfinradio.data.jellyfin.SdkJellyfinGateway
import dev.tseki.jellyfinradio.data.repository.DataStoreSessionRepository
import dev.tseki.jellyfinradio.data.repository.RoomDownloadRepository
import dev.tseki.jellyfinradio.data.repository.RoomLibraryRefreshRepository
import dev.tseki.jellyfinradio.data.repository.RoomLibraryRepository
import dev.tseki.jellyfinradio.data.repository.RoomLocalDataReset
import dev.tseki.jellyfinradio.data.repository.RoomPlaybackStateRepository
import dev.tseki.jellyfinradio.data.session.KeystoreTokenCipher
import dev.tseki.jellyfinradio.data.settings.DataStoreAppSettings
import dev.tseki.jellyfinradio.data.session.SessionStore
import dev.tseki.jellyfinradio.data.session.TokenCipher
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.DownloadQueue
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.LocalDataReset
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import dev.tseki.jellyfinradio.domain.SessionRepository
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JellyfinRadioDatabase =
        Room.databaseBuilder(context, JellyfinRadioDatabase::class.java, "jellyfin-radio.db").build()

    @Provides
    fun provideProgramDao(db: JellyfinRadioDatabase): ProgramDao = db.programDao()

    @Provides
    fun provideEpisodeDao(db: JellyfinRadioDatabase): EpisodeDao = db.episodeDao()

    @Provides
    fun provideLocalFileDao(db: JellyfinRadioDatabase): LocalFileDao = db.localFileDao()

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
