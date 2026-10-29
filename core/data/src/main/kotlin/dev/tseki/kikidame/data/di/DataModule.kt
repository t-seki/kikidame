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
import dev.tseki.kikidame.data.session.KeystoreCredentialCipher
import dev.tseki.kikidame.data.session.SessionSourceGateway
import dev.tseki.kikidame.data.sharedfolder.RetrieverTagReader
import dev.tseki.kikidame.data.sharedfolder.RoomScannedFiles
import dev.tseki.kikidame.data.sharedfolder.ScannedFiles
import dev.tseki.kikidame.data.sharedfolder.TagReader
import dev.tseki.kikidame.data.device.ContentResolverDeviceDocuments
import dev.tseki.kikidame.data.device.DeviceDocuments
import dev.tseki.kikidame.data.sharedfolder.FolderTreeFactory
import dev.tseki.kikidame.data.settings.DataStoreAppSettings
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.session.CredentialCipher
import dev.tseki.kikidame.data.source.SourceGateway
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

/** Room の DB と DAO。 */
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
}

/** 共有フォルダ（SMB と端末のフォルダ）の取得元が使う部品。 */
@Module
@InstallIn(SingletonComponent::class)
object SharedFolderModule {
    @Provides
    @Singleton
    fun provideDeviceDocuments(@ApplicationContext context: Context): DeviceDocuments =
        ContentResolverDeviceDocuments(context.contentResolver)

    /** 共有フォルダの木。SMB（#198）か端末のフォルダ（SAF、#199）。 */
    @Provides
    @Singleton
    fun provideFolderTreeFactory(documents: DeviceDocuments): FolderTreeFactory = DefaultFolderTreeFactory(documents)

    @Provides
    @Singleton
    fun provideTagReader(): TagReader = RetrieverTagReader()

    @Provides
    @Singleton
    fun provideScannedFiles(dao: EpisodeDao): ScannedFiles = RoomScannedFiles(dao)
}

/** Preferences DataStore と、その上に載るセッション・設定。 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides
    @Singleton
    @SessionPreferences
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") }

    @Provides
    @Singleton
    fun provideSessionStore(@SessionPreferences dataStore: DataStore<Preferences>, cipher: CredentialCipher): SessionStore =
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

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System
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

    /** 取得元は Jellyfin か共有フォルダ（ADR 0007）。今の接続の種類で振り分ける。 */
    @Binds
    abstract fun bindSourceGateway(impl: SessionSourceGateway): SourceGateway

    @Binds
    abstract fun bindCredentialCipher(impl: KeystoreCredentialCipher): CredentialCipher
    @Binds
    abstract fun bindDownloadRepository(impl: RoomDownloadRepository): DownloadRepository
    @Binds
    abstract fun bindDownloadQueue(impl: RoomDownloadRepository): DownloadQueue
}
