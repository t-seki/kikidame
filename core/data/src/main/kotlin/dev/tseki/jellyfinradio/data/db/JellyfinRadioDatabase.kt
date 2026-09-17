package dev.tseki.jellyfinradio.data.db
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
@Database(
    entities = [ProgramEntity::class, EpisodeEntity::class, LocalFileEntity::class, PlaybackStateEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // v2: programs の (stationName, name) を unique でなくした（サーバに同名の番組があり得る）
        AutoMigration(from = 1, to = 2),
        // v3: local_files.enqueuedAt（手動ダウンロードの FIFO 順）
        AutoMigration(from = 2, to = 3),
    ],
)
@TypeConverters(Converters::class)
abstract class JellyfinRadioDatabase : RoomDatabase() {
    abstract fun programDao(): ProgramDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun localFileDao(): LocalFileDao
    abstract fun playbackStateDao(): PlaybackStateDao
}
