# Room スキーマ

[アーキテクチャの入口](../architecture.md)の一部。


主キーはすべてローカル代理キー。取得元 ID は nullable unique（ADR 0001）。
取得元 ID を持たない行（未結合）は正規の状態（ADR 0001）。スキーマの版ごとの変更は `core/data/schemas/` の JSON が正。

```kotlin
@Entity(indices = [Index("sourceItemId", unique = true), Index("publisherName", "name")])
data class ProgramEntity(          // 番組（Jellyfin では MusicAlbum、ブックでは各回の `Album` のタグ）。(配信元, 番組名) は突合のキーだが一意ではない
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sourceItemId: String?,       // 取得元 ID（ADR 0001・0007）
  val name: String,
  val publisherName: String?,      // 配信元（Jellyfin では MusicAlbum.AlbumArtist、ブックでは各回の `AlbumArtist` のタグ）
  val syncEnabled: Boolean,        // 同期対象か（既定 false）
  val keepLatest: Int?,            // 最新 N 回まで保持（null = 上限なし）
  val deleteAfterPlayed: Boolean,
  val goneSince: Instant?,         // 消失した日時
  val starred: Boolean,            // よく聴く
  val imageVersion: String?,       // 手元に持っている番組の画像の版。取得元の画像の版と同じなら取り直さない
  val imagePath: String?           // その画像のファイルの絶対パス。imageVersion と同時に書く。画像なしと分かった画像の版（大きすぎる）では画像の版だけを持ち null
)

@Entity(indices = [Index("sourceItemId", unique = true), Index("programId")])
data class EpisodeEntity(          // 各回（Jellyfin では Audio、ブックでは AudioBook）
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sourceItemId: String?,       // 取得元 ID
  val programId: Long,
  val title: String,
  val publishedAt: Instant,        // 公開日（Jellyfin では PremiereDate、無ければ DateCreated）
  val addedAt: Instant?,           // 取り込み日時（Jellyfin では DateCreated）
  val runtimeTicks: Long, val sizeBytes: Long,
  val container: String,
  val performers: List<String>,    // 出演者
  val sourceFileSize: Long?,       // 共有フォルダのファイルのサイズ。Jellyfin では null（一覧が返さない）。sizeBytes はダウンロードで上書きされるので別に持つ。走査のたびに上書きされる
  val sourceModifiedAt: Instant?,  // 共有フォルダのファイルの更新日時。Jellyfin では null
  val sourceVersion: String?       // 一覧で最後に見たファイルの版。共有フォルダは `<大きさ>:<更新日時の ms>`、Jellyfin は Etag。予約のときに local_files へ写す
)

@Entity
data class LocalFileEntity(        // ダウンロード状態の唯一の正（ADR 0002）
  @PrimaryKey val episodeId: Long,
  val state: DownloadState,        // PENDING / RUNNING / DONE / FAILED
  val path: String?,               // getExternalFilesDir("episodes") 配下の絶対パス
  val pinned: Boolean,             // 固定。保持ルールの対象外（手動ダウンロード）
  val attemptCount: Int,           // FAILED の再試行判断用。WorkManager の backoff は request 単位で各回単位ではない
  val lastAttemptAt: Instant?,
  val downloadedAt: Instant?,
  val enqueuedAt: Instant?,        // キューに入れた時刻。差し替えの予約でも付け直す
  val sourceVersion: String?,      // 落としたときのファイルの版。予約したときの一覧のファイルの版。持たない回（差し替えの仕組みより前に落とした回）は null
  val replaceState: DownloadState?,// 差し替えの状態。null（差し替えていない）/ PENDING / RUNNING。差し替えの間も state は DONE のまま
  val replaceVersion: String?      // 差し替えを予約したときの一覧のファイルの版。落とし終えたら sourceVersion に移す
)

@Entity
data class PlaybackStateEntity(    // ブックのライブラリでは再生の共有（ADR 0010）
  @PrimaryKey val episodeId: Long,
  val positionTicks: Long,         // 1 tick = 100ns
  val played: Boolean,
  val updatedAt: Instant,
  val sharePending: Boolean,       // 未送信
  val sharedPositionTicks: Long?,  // 最後に見たサーバの値。一度も見ていなければ 3 つとも null
  val sharedPlayed: Boolean?,
  val sharedLastPlayedAt: Instant?
)
```

- `wifiOnly` は `Program` に持たず、アプリ全体の設定（DataStore）にする
- `PlaybackStateEntity` は `LocalFileEntity` が削除されても残す。`EpisodeEntity` と同じライフサイクル（取得元に各回が存在する限り）で生存させる
- ticks は Entity と取得元の境界にだけ現れる。ドメイン型は `Duration`
- 列を足したスキーマの移行では、既存の行は新しい列を null（または列の既定）で持つ。`playback_states.sharePending` の既定は 1 で、移行の時点の行は未送信として最初の同期で送る

