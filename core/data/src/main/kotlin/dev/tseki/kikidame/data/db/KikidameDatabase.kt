package dev.tseki.kikidame.data.db
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
@Database(
    entities = [ProgramEntity::class, EpisodeEntity::class, LocalFileEntity::class, PlaybackStateEntity::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        // v2: programs の (publisherName, name) を unique でなくした（サーバに同名の番組があり得る）
        AutoMigration(from = 1, to = 2),
        // v3: local_files.enqueuedAt（手動ダウンロードの FIFO 順）
        AutoMigration(from = 2, to = 3),
        // v4: programs.goneSince（消失の記録、#3）
        AutoMigration(from = 3, to = 4),
        // v5: programs.starred（よく聴く、#16）
        AutoMigration(from = 4, to = 5),
        // v6: episodes.performers（出演者、#70）
        AutoMigration(from = 5, to = 6),
    ],
)
@TypeConverters(Converters::class)
abstract class KikidameDatabase : RoomDatabase() {
    abstract fun programDao(): ProgramDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun localFileDao(): LocalFileDao
    abstract fun playbackStateDao(): PlaybackStateDao
}
