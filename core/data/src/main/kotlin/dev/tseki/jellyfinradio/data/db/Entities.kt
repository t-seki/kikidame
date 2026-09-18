package dev.tseki.jellyfinradio.data.db
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.tseki.jellyfinradio.domain.DownloadState
import kotlin.time.Instant
/**
 * 番組 = MusicAlbum。主キーはローカル代理キー、サーバ ID は nullable unique（ADR 0001）。
 * (放送局, 番組名) は突合のキーだが一意ではない（サーバ側に同名の番組が複数あり得る）。
 */
@Entity(
    tableName = "programs",
    indices = [Index("serverItemId", unique = true), Index(value = ["stationName", "name"])],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverItemId: String?,
    val name: String,
    /** 放送局（MusicAlbum.AlbumArtist）。保存先では番組フォルダの親フォルダ名になる。 */
    val stationName: String?,
    val syncEnabled: Boolean = false,
    /** 最新 N 回まで保持（null = 上限なし）。 */
    val keepLatest: Int? = null,
    val deleteAfterPlayed: Boolean = false,
)
/** 各回 = Audio。 */
@Entity(
    tableName = "episodes",
    indices = [Index("serverItemId", unique = true), Index("programId")],
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["id"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverItemId: String?,
    val programId: Long,
    val title: String,
    /** 放送日。 */
    val airedAt: Instant,
    /** 取り込み日時。サーバを経由していない行は null。 */
    val addedAt: Instant?,
    /** 1 tick = 100ns。ticks は Entity とサーバ境界にだけ現れる。 */
    val runtimeTicks: Long,
    val sizeBytes: Long,
    val container: String,
)
/** ダウンロード状態の唯一の正（ADR 0003）。 */
@Entity(
    tableName = "local_files",
    indices = [Index("path", unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocalFileEntity(
    @PrimaryKey val episodeId: Long,
    val state: DownloadState,
    /** `getExternalFilesDir("episodes")` 配下の絶対パス。 */
    val path: String?,
    /** 固定。保持ルールの対象外。 */
    val pinned: Boolean,
    val attemptCount: Int = 0,
    val lastAttemptAt: Instant? = null,
    val downloadedAt: Instant? = null,
    /** キューに入れた時刻。手動（固定）を先に、その中はこの古い順（FIFO）に落とす。同期の予約にも付く。v3 で追加 */
    val enqueuedAt: Instant? = null,
)
/**
 * 再生位置・再生済み。ローカル正（ADR 0002）。
 * `local_files` が消えても残し、`episodes` と同じライフサイクルで生存させる。
 */
@Entity(
    tableName = "playback_states",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaybackStateEntity(
    @PrimaryKey val episodeId: Long,
    val positionTicks: Long,
    val played: Boolean,
    val updatedAt: Instant,
    /** null = 未送信（dirty）。 */
    val syncedAt: Instant? = null,
)
