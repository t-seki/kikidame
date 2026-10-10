package dev.tseki.kikidame.data.db
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
@Database(
    entities = [ProgramEntity::class, EpisodeEntity::class, LocalFileEntity::class, PlaybackStateEntity::class],
    version = 7,
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
        // v7: 列名をコードの名前に揃え、使わない playback_states.syncedAt を消す（ADR 0010、#196）
        AutoMigration(from = 6, to = 7, spec = KikidameDatabase.V6ToV7::class),
    ],
)
@TypeConverters(Converters::class)
abstract class KikidameDatabase : RoomDatabase() {
    abstract fun programDao(): ProgramDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun localFileDao(): LocalFileDao
    abstract fun playbackStateDao(): PlaybackStateDao

    /**
     * v6 → v7（ADR 0010）。サーバ ID を取得元 ID に改め、`@ColumnInfo` で残していた旧い列名をコードの名前に揃える。
     * `syncedAt` は ADR 0007 で使わないまま残していた列で、ここで消す。値は列の改名で残る。
     */
    @RenameColumn(tableName = "programs", fromColumnName = "serverItemId", toColumnName = "sourceItemId")
    @RenameColumn(tableName = "programs", fromColumnName = "stationName", toColumnName = "publisherName")
    @RenameColumn(tableName = "episodes", fromColumnName = "serverItemId", toColumnName = "sourceItemId")
    @RenameColumn(tableName = "episodes", fromColumnName = "airedAt", toColumnName = "publishedAt")
    @DeleteColumn(tableName = "playback_states", columnName = "syncedAt")
    class V6ToV7 : AutoMigrationSpec
}
