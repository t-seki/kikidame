package dev.tseki.jellyfinradio.data.di
import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.tseki.jellyfinradio.data.db.EpisodeDao
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.ProgramDao
import dev.tseki.jellyfinradio.data.repository.RoomLibraryRepository
import dev.tseki.jellyfinradio.data.repository.RoomLocalImportRepository
import dev.tseki.jellyfinradio.data.repository.RoomPlaybackStateRepository
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.LocalImportRepository
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
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
    @Singleton
    fun provideClock(): Clock = Clock.System
}
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindLibraryRepository(impl: RoomLibraryRepository): LibraryRepository
    @Binds
    abstract fun bindPlaybackStateRepository(impl: RoomPlaybackStateRepository): PlaybackStateRepository
    @Binds
    abstract fun bindLocalImportRepository(impl: RoomLocalImportRepository): LocalImportRepository
}
