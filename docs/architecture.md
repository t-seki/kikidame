# Kikidame アーキテクチャ

Kikidame の今の設計を述べる文書。用語は `CONTEXT.md` の定義に従う。覆しにくい判断の理由は `docs/adr/` にある。画面の仕様（文言・シートの中身・行の操作）は [ui.md](ui.md) にあり、この文書は仕組み（型・口・規則）を述べる。
この文書と食い違う点があれば、`CONTEXT.md` と ADR の方を正とし、この文書を直す。

## 目次

構成の話（1〜4）の後に、データの流れ（取得 → 突合 → 同期 → ダウンロード・差し替え → 再生）の順に並べる。

1. [プロダクト概要](#プロダクト概要)
2. [技術スタック](#技術スタック)
3. [モジュール構成](#モジュール構成)
4. [Room スキーマ](#room-スキーマ)
5. [取得](#取得)
   - [取得元の境界とセッション](#取得元の境界とセッション)
   - [Jellyfin サーバの前提](#jellyfin-サーバの前提)
   - [Jellyfin のライブラリ構造と取得パス](#jellyfin-のライブラリ構造と取得パス)
   - [共有フォルダの走査](#共有フォルダの走査)
   - [番組の画像](#番組の画像)
6. [突合と取り込み](#突合と取り込み)
7. [同期](#同期)
8. [ダウンロードと差し替え](#ダウンロードと差し替え)
   - [ダウンロード](#ダウンロード)
   - [差し替え](#差し替え)
   - [手元のファイルの整理](#手元のファイルの整理)
9. [再生](#再生)
   - [再生の仕組み](#再生の仕組み)
   - [聴いている回の読み方](#聴いている回の読み方)
   - [再生位置と再生済み](#再生位置と再生済み)
10. [UI の文言（i18n）](#ui-の文言i18n)
11. [やらないこと](#やらないこと)
12. [参照](#参照)

## プロダクト概要

- 録り溜めたラジオ番組（ポッドキャストのファイルも使える。AAC/M4A・MP3）を Jellyfin サーバ・NAS の共有フォルダ・端末のフォルダから取得し、**オフラインで聴く**ための Android アプリ
- コンセプトは 2 つ
  1. **オフライン優先**: サーバに到達できなくてもアプリが完全に成立する
  2. **フォルダ同期**: 番組単位でルールを決めて自動的にダウンロード／削除する
- 既存クライアント（Findroid・公式アプリ）は映像中心で、番組単位の自動同期とオフライン自立が弱い。そこが差別化点なので、この 2 点の品質を最優先する
- 利用形態は **取得元は同時に 1 つ（Jellyfin は 1 サーバ・1 ユーザー・1 ライブラリ、SMB は 1 つの共有、端末のフォルダは 1 つのフォルダ）**。聴くのはこのアプリだけを想定する（他端末・Web との併用は、Jellyfin のブックのライブラリで再生の共有をするときだけ）

## 技術スタック

| 領域 | 採用 |
| --- | --- |
| 言語 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| 再生 | Media3 (ExoPlayer) + MediaSessionService — **再生専用**。DownloadManager は使わない（ADR 0002） |
| API | jellyfin-sdk-kotlin（公式）**v1.9.0 以上**（Jellyfin 12.0 対応） |
| 永続化 | Room |
| バックグラウンド | WorkManager（同期・ダウンロードの Worker） |
| DI | Hilt |
| 日時 | `kotlin.time.Instant`（stdlib）+ kotlinx-datetime（`LocalDate` / `TimeZone`）。時間長は `kotlin.time.Duration` |
| テスト | `:core:domain` は JUnit5、Android モジュールは JUnit4 + Robolectric。Media3 は `SimpleBasePlayer` 継承のフェイク `Player` |

## モジュール構成

```
:core:domain   純粋 Kotlin (JVM)。番組／各回／保持ルール／再生済み等のドメイン型、
               再生済み判定、同期の純粋関数。Android・Room・Media3 に依存しない
:core:data     Android ライブラリ。Room Entity / DAO / TypeConverter / Repository 実装。
               Entity ↔ ドメイン型のマッパー、ticks(100ns) ↔ Duration の変換はここに閉じ込める
:app           Compose UI、MediaSessionService、WorkManager Worker、Hilt エントリ
```

`applicationId` は `dev.tseki.kikidame`、`minSdk` は 31。Room は `exportSchema = true` で、`core/data/schemas/` を git 管理する。
境界を先に置くこと自体が目的で、後からモジュールを割るコストが最も高い。

## Room スキーマ

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

## 取得

### 取得元の境界とセッション

- 同期とダウンロードは、取得元の境界 `SourceGateway`（`fetchAll` / `fetchProgram` / `fetchProgramEpisodes` / `fetchProgramImage` / `fetchFileSize` / `openDownload`、`:core:data` の `data.source`）だけを通す（ADR 0007）。実装は Jellyfin の `JellyfinSource` と共有フォルダの `SharedFolderSource`。入口は `SessionSourceGateway`（`SourceGateway` の bind）で、今の接続の種類で振り分ける
- `:core:domain` に取得元のスナップショット型（`SourceProgram` / `SourceEpisode` / `SourceSnapshot`）と突合の純粋関数 `LibraryMatching.match` を置く。`:core:data` に `JellyfinGateway`（`signIn` / `listLibraries` / `fetchLibrary` ほか）と jellyfin-sdk-kotlin 実装、`SessionStore` を置く。Repository が突合結果を 1 トランザクションで Room に適用する。テストはフェイクのゲートウェイで行う
- `SessionState` は `SignedOut`（直前の取得元の入力補助として `lastServerUrl`・`lastUserName`・`lastSmb`・`lastDeviceFolder` を持つ）／`NeedsLibrary`（Jellyfin だけ）／`Ready`。`Ready` は `source: ConnectedSource`（`Jellyfin(session, library)`・`Smb(connection)`・`Device(folder)`）と `lastFetchedAt`・`lastAttemptedAt` を持つ
- DataStore（`SessionStore`）は、`smb_host` があれば SMB、`device_tree_uri` があれば端末のフォルダ、どちらも無ければ Jellyfin とみなす。SMB は `smb_host`・`smb_share`・`smb_path`・`smb_user`・`smb_password`（暗号化）・`smb_guest`、端末のフォルダは `device_tree_uri`・`device_name`・`device_signed_out` のキーで持つ
- 一度も接続していなければ、取得元の選択（「Jellyfin に接続」「NAS の共有フォルダ（SMB）」「端末のフォルダ」）から始まる。ログアウトした後は直前の取得元の接続画面へ戻る。設定の「取得元を変える」は確認の上でローカルデータ（音声ファイルを含む）を全部消す（処理は `SourceChanger`）。画面は [ui.md](ui.md)
- Jellyfin のライブラリ選択は常に出す。`/UserViews` の全ライブラリを列挙し、音楽とブック以外は選べない。選べるライブラリが 1 つなら選択済みにする
- SMB は `SessionRepository.connectSharedFolder` が、共有の直下が読めたら保存する（失敗は `SourceException`: 認証の失敗は `Unauthorized`、届かないは `Unreachable`、読めないは `Failed`）。SMB の認証の失敗は 401 と同じく接続をやり直させる。名前が引けない・タイムアウトは到達不能（判断保留）。SMB の接続は 1 回の操作ごとに張って閉じる
- 端末のフォルダは `SessionRepository.connectDeviceFolder(treeUri)` が、authority が `com.android.externalstorage.documents` でなければ `UnsupportedFolderException`、そうなら永続権限（読み取り）を取り、直下が読めたら保存し、ほかに持っている永続権限を手放す（`releasePersistableUriPermission`）。「取得元を変える」でも、持っている永続権限を手放す。フォルダは OS のフォルダ選択（`ACTION_OPEN_DOCUMENT_TREE`）で選び、選んだ後は直下が読めるかだけを確かめる
  - 木は `DeviceFolderTree`（`FolderTree` の SAF 実装。`DocumentsContract` の窓口 `DeviceDocuments` を `ContentResolverDeviceDocuments` が実装し、テストは `MemoryDeviceDocuments`）で、相対パスから文書 ID への対応は親を一覧して名前で引く
  - 最初の操作で永続権限と根があることを確かめ、無いと分かったら `SourceException.Unauthorized`（`LibraryRefresher`・`DownloadWorker` がログアウトし、フォルダの選び直しへ）。「無いと分かった」とするのは、永続権限の一覧に読み取りが無い、provider が `SecurityException` を投げた、根の問い合わせに provider が「無い」（行の無い結果）と答えたときだけで、それ以外の失敗は SMB と同じく通常の失敗（到達不能）にする。根が無いのを null で返さない（全番組が消失扱いになるため）
  - 画面に出す場所は `DeviceFolder.displayPath` がツリーの文書 ID から作る（`primary:Dir/Sub` の形は `ExternalStorageProvider` の実装の決まりで、公開の契約ではない）。形が崩れていれば保存済みの `name` に戻す
- Android 17+（targetSdk 37）の `ACCESS_LOCAL_NETWORK` ランタイム権限が要る。無いと LAN 内のサーバ宛の TCP が黙って落ちる（DNS だけ通るので名前解決は成功し、接続がタイムアウトする）。`adb shell` や古い targetSdk のアプリは対象外なので、切り分けでは `run-as <pkg> nc -z <host> <port>` でアプリの UID から試す。要求するのは、取得元が Jellyfin か SMB のとき（ログアウト中で前回の取得元が Jellyfin か SMB のときを含む）の起動時と、取得元の選択で Jellyfin か SMB のカードを押したときだけ（domain の `SessionState.usesLocalNetwork`。端末のフォルダには要求しない）

### Jellyfin サーバの前提

- Jellyfin **12.0** を対象にする。Jellyfin 10.10.7 と Jellyfin 10.11 系でも動かす（認証形式は同じ）
- **レガシー認証は使用不可**。`?api_key=`、`X-Emby-Token`、`X-Emby-Authorization` は 12.0 で廃止された。
  `Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="...", Token="..."`
  形式のみを使う
- `/emby/`・`/mediabrowser/` のパスは削除済み。使わない
- OpenAPI 仕様に載っていないエンドポイントは使わない。認証・API アクセスは jellyfin-sdk-kotlin に任せ、自前で HTTP を組み立てない
- 経路は `https://` 固定（正規証明書）。平文 HTTP は許可しない。URL 入力はスキーム省略可
- 認証情報の `Client` は `Kikidame`、`Version` は `versionName`。`Device` / `DeviceId` は jellyfin-sdk-kotlin の Android 既定（端末のモデル名／`ANDROID_ID` 由来）に任せ、自前の UUID は持たない
- ダウンロードは URL（`getDownloadUrl`）と `Authorization` ヘッダ（`AuthorizationHeaderBuilder`）を SDK に作らせ、
  転送だけ OkHttp のストリームで行い、Worker がファイルへ直接書く（ADR 0002。SDK にストリーミング API が無いため）。Media3 の `HttpDataSource.Factory` に Authorization ヘッダを設定する必要があるのは、サーバから直接ストリーミング再生する場合に限る（今は行わない）
- 認証トークンは Android Keystore で生成した鍵で AES-GCM 暗号化し、Preferences DataStore に置く（`EncryptedSharedPreferences` は使わない）。パスワードは保存しない。保存は `SessionStore`（`:core:data`）が持つ
- ログアウトは認証情報だけを消し、手元のデータは残す。別の取得元へ接続するときだけ、明示的に「取得元を変える」（ローカルデータを消して接続）を選ばせる。401 はログアウトと同じ処理をして接続画面へ戻す（URL とユーザー名は入力済み）

### Jellyfin のライブラリ構造と取得パス

サーバ側の音声は**音楽ライブラリ**として取り込まれている。アプリ内では自前モデルに正規化する。
**ブックのライブラリ**も取得元に選べる（ADR 0010 の決定 2）。ブックのライブラリには MusicAlbum が無いので、各回（AudioBook）の
`Album` のタグを番組、`AlbumArtist` のタグを配信元としてアプリがまとめ、番組の取得元 ID は `<AlbumArtist>/<Album>` の文字列にする（下の表の Audio・MusicAlbum の行の読み替え）。
番組の画像は番組のフォルダ（`Folder`）の Primary で、各回の親がどれも同じ Folder で、その名前が `Album` と同じときだけ使う（各回が 1 本だけの番組は、各回の親が配信元のフォルダになるため）。

| Jellyfin | アプリ内の概念（`CONTEXT.md` 参照） |
| --- | --- |
| MusicAlbum（ブックでは各回の `Album`） | 番組 (Program) |
| Audio（ブックでは AudioBook） | 各回 (Episode) |
| `AlbumArtist` | 配信元 (Publisher) — ラジオ録音なら放送局、ポッドキャストなら配信者やネットワーク（ラジオ録音ツールが、Jellyfin に取り込ませるファイルの albumartist タグに書く） |
| `PremiereDate` → `DateCreated` → ファイル更新日時 | 公開日 (Published At) — 並び順・「最新 N 回」の基準。この順でフォールバック |
| `DateCreated` | 取り込み日時 (Added At) — 保存するが新しさの判定には使わない。公開日の代用として並び順に効くのは上の行の経路（差分取得は ADR 0003 で見送り） |
| `Id` | 取得元 ID（`sourceItemId`）— アプリ内の主キーではない（ADR 0001・0007） |

主に使う取得パス（SDK 経由で呼ぶ）:

- ライブラリ一覧: `/UserViews`。`CollectionType = music` と `books` のものだけ選択できる
- ブックのライブラリ: `/Items?ParentId={libraryId}&IncludeItemTypes=Folder&Recursive=true&Fields=ParentId` で番組のフォルダを、
  `/Items?ParentId={libraryId}&IncludeItemTypes=AudioBook&Recursive=true&Fields=DateCreated,ParentId,Path,Etag&SortBy=DateCreated&SortOrder=Descending`（500 件ずつ）で各回を取り、タグでまとめる（`Etag` はファイルの版。ADR 0011）。
  `albums=` の絞り込みは使わない（Jellyfin 10.10.7 では効き、Jellyfin 12.0 では 0 件、Jellyfin 10.11.11 では全件が返った）。1 番組の各回・番組の存在確認もライブラリ全体を取ってまとめた中から選ぶ。
  AudioBook は `Container` を返さない（Jellyfin 10.10.7 と 12.0）ので、保存先の拡張子は `Path` から取る。番組の画像の中身は、番組のフォルダの `/Items/{folderId}/Images/Primary`
- 番組一覧: `/Items?ParentId={libraryId}&IncludeItemTypes=MusicAlbum&Recursive=true`（1 回。`Fields` は付けない）。
  番組の画像の版は、返る `ImageTags.Primary`（`SourceProgram.image`）。`Fields` を付けなくても返ることを、Jellyfin 10.10.7 のテストサーバ（`scripts/jellyfin-testserver.sh up 1010`）で確かめた（`folder.jpg` のあるアルバムだけ `ImageTags` に `Primary` が出た）。Jellyfin 12 では未確認
- 各回一覧: `/Items?ParentId={libraryId}&IncludeItemTypes=Audio&Recursive=true&Fields=DateCreated,ParentId,Etag&SortBy=DateCreated&SortOrder=Descending&StartIndex=…&Limit=500`
  （ライブラリ全体を 500 件ずつ。番組ごとには呼ばない。`AlbumId` で番組に結び付け、どの番組にも属さない Audio は取り込まない。
  **`Fields=MediaSources` は付けない**: SDK 1.9 は `MediaStream.IsOriginal` を必須と見なすが、それを返さない Jellyfin サーバがあり、デコードで落ちる（どの Jellyfin を指すかは、コードと統合テストからは決められていない）。
  ファイルサイズはダウンロードの完了時に実際の値で書く。`Etag` は**差し替え**のためのファイルの版（ADR 0011）。`ItemFields.ETAG` を求めたときだけ返る）
- 各回のファイルの大きさ（差し替え、ADR 0011）: `/Items/{itemId}/Download` に `Range: bytes=0-0` を付け、206 の `Content-Range: bytes 0-0/<全長>` から全長を取る
  （`SdkJellyfinGateway.fetchFileSize`。本文は 1 バイトだけ）。`HEAD` は 405 で使えない。どちらも Jellyfin 10.10.7 と 12.0 のテストサーバで確かめた。
  ファイルを書き換えた後、Jellyfin が走査し直す前でも、ディスク上の今の大きさが返った（同じく両方のサーバで確かめた）。一覧のファイルの版（`Etag`）が変わった回と、ファイルの版を持たない手元の回にだけ問い合わせる
- 番組の画像の中身: `/Items/{albumId}/Images/Primary`（SDK の `imageApi.getItemImageUrl` で URL を作り、転送は OkHttp。認証ヘッダは `openDownload` と同じ）。
  404（Primary が無い）と 10 MiB（`SdkJellyfinGateway.MAX_PROGRAM_IMAGE_BYTES`。共有フォルダの上限と同じ値で、根拠は無い）超えは null（画像なし）。サイズ指定・変換は付けず、サーバの原本をそのまま持つ。
  Jellyfin 10.10.7 の画像の API は、無効なトークンでも画像を返した（認証を求めない）。ヘッダは付けるが、認証の失敗の経路（401/403 → `Unauthorized`）は実サーバでは確かめていない
- 原本ダウンロード: `/Items/{itemId}/Download`（トランスコードは使わない。direct play 前提）
- 再生位置・再生済みの送信（再生の共有。ブックのライブラリだけ、ADR 0010）: **`POST /UserItems/{itemId}/UserData`**（`UpdateUserItemDataDto` の
  `PlaybackPositionTicks` / `Played` / `LastPlayedDate`。`LastPlayedDate` は手元の更新時刻を UTC のナイーブな日時で）。`/Sessions/Playing/*` は使わない。
  応答の `UserItemDataDto` を「最後に見たサーバの値」にする（`LastPlayedDate` はミリ秒にそろえてから比べ・記録する。手元はミリ秒で持つため）。Jellyfin 10.10.7 と 12.0.0 のコンテナで、書いた 7 秒の位置・再生済み・ミリ秒までの `LastPlayedDate` が、
  応答・`GET /UserItems/{itemId}/UserData`・一覧（`/Items` の `UserData`）のどれでも同じ値で読み戻せた（統合テスト `bookUserDataKeepsWrittenPositionPlayedAndLastPlayedDate`）
- 再生位置・再生済みの読み込み（同じくブックのライブラリだけ）: 同期では、ブックの一覧（`/Items`）に `userId` と `enableUserData` を付けて各回の `UserData` を一緒に読む（回ごとに問い合わせない）。
  ▶ の直前は `GET /UserItems/{itemId}/UserData` を 1 回だけ、2 秒で打ち切る

取得は毎回フル走査にする。差分取得（`DateCreated` 降順で取り、ローカルの最大取り込み日時に到達したら打ち切る）は**作らない**（ADR 0003）: 不完全な一覧を同期の入力にすると載っていないだけの各回を削除してしまい、
全走査を定期に回しても許容できる重さなら取り込み専用の経路を持つ価値が無い。各回一覧の「引っ張って更新」も、取り込みだけの経路ではなく 1 番組の同期（`syncProgram`）にそろえる。

サーバの `PremiereDate`（無ければ `DateCreated`）は日時で来るが、**日付部分だけ取って JST 0 時の `Instant`** にする
（日単位の値として並び順を安定させる）。

### 共有フォルダの走査

共有フォルダ（`CONTEXT.md`）を取得元にするときの、中身のたどり方。3 段の構成は ADR 0013、全走査を番組ごとに取り込むことは ADR 0003 の決定 4、タグから回の情報を読むことは ADR 0007 の決定 8。
実装は `core/data/.../data/sharedfolder/` の `SharedFolderSource`（`SourceGateway` の実装）。

**フォルダの木の抽象**（`FolderTree`）: SMB（`SmbFolderTree`）と端末のフォルダ（SAF、`DeviceFolderTree`）がこれを実装する。走査はこの上だけで書き、テストはメモリ上の木で行う。

- 子の一覧（名前・フォルダかどうか・サイズ・更新日時）。そのパスのフォルダが無ければ一覧の代わりに null、失敗なら例外。更新日時は必須で、取れない取得元では実装の側で補う（端末のフォルダで provider が更新日時を返さないときはエポックで補う。そのときファイルの版は大きさだけで決まり、大きさの変わらない書き換えには気づけない。ADR 0011）
- ファイルの部分読み（位置と長さを指定。タグの読み取り用。ファイル全体を転送しない）
- ファイルのストリーム（オフセットから。ダウンロードの続きから取る `.part` の再開用）
- パスは共有フォルダの根からの相対パスで、区切りは `/`。どのメソッドも blocking で、走査の側が IO のスレッドで呼ぶ。読むだけで書き込まない

**3 段の規則**: 中はちょうど `<配信元>/<番組>/<各回のファイル>` の 3 段。

- 2 段目のフォルダはすべて番組（音声ファイルが 0 本でも番組。1 番組の取得がフォルダの有無で答えるのと揃える）。配信元と番組はフォルダの名前で決め、タグは使わない
- 3 段目の、拡張子が `m4a` / `mp3` / `aac` / `ogg` / `opus` / `flac` / `wav`（大文字・小文字を区別しない）のファイルだけを各回とする。
  それ以外（1・2 段目のファイル、4 段目以下、音声でないファイル）は黙って無視する。形式（`container`）は拡張子（小文字）
- 例外は番組のフォルダ（2 段目）の `folder.jpg`（名前は大文字・小文字まで一致するものだけ。`folder.png` などは読まない）で、番組の画像（`CONTEXT.md`）として読む（ADR 0009。ラジオ録音ツールが番組のフォルダに `folder.jpg` を置く付け方に合わせた）。
  一覧の大きさと更新日時（ms）から画像の版（`SourceImage.version`、`<大きさ>:<更新日時の ms>`）を作って `SourceProgram.image` に載せ、中身は `SharedFolderSource.fetchProgramImage` で全部読む（10 MiB（`MAX_PROGRAM_IMAGE_BYTES`）より大きいものは読まずに画像なし（null）として返す。上限の値に根拠は無い。「番組の画像」節）
- 名前が `.`・`@`・`#` で始まるフォルダとファイルは、どの段（配信元・番組・各回）でも無視する。
  NAS や OS が作るもの（例: `@eaDir`・`#recycle`・`._<名前>.m4a`・`.DS_Store`。どれが作られるかは確かめていない）を拾わないため。決まった名前だけを除く一覧方式は、NAS の種類ごとに漏れが出るので採らない。
  1 番組の取得（`fetchProgram`・`fetchProgramEpisodes`）も同じ規則で、そういう名前の番組は無いものとして扱う
- 取得元 ID は相対パス。番組は `<配信元>/<番組>`、各回は `<配信元>/<番組>/<ファイル名>`。ファイル名を変えると未結合になり、
  今の突合（同じ番組内のタイトル → 公開日と尺）で結び直す。手元のコピーのパス（`local_files.path`）はキューに入れたときに決めたものを使い続けるので、
  結び直してタイトルが変わってもコピーは旧タイトルの名前のまま見つかる（`SharedFolderSourceTest` で確かめている）。番組のフォルダ名を変えると消失になる
- 全走査は完全な一覧（ADR 0003）。根が無いときは空の一覧でなく到達不能にする。1 番組の取得は、その番組のフォルダの中身だけで答える
- 木の操作（一覧・部分読み）が例外を投げたら到達不能（`SourceException.Unreachable`。今の Jellyfin と同じく判断保留）。番組のフォルダが無ければ消失

**タグ**（`TagReader`。Android では `MediaMetadataRetriever` に部分読みの `MediaDataSource` を渡す `RetrieverTagReader`）:

| 項目 | タグがあるとき | 無いとき |
| --- | --- | --- |
| タイトル | title | 拡張子を除いたファイル名 |
| 公開日 | 日付（年月日まであるときだけ。JST の日付の 0 時） | 取り込み日時（ファイルの更新日時）の JST の日付の 0 時 |
| 取り込み日時 | — | ファイルの更新日時 |
| 尺 | 音声の長さ（ticks で持つ） | 0（不明。突合の第 2 段の対象外） |
| 出演者 | artist（1 件。分割しない） | 空 |

- 年だけ・年月だけ・読めない日付は、無いものとして扱う（`TagDate`）
- ファイルを解釈できないときはタグ無しとして補う。木の読み取りの失敗は到達不能にする（タグ無しとして保存すると、サイズと更新日時が同じ間は読み直されないため）
- `RetrieverTagReader` は JVM のテストで動くかを確かめていない（未確認）。テストはフェイクの `TagReader` で行い、`RetrieverTagReader` 自体はテストしていない

**タグの読み直しの条件**: 回の行に、ファイルのサイズと更新日時（`sourceFileSize` / `sourceModifiedAt`）を持つ。
走査のときに前の行を取得元 ID（相対パス）で引き、パス・サイズ・更新日時がどれも同じなら、タグを読まずに前の行のタイトル・公開日・尺・出演者を使う。
どれかが変わったファイルと新しいファイルだけタグを読む。更新日時は ms に揃えて比べる（Room は ms で持つ。ms より細かい値が来ても同じとみなす。SMB の実装から何が来るかは未確認）。
`sizeBytes` はダウンロードの完了時に実サイズで上書きされるので、この判定には使わない。
`sourceFileSize` / `sourceModifiedAt` は走査のたびに取得元の値で上書きされるので、「落としたときの値」としては使えない。
**差し替え**（ADR 0011）は、各回のファイルの版（`SourceEpisode.version`。大きさと更新日時（ms）の組 `<大きさ>:<更新日時の ms>`）を落としたときのファイルの版（`local_files.sourceVersion`）と比べて決める（「差し替え」節）。

**全走査の流れ**: `SharedFolderSource.fetchAll(listener)` は、一覧を先に全部取ってから番組ごとにタグを読み、タグを読んだ番組をその番組を読み終えるたびに `ScanListener.onProgramScanned` に流す。
`RoomLibraryRefreshRepository.refresh` はそれを受けて 1 番組ずつ取り込み（`SnapshotScope.Program`）、その番組の中で削除・予約する（`syncProgram` と同じ範囲）。全部を読み終えたら、全体を取り込み直して消失を判断し、最終同期の時刻を記録する。
途中で止まっても取り込み済みの番組は残り、次の同期ではそのタグを読まない（DB の大きさ・更新日時で判定）。
全走査は、前面サービスも通知も作らず、アプリのスコープと WorkManager の上で走る（ADR 0003）。
木の操作が失敗したら `FolderTree.reset`（SMB は接続を閉じる）してやり直し、続けて 3 回（`SharedFolderSource.MAX_CONSECUTIVE_FAILURES`）失敗したら到達不能にする。画面が消えて裏に回ると、しばらくして Android が TCP の接続を壊し、張り直しも効かずに到達不能で終わることがある（壊されるまでの時間は一定ではなく、何で決まるかは未確認。張り直しで続けられた場面は実機でまだ見ていない）。
タグの読み取りは、遅いのは転送量ではなく往復の回数と見て（m4a のタグの読み取りは、測った例で 1 本あたり 2,048 バイトずつ 150〜191 回の読み出しになった）、`BlockReader` が 256 KB ずつまとめて読み（位置ごとの塊を 4 つまで持つ）、往復を減らす。並列では読まない。進み具合は `ScanListener.onTagProgress` → `LibraryRefreshRepository.refresh(onProgress)` → `LibraryRefresher.tagProgress` で番組一覧の `BackgroundSyncBar` に出す。
聴いている回（削除と差し替えの予約から外す回。ADR 0011）は `refresh(excluded = { … })` で番組ごとに読み直す。番組ごとに予約した数は `onEnqueued` で流れ、`LibraryRefresher` は全走査が途中で失敗してもそれが 1 以上なら Worker を起こす（取り消し・認証の失敗では起こさない。取得元を変えるときは `SourceChanger` が Worker を止めてから走査を取り消し、手元を消すため）。

### 番組の画像

番組の画像（`CONTEXT.md`）を同期で取得元から取り、アプリの領域にファイルで持つ（ADR 0009）。表示は手元のファイルだけを読む。

- **口の形**: `SourceProgram.image`（`SourceImage(version)`。取得元に画像が無ければ null）に画像の版だけを載せ、中身は `SourceGateway.fetchProgramImage(programId)` で別に取る。
  取り直すかを決めるのは取り込みの側（`RoomLibraryRefreshRepository.updateImages`）で、取得元は前の画像の版を知らなくてよい。Jellyfin は Primary の ImageTag を画像の版にして同じ口に載る（`JellyfinSource.fetchProgramImage` → `JellyfinGateway.fetchProgramImage`）。ImageTag が変わらなければ取り直さない（`JellyfinProgramImageTest`）
- **取り込み**: 番組ごとの取り込み（全走査の途中、`ScanListener.onProgramScanned`）・全走査の最後の取り込み・1 番組の同期のどれでも、同期（削除・予約）の後に `updateImages` を回す。
  新しい回の無い番組は途中の取り込みに流れないので、最後の取り込みで取る。画像の版が行（`imageVersion`）と同じで、ファイルがあるか `imagePath` が null（その画像の版は画像なしと分かっている）なら取らない。違えば取り、新しい名前のファイルに書いてから行を書き換え、前のファイルを消す。
  取得元に画像が無ければ（`folder.jpg` が消えた）ファイルを消して行を null にする。
  `folder.jpg` が 10 MiB（`SharedFolderSource.MAX_PROGRAM_IMAGE_BYTES`）を超えるときは、`fetchProgramImage` が読まずに null を返し、画像なしとして扱う。手元に画像のファイルがあれば消し、行には画像の版だけを書く（`imagePath` は null）。同じ画像の版の間は同期のたびに取り直さず、ファイルが変われば（画像の版が変われば）取り直す（一覧の後に `folder.jpg` が消えて null が返ったときも同じ扱い）。取得・書き込みに失敗したら、その番組は前の画像のまま残して同期を続ける（画像の版を書かないので、次の同期で取り直す）。取得元に届かない（`SourceException.Unreachable`）・認証が通らない（`Unauthorized`）ときは、その回の残りの番組の画像の取得を打ち切る（残りも前の画像のまま）
- **接続**: 共有フォルダは `SessionSourceGateway` が `fetchProgramImage` のたびに木を開く（`openDownload` と同じ）。全走査の途中で呼ぶと、走査の接続とは別にもう 1 本張る。
  画像を取るのは画像の版が変わった番組だけだが、画像を取る仕組みを入れた直後の最初の同期では、`folder.jpg` を持つ番組の数だけ接続を張る（何本までなら困らないかは測っていない。未確認）
- **置き場**: `filesDir/program_images/<番組のローカル ID>-<画像の版の SHA-256 の頭 16 桁>.img`（`ProgramImageFiles`）。画像が変わると名前も変わるので、Coil のメモリのキャッシュがパスで引いても古い画像を出さない。
  一時ファイルに書いてから名前を変える。「この番組を削除」（`RoomDownloadRepository.removeProgram`）は、行を消す前に `imagePath` を取り、行を消した後にそのファイルを消す（消せなくても削除は止めない）。全走査の最後に、どの行からも指されていないファイル（消せずに残ったものなど）を孤児として消す。
  「取得元を変える」（`RoomLocalDataReset`）は全部消す。ファイルの書き込み・消去と行の更新は `RoomLibraryRefreshRepository` の中の `Mutex` で 1 つずつ行う
- **表示**: Coil（`coil-compose`、Apache-2.0）の `AsyncImage` に手元のファイルを渡す（`ui/ProgramImage.kt` の `ProgramImageView`）。出す場所と切り抜き方は [ui.md](ui.md)。ミニプレイヤーは `MiniPlayerViewModel.programImage` が聴いている回の番組を購読して引く（`NowPlayingState` は画像を持たない）。
  プレビューは `ProgramImagePreview`（Coil の `LocalAsyncImagePreviewHandler`）で単色に差し替える
- **回の画像と再生まわり**: 回の画像（各回のファイルに埋め込まれた画像）は走査では読まず、再生に入った回の分だけ `EpisodeImage`（`MediaMetadataRetriever.embeddedPicture`。無ければ番組の画像のファイル）が読む。
  長辺 512px 以下に縮め（透過があれば PNG、無ければ JPEG 85）、`EpisodeImageAttacher`（`PlaybackService` が付けるリスナー）が聴く回に替わるたびに現在の項目を `replaceMediaItem` で `setArtworkData` 付きに差し替える（キューの全件は読まない）。
  通知・ロック画面・Auto の再生中の表示と、再生画面（`PlayerViewModel.episodeImage`。Coil に ByteArray を渡す `EpisodeImageView`）は、この `artworkData` を読む。ContentProvider は足さない。
  Auto のブラウズツリーの各項目（`BrowseTree`）には画像を出さない。Media3 が再生中のファイルの埋め込み画像を自分で `Player.mediaMetadata` に載せるかは確かめていない（未確認）。
  差し替えで音が途切れず再生位置も戻らないことは、release ビルドで実機を使って確かめた。ExoPlayer が同じ URI の項目の更新を作り直しなしで受けているか（内部の挙動）は確かめていない
- **チャプター**: 各回のファイルに埋め込まれたチャプターは、`PlayerViewModel` が `MediaController.currentTracks` の音声トラックの `Format.metadata`（media3 の `Chapter`、`@UnstableApi`）から読み、`PlayerUiState.chapters` に載せる（ADR 0004 が、再生画面は `MediaController` を購読するとしている範囲。`PlaybackService` 側では読まない）。取り出す処理は純粋な関数に分けた（`ui/player/EpisodeChapters.kt`）: `chaptersOf(Tracks)` は `isHidden` を除き開始の順に返し、`currentChapter(chapters, positionMs)` は開始が位置以下のうちで最後のもの（最初の開始より前なら無し）を返す。再生画面は同じプロセスの controller なので `Format.metadata` がオブジェクトのまま届く見込みだが、実機では確かめていない（未確認）。

## 突合と取り込み

- **突合**（`CONTEXT.md`「突合」、ADR 0001）の対象は**未結合**の行: 取得元 ID を持たない行と、持っている取得元 ID が今回の完全な一覧に無い行。`LibraryMatching.match` が行い、キーは番組が (配信元, 番組名) = (`AlbumArtist`, `Name`)、各回が同じ番組内の `Name` 完全一致。双方 1 対 1 でなければ結ばない（候補が複数なら新規行）。表記ゆれは吸収しない（表記を揃えるのは取得元の側、つまりラジオ録音ツールやタグ付けの責務）。閾値付きの自動結合も利用者の確認画面も作らない。
  結び付けたら `sourceItemId` を書き換えるだけで `LocalFile` / `PlaybackState` は触らない。突合は新しい番組の挿入より前なので、再取り込み後に番組が二重になることは無い
- **第二段**: タイトルで結べなかった未結合の各回に対し、同じ番組内で **公開日（日単位）が同じ ＋ 尺の差が 5 秒以内** の候補が双方 1 対 1 なら結ぶ。尺 0（不明）は対象外。同日に複数本あって尺で絞れなければ結ばない。古い ID の行にも同じ第二段を適用する。結んだ後は取得元の値で上書きする（タイトルは `YYYY-MM-DD (1)` のようになる）。`EpisodeKeyRow` / `LocalEpisodeKey` に `publishedAt` と `runtime` を持たせる。再取り込みと同時にタイトルを付け直したケースの保険
- **取り込み**: 取得元由来の各回は取得元の値で上書きする（タイトル・公開日・取り込み日時・尺・サイズ・コンテナ）。`LocalFile` と `PlaybackState` は触らない
- 同期との順序は、全体同期（`refresh`）も 1 番組の同期（`syncProgram`）も、取り込み（突合を含む）→ `planSync` の順。結び直せなかった古い ID の各回だけが `remove` に落ちる（本当に取得元から消えた回）。1 番組の同期で番組が見つからない（`ids=` 検索が空）ときは番組一覧が無いので結び直さず判断保留
- **ライブラリ切替**: 同じサーバでライブラリだけを変えるときは手元の行は触らない。旧ライブラリの番組は判断保留と同じ扱いになり、同期で未結合になった行は突合で結び直す（ADR 0010）
- **消失**: `programs.goneSince` を、全体同期で「番組一覧に無く突合でも結べなかった」番組に立て、見つかれば（結び直しを含めて）null に戻す。1 番組の同期で番組が見つからなければ立てる。消失した番組は手元の回をそのまま聴ける。画面での見せ方は [ui.md](ui.md)
- **手元から消せる番組**: 「この番組を手元から消す」（番組・各回・ファイル・再生位置をすべて消す）は、**消失した番組と取得元 ID の無い番組だけ**に出す（取得元に在る番組を消しても次の同期で戻り、再生位置だけ失うため）

## 同期

### 同期エンジンの設計

同期ロジックは `:core:domain` の **副作用のない関数**（`planSync`）として書き、ユニットテストで網羅する。
I/O（HTTP・ファイル・DB）はこの関数の外側に置く。

```
入力:  番組ごとの { 保持ルール, 同期対象か,
                    取得元の各回一覧: Known(list) | Unavailable | Gone,
                    手元の各回集合（取得元 ID・固定フラグ・再生済み・手元のファイルの有無） }
出力:  番組ごとの { ダウンロード対象, 削除対象, 除去対象, 判断保留か }
```

規則:

- `Unavailable`（その番組の一覧が取得できなかった）→ その番組は**判断保留**。ダウンロードも削除も出さない。「一覧が空」とは型で区別する。全走査なので、番組ごとの `Unavailable` になる経路は今は無い
- `Gone`（取得元の番組一覧に、その番組の取得元 ID が無い）→ 同じく**判断保留**。「番組が無い」は再取り込み・整理であって「各回を消した」ではない（ADR 0001 の守りたいシナリオ）。新しい取得元 ID への突合は ADR 0001 に従う
- 同期対象でない番組 → 保持ルールを適用しない。固定された各回だけが手元に残る。関数は `syncEnabled = false` でも動く: `download` は空、`delete` は非固定の手元ファイル全部、`remove` は取得元から消えた `sourceItemId != null` の回（固定含む）。`syncEnabled` が効くのは保持ルールの適用だけで、`Unavailable` / `Gone` は `syncEnabled` に関わらず全部空 + 判断保留
- **保持ルールは独立した削除理由**。「最新 N 回まで保持」と「再生済みなら削除」のどちらかに該当すれば手元に置かない（保持は AND）。「最新 N 回」は再生済み・未再生を問わず公開日の新しい順、
  同着（公開日は日単位なので同日パートで起きる）は各回のタイトルの辞書順 → ローカル ID で安定ソート。この順序は各回一覧・連続再生と共通（`EpisodeOrder`）
  番組一覧の並びは「最新の各回の公開日が新しい順、同着は番組名の順」（`ORDER BY latestPublishedAt DESC, p.name ASC`。`latestPublishedAt` は `MAX(e.publishedAt)`）。各回一覧は公開日の新しい順（同着はタイトルの辞書順）
- **固定**された各回は保持ルールの対象外で、N に数えない（別枠。`keepLatest` は固定を除いた公開日の新しい順で数える）。利用者が固定を外すか手動削除するまで残る（例外は取得元の一覧から消えた回。次項）
- 取得元の一覧（`Known`）から消えた各回は、固定でも手元から `Episode` 行ごと削除する（`remove`。`LocalFile` / `PlaybackState` は cascade、ファイルも消す）。取得元が各回の存在の正、手元はキャッシュ。
  ただしこの規則が届くのは **`Known` の番組に属し、かつ `sourceItemId != null` の各回だけ**。取得元 ID を持たない各回は「取得元に在る」と主張したことがないので対象外。`Known` の番組で各回が 0 になっても番組は残す
- 保持すべき集合 − 手元の集合 → ダウンロード対象（再生済みの回は保持すべき集合に入らないので落ちない）。`hasLocalFile` は PENDING / RUNNING / DONE / FAILED のいずれかの行があること（**FAILED も「手元にある」**）
- 保持ルールによる削除（`delete`）は「ダウンロード」節の削除の規則に従う（`sourceItemId` がある回はファイルだけ、無い回は `Episode` ごと）

#### 実行側の規則

`planSync` の外の規則。

- **同期の 1 回** = 全走査（`SourceGateway.fetchAll`。Jellyfin では `fetchLibrary`）→ 取り込み（突合・上書き）→ `planSync` → 削除の実行 → ダウンロード対象を `PENDING`（`pinned = false`）で enqueue → `DownloadScheduler.kick()`。転送は `DownloadWorker` に任せ、同期は自分でファイルを落とさない。**削除の権限を持つ入力は全走査だけ**（ADR 0003）
- 削除対象が PENDING / RUNNING / FAILED なら `cancel` 相当。**聴いている回（現在の `MediaItem`）はその同期では削除しない**（次回に持ち越す。ただし最後まで聴き終えて止まっている回は除外しない。消された後に ▶ を押すと再生に失敗し、キューを空にして理由をスナックバーに出す）。実行順は削除 → enqueue。同期分の enqueue 順は **番組をまたいで公開日の新しい順**
- **入口は `LibraryRefresher` に一本化**する（`synchronized(lock)` と走っている実行 `Run` で同時に 1 つに保つ。`Mutex` は使っていない）。バックグラウンドの実行の最中に来た手動の呼び出しはその実行に合流し、それ以外の重なった呼び出しは何もせず null を返す。ただし同期のシートを閉じたときの 1 番組の同期だけは、合流も捨てもせず、走っている実行が終わるのを待ってから走る。定期 `SyncWorker`・起動時・番組一覧の手動プルがすべてここを通る（各回一覧の手動プルと、同期のシートを閉じたときの 1 番組の同期も）
- **番組一覧の「引っ張って更新」= ライブラリ全体の手動同期**（`planSync` まで回す。番組の画面のどのタブで引っ張っても同じ）。**各回一覧の「引っ張って更新」= 1 番組の同期**（`syncProgram`）: `/Items?ids=…` で番組の存在を確かめ（空 → 消失 = 判断保留）、その番組の各回一覧を取り、取り込み → `planSync`（この番組だけ）→ 除去・削除・予約。全走査を待たずに終わる。他の番組には触れず、最終同期の時刻も更新しない（ADR 0003）。同期を OFF にした番組でも同じで、固定していない手元のファイルは消える。番組の行が無い・取得元 ID が無い番組は全走査に切り替わる（`LibraryRefresher.syncProgram(orFullSync = true)`）。番組一覧・各回一覧の引っ張って更新は `applicationScope` で走らせ（`LibraryRefresher.launchRefresh` / `launchSyncProgram`）、画面を離れても最後まで続く。設定画面の「最終同期」の値は `lastFetchedAt`（1 番組の同期は更新しない）
- **「Wi-Fi のみ」は同期にも効く**（手動を含む。取得元が端末のフォルダなら効かせない。domain の `wifiOnlyApplies`）。`SyncWorker` の制約は `DownloadWorker` と同じ（UNMETERED / CONNECTED、充電中は課さない。同期自体の通信は一覧の JSON だけで軽く、転送は `DownloadWorker` の制約で守られる）。手動は `LibraryRefresher` で `ConnectivityManager.isActiveNetworkMetered` を見て、従量制なら「Wi-Fi に接続していないため更新しません」で終える（裏で待たせない）。判定は全走査・番組単位・起動時・定期のすべてが通るが、**ゲートは取得元への取得だけを包む**: 先頭の `reconcileMissingFiles()`（ローカル I/O のみ）は従量制でも走らせる
- **起点**: 定期は `PeriodicWorkRequest` 3 時間ごと（`UPDATE` で起動時に登録し直す）。起動時は `ProcessLifecycleOwner` の `ON_START` で、前回同期から 15 分以上（`lastFetchedAt` と `lastAttemptedAt`（失敗した全走査も数える。全走査が取得元に問い合わせる直前に成功・失敗を問わず記録）の新しい方から数える）なら `OneTimeWorkRequest`（ユニーク `sync-once`、`KEEP`）を出す。同じ制約で待つ。定期・起動時の失敗は `Result.success()` で終え次回を待つ（スナックバー無し、Log のみ）
- **周期と閾値の根拠と電池の最適化**: 必須の要求は「アプリを開いたら、新しい回が手元に来ている（来はじめる）」で、開かずに手元に来るまでの遅れも縮めたいため、周期を 3 時間、起動時の閾値を 15 分にした。App Standby のバケットが RARE になりうるので、定期の同期は遅れうる（どれだけ遅れるかは未確認）。そのため設定の「ダウンロード」の節に、電池の最適化の対象のときだけ「電池の最適化を外す」の行を出す（`PowerManager.isIgnoringBatteryOptimizations`。`ON_RESUME` のたびに判定し直す。押すと `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` で OS の確認を出す。`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を Manifest に足した。Play を通さずに配るので方針の縛りは受けない。ADR 0005）。やらないこと: 録音時刻に合わせて起こす・取得元からの知らせ（webhook）を受ける（端末に外から届く経路が要り、FCM は非目標）、「次の予定」の時刻の表示（RARE では予定どおりに走らないことがあり誤解を招く）、同期の試みを数日分残す記録（「最終同期」の行に任せる）
- **同期の表示と結果**: 走っている間は裏の同期か操作による同期かを `LibraryRefresher.isSyncingBackground` などで区別し、手動の操作が合流したら裏の表示から操作の表示に切り替える。共有フォルダの全走査のタグの読み込みの進み具合は `LibraryRefresher.tagProgress`。結果は変化だけを出す: 新しい回（取り込みで手元に新しく増えた各回。突合で結び直した回は数えない。`RefreshResult.newEpisodes`）・ダウンロード予約・削除の数、判断保留の番組があればその数。0 は省く。手動は変化が無ければ「最新の状態です」、裏の同期（定期・起動時）は変化があったときだけ出す（`messages` を collect している番組一覧・各回一覧を開いているときだけ。それ以外の画面にいる間や、閉じている間の成功した裏の同期の結果は後から出さない）。`RefreshResult` に `enqueued` / `deleted` / `onHold` を持つ。文言と見せ方は [ui.md](ui.md)
- **失敗の記録と通知**: 全走査の最後の試み（成功・失敗とその理由・「Wi-Fi ではない」の見送り）と時刻を、手動でも裏でも `LibraryRefresher` が `SessionRepository.recordSyncAttempt` で DataStore に記録する
  （`SessionState.Ready.lastSyncAttempt`。`lastAttemptedAt` とは別の値で、`SyncScheduler.isStale` は変えない。1 番組の同期・401・取り消しは記録しない）。設定の「最終同期」の行は、
  それが `lastFetchedAt` より新しく成功でないときに補足の行を出す。裏の同期（`SyncWorker` → `LibraryRefresher.refreshInBackground`。手動が合流したら手動として扱う）が失敗で終わり、
  `lastFetchedAt` から 24 時間（`SyncWorker.NOTIFY_AFTER`）以上たっていれば、同期の失敗の通知（`sync/SyncFailureNotifier.kt`、チャンネル `sync`、`IMPORTANCE_LOW`）を 1 回出す。
  出したことを記録し（`syncFailureNotified`）、全走査が成功するまで出し直さない。成功すると通知と記録を消す。ログアウトと取得元を変えるときも消す（記録は同期の時刻と一緒に `SessionStore` で消え、
  通知は `LibraryRefresher.cancelAndAwait`（走っている実行の取り消しを待った後）と、401 でログアウトする 3 か所（`LibraryRefresher`・`DownloadWorker`・`LibraryPickViewModel`）で消す。
  `SyncWorker` も頭で、記録が無いのに通知が残っていれば消す）
- **手動削除と同期の往復は仕様として受け入れる**: 同期対象の番組で保持すべき回のファイルを手で消しても、次の同期で落とし直される。削除時にそれを知らせる（文言は [ui.md](ui.md)）。聴かない回は再生済みにするのが正規の操作。「除外」状態は作らない
- **同期対象 OFF**: シートを閉じたときの 1 番組の同期でその番組の非固定ファイルが全部消える（`CONTEXT.md`「固定」の定義どおり）。OFF に切り替えるとき手元に非固定の回があれば確認を出す
- **保持ルール編集**: 各回一覧の同期のシートで、同期対象のスイッチ、「最新 N 回まで保持」（選択式 1 / 3 / 5 / 10 / 20 / 上限なし）、「再生済みなら削除」のスイッチ（後の 2 つは同期 OFF で無効）を編集する。
  ON に切り替えるたび `keepLatest` が null なら **既定 3** を入れる（「上限なし」にしたい場合は ON にした後で選ぶ。OFF → ON を往復すると 3 に戻る。「未設定」と「上限なし」を区別する列は足さない）。
  スイッチやチップを触った時点では保存するだけで同期しない。シートを閉じたとき、開いたときから同期の設定（ON / OFF、最新 N 回、再生済みなら削除）が変わっていれば、その番組の 1 番組の同期（`LibraryRefresher.launchSyncProgramAfterRunning`）が走る
  （閉じる経路は `onDismiss` でも「番組を手元から消す」の確認への遷移でも、開いたまま画面が作り直されたとき（回転など）でもよい。`EpisodeListContent` の `DisposableEffect` で伝える。OFF のときは保持ルールを比べないので、OFF → ON → OFF と戻して閉じても走らない）。
  走っている同期（手動・裏）があれば、合流も捨てもせず終わるのを待ってから走る（待っている間に `cancelAndAwait` されたら走らせない）。`applicationScope` で走り、画面を離れても続く。結果の文言は引っ張って更新と同じ。
  `sourceItemId` の無い番組はスイッチを無効にする（取得元に見つかっていないため同期できない）
- Room のスキーマ: `syncEnabled` / `keepLatest` / `deleteAfterPlayed` は番組の行にある。`Episode.addedAt` は差分取得を作らないので読み手が無いが、列は残す（取り込み日時の表示の補助に使える。消すならスキーマ変更が要る）

## ダウンロードと差し替え

### ダウンロード

- **転送**: `SourceGateway.openDownload(episodeId, rangeStart)`（Jellyfin の実装は `JellyfinGateway.openDownload(credentials, episodeSourceId, rangeStart)`）。SDK にはストリーミング取得の API が無い（`getDownload` / `request` は本文を `byte[]` に全部読む）ので、**URL は SDK の `getDownloadUrl`、`Authorization` ヘッダは SDK の `AuthorizationHeaderBuilder` に作らせ、転送だけ OkHttp（SDK の依存に同梱）で行う**。エンドポイントと認証形式は手書きしない。
  戻り値は `resumedFrom`（206 で `Range` が効いたか）・`totalBytes`・本文ストリーム。サーバが `Range` を無視して 200 を返したら `.part` を書き直す。テストはフェイク（206 再開／200 全体再送の両方）
- **Worker**: WorkManager のユニーク Worker（`download-queue`、`KEEP`）が、`LocalFile.state = PENDING` または `replaceState = PENDING` の行を **1 本ずつ**処理する。順は `nextPending()` の `(state = PENDING かつ pinned) DESC, enqueuedAt IS NULL, enqueuedAt ASC, publishedAt DESC, title ASC, id ASC`: 手動の予約（固定した回の新規ダウンロード）が先で、そのなかはキューに入れた順（FIFO）。差し替えは同期の予約なので、固定した回の差し替えでも手動の予約の後に並ぶ。`enqueuedAt` は nullable なので NULL を先頭に来させない。再試行は列の末尾へ。
  `<配信元>/<番組>/<タイトル>.<container>.part` に追記し、完了でリネームする。既存の `.part` は `Range: bytes=<size>-` で再開する。進捗は `setProgress` で UI へ出す。通知は出さない（フォアグラウンドサービスにしない）。
  同期分（`pinned = false`）の行に利用者が「ダウンロード」を選んだら `pinned = true` にする
- **条件**: 設定の「Wi-Fi のみ」（既定 ON、`AppSettings` DataStore）。ON なら `UNMETERED`、OFF なら `CONNECTED`。待ちの間は「Wi-Fi 待ち」と表示する。取得元が端末のフォルダならネットワークの条件をかけない（`NOT_REQUIRED`）。充電中の条件は課さない
- **失敗**: 1 本失敗しても次へ。`attemptCount` +1、`lastAttemptAt`、`FAILED`。同一実行内では再試行しない。次の起動で `attemptCount < 3` を PENDING に戻す。3 回超えは手動の再試行だけ。401 は `LibraryRefresher` と同じくログアウト
- **キャンセル**: DONE 以外の行を消し `.part` も消す。Worker は約 1 MB ごとに DB を見てスキップする
- **行の操作**（各回一覧。画面は [ui.md](ui.md)）: 右端のアイコンは行の状態を表し、タップで最も自然な 1 操作を行う（雲 → ダウンロード（= 固定）、進捗リング → キャンセル、警告 → 再試行、手元にある回は再生済み切替）。長押しのシートに、固定する／固定を外す／ファイルを削除／再生済み切替／ダウンロード／詳細がある。
  「固定を外す」は同期対象の番組の固定中の回だけに出す（同期対象でない番組で外すと次の同期で消えるため）。「固定する」は手元にあり（`DONE`）固定していない回に出す（番組が同期対象かは見ない）。ファイルは落とし直さず、`local_files.pinned` を立てるだけ
- **削除の規則**（手動削除・保持ルール・消えたファイルの整合で共通）:
  - `sourceItemId` がある回: ファイルと `LocalFile` 行だけ消す。`Episode` / `PlaybackState` は残す（落とし直せば続きから）。
    ただし**取得元の一覧から消えた回**は落とし直せないので `Episode` ごと消す（`remove`。固定でも。突合で結び直せなかった回に限る）
  - `sourceItemId` が無い回（取得元を経由していない回）: 二度と手に入らないので `Episode` ごと消す（`PlaybackState` は cascade）。各回が 0 になった `sourceItemId` 無しの番組も消す
- **消えたファイルの整合**: 同期・更新の開始時に `DONE` 行を全走査し、再生開始時にも存在を確認する。無ければ上記の削除規則を自動で適用し、「ファイルが見つかりません」と知らせる
- **完了時**: `LocalFile(DONE, path, pinned = true, downloadedAt)`、`Episode.sizeBytes` を実バイト数で更新する。同期によるダウンロードは `pinned = false`
- **命名**: 配信元 null は `_`、`container` 空は `m4a`、禁止文字（`/ \ : * ? " < > |`）は `_`、同名衝突は ` (2)`
- 「手元 M / 全 N 回」の M は `DONE` のみ。手元に無い回は再生できない（ストリーミング再生は行わない）。手元に無い各回は一覧に同じ並びで薄く表示し、タップしても再生画面へ行かない
- 置き場所: `getExternalFilesDir("episodes")/<配信元>/<番組>/<ファイル>`（共有フォルダの構成（ADR 0013）と同じ階層）

### 差し替え

手元にある回（`local_files.state = DONE`）のファイルが取得元で書き換わったら、同期のときに見分けて新しいファイルに替える（`CONTEXT.md` の**差し替え**。ADR 0011）。取得元の種類によらず同じ規則。

- **ファイルの版**: 共有フォルダは大きさと更新日時の組、Jellyfin は一覧の `Etag`（`SourceEpisode.version`、`episodes.sourceVersion`）。
  ダウンロードを予約するとき（同期の予約も手動の予約も）に一覧のファイルの版を `local_files.sourceVersion` に写し、落とし終えてもそのまま持つ（落とし始めた後に取得元が変わっても、次の同期で差し替えに回る）
- **見分け方**（`:core:domain` の `Replacement`。`RoomLibraryRefreshRepository` が同期の削除の後・予約の前に回す）: 一覧のファイルの版が null の回は比べない。
  落としたときのファイルの版と違えば、一覧が大きさを返す取得元（共有フォルダ）は差し替える。返さない取得元（Jellyfin）は取得元の大きさを問い合わせ（`SourceGateway.fetchFileSize`）、
  手元の大きさと違えば差し替え、同じならファイルの版だけ書く。ファイルの版を持たない回（差し替えの仕組みより前に落とした回）は大きさだけで比べ、同じなら今のファイルの版を書く。
  手元の大きさは、ディスク上のファイルの長さ（共有フォルダでは `episodes.sizeBytes` が走査のたびに一覧の値で上書きされるため）
- **聴いている回**（`NowPlaying.excludedFromSync`）はその同期では予約しない
- **予約と実行**: `DownloadRepository.enqueueReplacements` が `replaceState = PENDING` にし、残っている `.part` を捨てる。行は DONE のまま（今のファイルで聴け、一覧の表示も変わらない）。
  予約した数は同期の結果の予約数（`RefreshResult.enqueued`）に含める。
  普通のダウンロードと同じ待ち行列（`LocalFileDao.nextPending`）と条件（`DownloadWorker`）で落とす。手動の予約が先で、差し替えは同期の予約と同じ並び
- **落とし方**（`EpisodeDownloader`）: いつも頭から `.part` に書き、落とし終えたら今のファイルの場所へ `Files.move`（置き換え）。ファイルの版と `episodes.sizeBytes` と `downloadedAt` を新しくする。
  落とし終えた時点でその回が聴いている回なら差し替えずに `.part` を捨てる。失敗したら `.part` を捨て、今のファイルとファイルの版を残す（試行回数は数えない）。どちらも次の同期でまた予約される。
  差し替えの間に手元の回を消す（保持ルール・手の削除）と、行と `.part` が消え、Worker は取り消しとして止まる
- **実行側の口**: ダウンロードの実行側（`DownloadQueue`）に、差し替えの開始・完了・断念・まだ要るかの確認・残った実行中の戻しの口を持つ
- `playback_states` には触らない（再生位置と再生済みは変わらない）

### 手元のファイルの整理

- **取得元に見つからない番組をまとめて消す**: 対象は上の「手元から消せる番組」と同じ範囲で、0 番組なら操作を出さない。確認では番組の数・手元の回（`DONE` でパスがある回）の数・うち固定の数を出し、固定した回や再生途中の回も区別せずに消す（`DownloadRepository.removeUnmatchedPrograms` が番組ごとに `removeProgram` を呼ぶ）。聴いている回（`NowPlaying.excludedFromSync` と同じ定義）の番組だけは消さずに残して知らせる。同期の間（`isRefreshing` か `isSyncingInBackground`）は操作を無効にする。画面は [ui.md](ui.md)
- **手元のファイルの内訳と、選んだ回を消す**: 内訳の画面（`StorageScreen`）の手元の回は `DONE` でパスのある回（`LOCAL_FILE_PRESENT`、合計の `observeUsage` と同じ定義）で、容量は各回の `sizeBytes` の合計（ストレージ上の実際のファイルサイズは測らない）。番組ごとの内訳は重ならない 3 つ（固定・固定でない再生済み・固定でない未再生。`LocalFileDao.observeProgramStorage`）で、全部の回の一覧は `observeLocalEpisodes`。番組ごとの手元の回の一覧は `StorageProgramScreen`。
  消すのは `LocalStorageRepository.deleteLocalEpisodes` で、今も手元にある回だけを 1 回ずつ `DownloadRepository.deleteLocal`（`deletionScopeFor` の規則）で消し、消す時点の聴いている回は除いて知らせる。固定は `local_files.pinned` が持つので、固定の回を消すと次の同期では非固定として扱われ、「最新 N 回」に入れば落とし直され、別の非固定の回を枠から押し出しうる（`SyncPlanner.planProgram`）。どの回が落とし直されるかは計算しない。同期の間は消すボタンを無効にする。「すべて選ぶ」と、条件でまとめて消す操作は作らない。各回一覧のシートの「ファイルを削除」（確認なし・聴いている回を除かない）は別の操作。画面は [ui.md](ui.md)。`LocalStorageRepository` の束ね（`@Binds`）は `di/DataModule.kt` ではなく `repository/RoomLocalStorageRepository.kt` の `LocalStorageModule` に置く

## 再生

### 再生の仕組み

- 連続再生は同じ番組内で **公開日の古い順** に次の回へ進む。再生済みの回は飛ばさない。手元に無い回は飛ばす（キューは手元にある回だけ）
- 各回を開いた時点で、その番組の手元にある全各回（古い順）を Media3 のプレイリストとして `MediaSessionService` に積み、選んだ回を開始インデックス・再開位置で `setMediaItems` する。
  `mediaId` = ローカル `episodeId`。Media3 のプレイリストは先頭の回にしか開始位置を持てないので、次／前・自動遷移で別の回に入ったときはサービス側で保存位置（再開位置の規則）へシークする
- 操作は再生／一時停止／シーク／±10 秒スキップ／前後の回へ移動。倍速とスリープタイマーがある。スリープタイマーは「一時停止中は減らず、再開すると続きから」。「この回の終わりまで」は ExoPlayer の `pauseAtEndOfMediaItems` で、回の終わりで止めたら解除する（次の回に持ち越さない）。途中の回で止めたときは `STATE_ENDED` にならないので、`NowPlaying.isEnded` は「回の終わりで止めた」も見る（削除から外さないため）
- **再生位置の保存**: 一時停止・停止・回の切替時（`onMediaItemTransition` の `oldPosition`）と再生中 10 秒ごと。ただし行が無く、位置 5 秒未満・未再生（`PlaybackRules.NOT_STARTED_THRESHOLD`、聴き始めていない）なら書かない。プロセスの強制 kill で最大 10 秒戻るのは許容する（定期保存は壁時計なので、倍速なら音源時間で最大 10 秒 × 速度）
- **MediaSessionService**: 通知・ロック画面操作。Android 13+ の `POST_NOTIFICATIONS` ランタイム権限を要求し、`AndroidManifest` に `foregroundServiceType="mediaPlayback"` と `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 権限を置く（Android 14+ の FGS 制約）

### 聴いている回の読み方

一覧の聴いている回の印とミニプレイヤーは「聴いている回」を知る必要があるが、`MediaController` の購読は採らず、プロセス内のシングルトン `NowPlaying` を読む（ADR 0004）。

- 聴いている回は `PlaybackService` が `Player.Listener` で `@Singleton` の `NowPlaying`（`StateFlow<NowPlayingState?>`、`:app`）に書き、同期の実行側もそれを読む（Worker と Service は同一プロセス。MediaController を Worker から結ばない）
- 再生・一時停止などの**操作**だけは `PlayerConnection` の `MediaController` を使う。`MediaController` はサービスに bind するので、持っている限り `stopSelf()` してもサービスは破棄されない。そのため `PlayerConnection` は利用者を数え、最後の利用者が離れたら controller を解放して bind を外す（再生画面は画面の間だけ、ミニプレイヤーは操作のあいだだけ持つ）。プロセスの寿命で持つと、一時停止してタスクを消しても `onTaskRemoved` → `stopSelf()` が効かず、聴いている回が消えない
- 聴いている回が利用者の操作なしに消えた理由（再生の失敗）もサービスから UI へ渡す（`NowPlaying.messages`）。状態ではなく 1 回きりの通知だが、プロセス内で受け渡す点は同じ
- 一覧とミニプレイヤーの**読み**だけがこの経路。再生画面（`PlayerViewModel`）は再生を始める画面なのでサービスが生きていて当然で、位置の追従も要るため `MediaController` を購読する
- Android Auto は車載機からアプリの外で `MediaLibraryService` に bind するので、UI を開かずにサービスが起動する。プレイヤーが空のあいだ `NowPlaying` は null のままなので、一覧とミニプレイヤーの「何も載っていなければ出さない」判定は変わらない。Auto が bind している間は `onTaskRemoved` → `stopSelf()` が効かないが、車が操作権を持っている状態なので意図どおり
- **再開**: プレイヤーが空のとき（プロセスが死んだ後の通知の ▶、Auto の「最近」、端末の再起動の後）だけ Media3 が `onPlaybackResumption` を呼び、`PlaybackService` は Room の続きから聴く回（`LibraryRepository.getContinueListening`）から番組のキューを復元する。「聴いている回」は、復元した回がプレイヤーに載った時点で立つ（Room に「聴いている回」を書くのではなく、再生位置の記録から組み立て直す）
- Media3 は `MediaButtonReceiver` の Manifest の宣言があるときだけ再開（SystemUI の `isRecent` と `onPlaybackResumption`）を有効にする（`MediaSessionLegacyStub.canResumePlaybackOnStart`）。SystemUI の `isRecent` はアプリの `onGetLibraryRoot` を通らず Media3 が自前のルートを返すので、コールバックの側では制御できない
- 前提: アプリは単一のプロセスで、Service・Worker・UI が同じ `NowPlaying` のインスタンスを見る。マルチプロセスにするときは ADR 0004 を見直す

### 再生位置と再生済み

- 取得元が Jellyfin の**ブックのライブラリ**のときは、**再生の共有**（ADR 0010）。それ以外の取得元では手元だけで持ち、送らない
  - 衝突の規則（`:core:domain` の `PlaybackSharingRules`）: 各回ごとに「最後に見たサーバの値」（位置・再生済み・`LastPlayedDate`）を手元に持つ。
    サーバが変わっていればサーバの値、サーバが変わらず手元が変わって（未送信の印が立って）いれば手元を送る。両方なら手元の更新時刻とサーバの `LastPlayedDate` の新しい方
    （サーバの時刻が無い・同じ時刻なら手元）。最後に見た値が無い回は、誰も再生していない値（位置 0・未再生・時刻なし）を見たとみなす
  - 未送信の印は、手元で位置か再生済みが変わると立つ（`RoomPlaybackStateRepository.update`）。突合で取得元 ID が変わった回も立て、前の ID で見た値は忘れる。この列を足した移行の時点で手元にある行も立つ（列の既定が 1）
  - 送る: 一時停止・停止・回の終わり・再生済みの手動切替の後（`PlaybackSharePusher`。未送信の回のうち鳴っている回を除いたもの。再生サービスが動いている間だけ）。
    送る前に 1 回ずつサーバの値を読んで衝突の規則を通し（サーバの方が新しければ送らずに採る）、送信は同期の送信とも直列にする（`PlaybackSharing.sendPending`）。
    読めなかった・送れなかった分は次の同期で送る
  - 読む: 同期のたび（取り込みの後、`SyncPlanner` の前。ほかで再生済みになった回も保持ルールの対象）。聴いている回（`NowPlaying.excludedFromSync`）は手元のまま。
    ▶ の直前（`ReadBeforePlayPlayer`。一時停止からの再開を含む）と、次／前・自動遷移で別の回に入ったとき（`ResumeOnTransition`）にも 1 回だけ読み、サーバの値を採るならその位置へ移る
  - 共有の状態は、最後に共有できた時刻と、それより新しい送信の失敗を設定に出す。通知は出さない（画面は [ui.md](ui.md)）
- **再生済み**の判定（`:core:domain` の純粋関数）:
  - 再生位置が **末尾から残り 2 分以内**に達したら自動で `played = true`。ただし位置 0 では判定しない
    （尺が 2 分以下の回は「再生が少しでも進んだら再生済み」。閾値を尺でスケールさせない）
  - 一度 true になったら、シークで戻しても自動では false に戻らない
  - 手動で再生済み／未再生を切り替えられる。**手動切替は再生位置を変えない**
- **再開位置**（`:core:domain` の純粋関数）: 再生開始時は「残りが 2 分以下なら先頭から、そうでなければ保存位置から」。再生済みフラグは見ない。最後まで再生し終えた回は位置 = 尺を保存する。
  「再生開始」は各回をタップしたとき（止まっている回を含む）と、次／前・自動遷移で別の回に入ったとき。再生中の回の画面に戻っただけのときは触らない
- 再生済みフラグと再生位置は互いに書き換えない。両方を読んで決めるのは上記の再開位置と「聴き始めていない」だけ

## UI の文言（i18n）

UI の文言は英語と日本語の 2 言語で、`res/values/strings.xml`（英語、既定）と `res/values-ja/strings.xml`（日本語）に置く（ADR 0006）。文言の足し方（キーの名前・書式）は [ui.md](ui.md) の「文言の足し方」。

- **文字列に解決するのは Composable だけ**。Composable では `stringResource` / `pluralStringResource`。Composable の外で文言を作る場所（`LibraryRefresher` のスナックバー、`NowPlaying.messages`、ViewModel の `uiState.error`、`EpisodeDetails` の行のラベル）は `String` ではなく `UiText` を返し、表示する Composable が `resolve()` する。`Context.getString` は `ui/` の外に書かない
- **`UiText`**（sealed interface）: `Res(@StringRes id, args)` / `Plural(@PluralsRes id, quantity, args)` / `Plain(text)`（サーバから来た名前など、翻訳しない文字列）/ `Joined(parts, separator)`。型は Compose に依存せず、`ui/` の外（`sync/`・`playback/`）からも使うので `dev.tseki.kikidame.text`（`text/UiText.kt`）にある。`resolve()` は Compose と `Context` に依存するので `ui/UiTextResolve.kt` にある
- `resolve(Context)` は、`ui/` の Composable から呼ぶ Composable でないラムダ（`LaunchedEffect` の中でスナックバーに渡すとき。`.resolve(LocalContext.current)`）のためのもので、`ui/` の外では使わない
- **例外**: プロセスの外（システムの UI・車載機）へ `String` を渡すしかない次の 2 か所だけは `getString` する
  - `playback/PlaybackService.kt`: 通知のボタン名と Android Auto のブラウズツリー
  - `sync/SyncFailureNotifier.kt` の `AndroidSyncFailureNotifier`: 同期の失敗の通知。通知を組み立てる 1 か所で `getString` し、そこまでは `UiText` で持つ（`LibraryRefresher` と `SyncWorker` は失敗の理由を `UiText` で渡す）
- **テスト**: `UiText` を返す関数は `UiText.Plural(R.plurals.sync_new_episodes, 5)` のような値で比べ、素の JVM で済ませる。並びや省略の規則を見るテスト（行の補足と各回の詳細）は、Robolectric の `@Config(qualifiers = "ja")` で日本語に解決した文字列で比べる
- **言語**: 既定は英語（日英以外の端末には既定が出る）。切り替えは OS の「アプリの言語」（Android 13+、`generateLocaleConfig = true`）だけで、アプリの中の切り替えは置かない（AppCompat を足すことになるため）。Android 12 はシステムの言語に従う。3 言語目は `values-<lang>/strings.xml` を置くだけで OS の言語一覧に載る。翻訳の受け入れの仕組み（Weblate など）は需要が見えてから用意する
- **lint**: `MissingTranslation` を lint のエラーにして、片方の言語だけに足すことを防ぐ。`values/strings.xml` には、英語だけでは文脈が分からないキーに翻訳者向けのコメントを付ける
- **書式**: 日付（`2026-08-02`）・尺（`1:02:03`）・容量（`12.3 MB`）の書式はロケールによらず固定。資源にするのは区切り（`common_list_separator`「、」/ ", "）と単位だけで、数 + 単位は `<plurals>` にする

## やらないこと

- 映像の再生
- トランスコード
- サーバ管理機能
- キャスト（Chromecast）
- 複数サーバ・複数ユーザー・複数ライブラリ
- ブックのライブラリのほか（音楽のライブラリ・共有フォルダ・端末のフォルダ）での、他端末・Web との再生位置の共有（ADR 0010 の決定 1。取得元には書き込まないため。例外はブックのライブラリの UserData だけ。ADR 0007 の決定 6）
- Media3 DownloadManager / SimpleCache によるダウンロード
- `/Sessions/Playing/*` による「再生中」のサーバ通知
- オーディオブック向けの並び（古い章から順に聴くので「最新 N 回」が合わない。番組に「向き」を足す設計が要る。`CONTEXT.md`「番組型の音声」）
- Google Play での配布と課金（ADR 0005）

## 参照

- `CONTEXT.md` — 用語集（番組／各回／公開日／同期対象／保持ルール／固定／判断保留／再生済み）
- `docs/adr/0001-local-surrogate-key.md` — 主キーは取得元 ID ではなく代理キー。未結合の行は、同期の削除判断の前に突合で結び直す
- `docs/adr/0002-download-without-media3-downloadmanager.md` — DownloadManager を使わない
- `docs/adr/0003-sync-deletes-only-from-full-listing.md` — 同期の削除の権限は完全な一覧（全走査、1 番組の同期ではその番組の一覧）だけ。共有フォルダの全走査は番組ごとに取り込む
- `docs/adr/0004-now-playing-from-in-process-state-not-media-controller.md` — 聴いている回はプロセス内の状態から取る
- `docs/adr/0005-open-source-distributed-outside-play.md` — オープンソースとして Play を通さず、GitHub Releases と Obtainium で配布する
- `docs/adr/0006-ui-text-resolved-only-in-compose.md` — UI の文言は strings.xml、文字列に解決するのは Compose だけ（`UiText`）。既定は英語
- `docs/adr/0007-add-file-shares-as-sources.md` — 共有フォルダ（NAS の共有フォルダ（SMB）・端末のフォルダ）を取得元に加える。取得元は同時に 1 つで、取得元には書き込まない（例外はブックのライブラリの再生の共有）
- `docs/adr/0008-no-autoplay-on-bluetooth-connect.md` — Bluetooth の接続では自動で再生しない（ロック画面の再開のカードの ▶ で足りる）
- `docs/adr/0009-show-images-only-from-source-data.md` — 画像はデータにあるときだけ出す。番組の画像は同期で取って手元に持ち、回の画像は再生のときに手元のファイルから読む
- `docs/adr/0010-share-playback-state-via-jellyfin-book-library.md` — ブックのライブラリでは、再生位置と再生済みを UserData を通してほかの端末や Jellyfin Web と分け合う（再生の共有）。ほかの取得元では手元だけで持ち、送らない
- `docs/adr/0011-replace-local-files-when-the-source-version-changes.md` — 手元のファイルは、取得元のファイルの版が変わったら差し替える。Jellyfin では Etag を合図にして大きさで確かめる
- `docs/adr/0012-license-mpl-2.0.md` — ライセンスは GPL-3.0 ではなく MPL-2.0
- `docs/adr/0013-shared-folder-three-level-layout.md` — 共有フォルダの中はちょうど 3 段で、番組と配信元はタグでなくフォルダの名前で決める
- Jellyfin 12 認証仕様: https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f
- jellyfin-sdk-kotlin Releases: https://github.com/jellyfin/jellyfin-sdk-kotlin/releases
- Jellyfin OpenAPI (stable): https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json
