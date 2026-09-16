package dev.tseki.jellyfinradio.data.db
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
@Database(
    entities = [ProgramEntity::class, EpisodeEntity::class, LocalFileEntity::class, PlaybackStateEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class JellyfinRadioDatabase : RoomDatabase() {
    abstract fun programDao(): ProgramDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun localFileDao(): LocalFileDao
    abstract fun playbackStateDao(): PlaybackStateDao
}
