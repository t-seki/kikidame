package dev.tseki.kikidame.data.db
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.tseki.kikidame.domain.DownloadState
import kotlin.time.Instant
/**
 * 番組 = MusicAlbum（Jellyfin のブックのライブラリでは各回の Album のタグ。#354）。主キーはローカル代理キー、取得元 ID は nullable unique（ADR 0001）。
 * (配信元, 番組名) は突合のキーだが一意ではない（取得元に同名の番組が複数あり得る）。
 */
@Entity(
    tableName = "programs",
    indices = [Index("sourceItemId", unique = true), Index(value = ["publisherName", "name"])],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceItemId: String?,
    val name: String,
    /** 配信元（MusicAlbum.AlbumArtist。ブックのライブラリでは各回の AlbumArtist のタグ）。保存先では番組フォルダの親フォルダ名になる。 */
    val publisherName: String?,
    val syncEnabled: Boolean = false,
    /** 最新 N 回まで保持（null = 上限なし）。 */
    val keepLatest: Int? = null,
    val deleteAfterPlayed: Boolean = false,
    /** 消失した日時。null なら取得元に在る。v4 で追加 */
    val goneSince: Instant? = null,
    /** よく聴く（表示用の印。CONTEXT.md）。v5 で追加 */
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /**
     * 手元に持っている番組の画像（ADR 0012、#264）の版（`SourceImage.version`）と、そのファイルの絶対パス。
     * 版が取得元と同じなら画像を取り直さない。画像を持たなければどちらも null。2 つは同時に書く。
     * 取りに行って画像なしだった版（大きすぎるなど）は、版だけを持ちパスは null（同じ版の間は取り直さない）。v9 で追加
     */
    val imageVersion: String? = null,
    val imagePath: String? = null,
)
/** 各回 = Audio（Jellyfin のブックのライブラリでは AudioBook。#354）。 */
@Entity(
    tableName = "episodes",
    indices = [Index("sourceItemId", unique = true), Index("programId")],
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
    val sourceItemId: String?,
    val programId: Long,
    val title: String,
    /** 公開日。 */
    val publishedAt: Instant,
    /** 取り込み日時。取得元を経由していない行は null。 */
    val addedAt: Instant?,
    /** 1 tick = 100ns。ticks は Entity と取得元の境界にだけ現れる。 */
    val runtimeTicks: Long,
    val sizeBytes: Long,
    val container: String,
    /** 出演者（Audio・AudioBook の Artists、#70）。[Converters] で 1 列に畳む。v6 で追加 */
    @ColumnInfo(defaultValue = "") val performers: List<String> = emptyList(),
    /**
     * 共有フォルダでの、取得元のファイルのサイズと更新日時（#197）。Jellyfin の行と、v8 より前に取り込んだ行は null。
     * 取得元 ID（相対パス）とこの 2 つがどれも前と同じ回は、タグを読み直さずにこの行の値を使う。
     * [sizeBytes] はダウンロードの完了時に実サイズで上書きされるので、この判定には使わない。v8 で追加
     */
    val sourceFileSize: Long? = null,
    val sourceModifiedAt: Instant? = null,
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
 * 再生位置・再生済み。取得元が Jellyfin のブックのライブラリのときは、取得元の UserData と分け合う（**再生の共有**、ADR 0013。
 * ADR 0002 を置き換えた）。それ以外の取得元では手元だけの記録。
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
    /**
     * 未送信（ADR 0013 の決定 5、#355）。手元で位置か再生済みが変わると立ち、取得元に送れたら下りる。取得元が共有しないとき
     * （音楽のライブラリ・共有フォルダ）も立つが送らない。突合で取得元 ID が変わった回も立つ。v10 で追加。
     * 列の既定は 1: v10 に上げた時点で手元にある行は、まだ一度も送っていないので未送信として扱う（ブックのライブラリを選んだまま上げた端末や、
     * この版より前に音楽からブックへ移って結び直し済みの端末でも、最初の同期で送る。サーバに別の値があれば新しい方。ADR 0013 の決定 7）。
     * Kotlin の既定（false）はテストで行を直接作るときのためのもの
     */
    @ColumnInfo(defaultValue = "1") val sharePending: Boolean = false,
    /**
     * 最後に見た取得元の値（ADR 0013 の決定 4）。位置（ticks）・再生済み・`LastPlayedDate`。一度も見ていなければ 3 つとも null
     * （[sharedPositionTicks] が null かどうかで見分ける。`LastPlayedDate` は見ていても null がありうる）。v10 で追加
     */
    val sharedPositionTicks: Long? = null,
    val sharedPlayed: Boolean? = null,
    val sharedLastPlayedAt: Instant? = null,
)
