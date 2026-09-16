# Jellyfin オフライン音声クライアント — 実装引き継ぎ

## あなたへの依頼

Android 向けの Jellyfin クライアントを新規に作ります。以下の設計は確定済みなので、
勝手に方針を変えず、不明点があれば実装を進める前に質問してください。

用語は `CONTEXT.md` の定義に従うこと。覆しにくい判断の経緯は `docs/adr/` にある。
本文書と食い違う点があれば、`CONTEXT.md` / ADR の方を正とし、本文書を直してください。

まずは **マイルストーン 1** だけを実装してください。M2 以降には進まないこと。

---

## プロダクト概要

- ラジオ録音（AAC/M4A）を Jellyfin サーバから取得し、**オフラインで聴く**ための Android アプリ
- コンセプトは 2 つ
  1. **オフライン優先**: サーバに到達できなくてもアプリが完全に成立する
  2. **フォルダ同期**: 番組単位でルールを決めて自動的にダウンロード／削除する
- 既存クライアント（Findroid・公式アプリ）は映像中心で、番組単位の自動同期とオフライン自立が弱い。
  そこが差別化点なので、この 2 点の品質を最優先する。
- 利用形態は **1 サーバ・1 ユーザー・1 ライブラリ**。聴くのはこのアプリだけ（他端末・Web との併用は想定しない）。

## 技術スタック

| 領域 | 採用 |
| --- | --- |
| 言語 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| 再生 | Media3 (ExoPlayer) + MediaSessionService — **再生専用**。DownloadManager は使わない（ADR 0003） |
| API | jellyfin-sdk-kotlin（公式）**v1.9.0 以上**（Jellyfin 12.0 対応、2026-09-08 リリース） |
| 永続化 | Room |
| バックグラウンド | WorkManager（同期・ダウンロードの Worker） |
| DI | Hilt |
| 日時 | kotlinx-datetime（`Instant`）。ドメイン型の時間長は `kotlin.time.Duration` |
| テスト | JUnit5 + kotlin-test、Room は Robolectric、Media3 はフェイク `Player` |

## モジュール構成（M1 から この形で切る）

```
:core:domain   純粋 Kotlin (JVM)。番組／各回／保持ルール／再生済み等のドメイン型、
               再生済み判定、同期の純粋関数（M3）。Android・Room・Media3 に依存しない
:core:data     Android ライブラリ。Room Entity / DAO / TypeConverter / Repository 実装。
               Entity ↔ ドメイン型のマッパー、ticks(100ns) ↔ Duration の変換はここに閉じ込める
:app           Compose UI、MediaSessionService、WorkManager Worker、Hilt エントリ
```

M1 の時点で `:core:domain` にあるのはほぼ型だけだが、境界を先に置くこと自体が目的。
後からモジュールを割るコストが最も高い。

## サーバ側の前提

- Jellyfin **12.0** を対象にする（2026-09 リリース）
- **レガシー認証は使用不可**。`?api_key=`、`X-Emby-Token`、`X-Emby-Authorization` は 12.0 で廃止された。
  `Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="...", Token="..."`
  形式のみを使う
- `/emby/`・`/mediabrowser/` のパスは削除済み。使わない
- OpenAPI 仕様に載っていないエンドポイントは使わない。認証・API アクセスは
  jellyfin-sdk-kotlin に任せ、自前で HTTP を組み立てない
- ダウンロードは SDK の HttpClient（OkHttp）で `/Items/{itemId}/Download` をストリーム取得し、
  Worker がファイルへ直接書く（ADR 0003）。Media3 の `HttpDataSource.Factory` に Authorization ヘッダを
  設定する必要があるのは、サーバから直接ストリーミング再生する場合（M4 以降で検討）に限る
- 認証トークンは Android Keystore で生成した鍵で暗号化し DataStore に置く（`EncryptedSharedPreferences` は使わない）
- ログアウトは認証情報だけを消し、手元のデータは残す。別サーバへ接続するときだけ
  明示的に「ローカルデータを消して接続」を選ばせる

## ライブラリ構造のマッピング

サーバ側のラジオ録音は**音楽ライブラリ**として取り込まれている。アプリ内では自前モデルに正規化する。

| Jellyfin | アプリ内の概念（`CONTEXT.md` 参照） |
| --- | --- |
| MusicAlbum | 番組 (Program) |
| Audio | 各回 (Episode) |
| `AlbumArtist` | 放送局 (Station) — `radirec-tool` が albumartist に放送局を書く |
| `PremiereDate` → `DateCreated` → ファイル更新日時 | 放送日 (Aired At) — 並び順・「最新 N 回」の基準。この順でフォールバック |
| `DateCreated` | 取り込み日時 (Added At) — 差分検出の打ち切りにだけ使う |
| `Id` | サーバ ID — アプリ内の主キーではない（ADR 0001） |

主に使う取得パス（SDK 経由で呼ぶ）:

- 番組一覧: `/Items?IncludeItemTypes=MusicAlbum&Recursive=true&ParentId={libraryId}`
- 各回一覧: `/Items?ParentId={albumId}&IncludeItemTypes=Audio&Fields=MediaSources,DateCreated,PremiereDate&SortBy=DateCreated&SortOrder=Descending`
- 原本ダウンロード: `/Items/{itemId}/Download`（トランスコードは使わない。direct play 前提）
- 再生位置・再生済みの送信: **`POST /UserItems/{itemId}/UserData`**（`UpdateUserItemDataDto` の
  `PlaybackPositionTicks` / `Played` / `LastPlayedDate`）。`/Sessions/Playing/*` は使わない
- 再生位置の初期取得: `GET /UserItems/{itemId}/UserData`（ローカルに行が無いときだけ）

差分検出は `SortBy=DateCreated&SortOrder=Descending` で取得し、ローカルの最大取り込み日時に
到達したら打ち切る方式。番組数が少ないうちはフル走査でも構わない。

## Room スキーマ（この形で作る）

主キーはすべてローカル代理キー。サーバ ID は nullable unique（ADR 0001）。
サーバ ID を持たない行（M1 のシード、孤児化した行）は正規の状態。

```kotlin
@Entity(indices = [Index("serverItemId", unique = true)])
data class ProgramEntity(          // 番組 = MusicAlbum
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val serverItemId: String?,
  val name: String,
  val stationName: String?,        // 放送局（MusicAlbum.AlbumArtist / シードでは番組フォルダの親フォルダ名）
  val syncEnabled: Boolean,        // 同期対象か（既定 false）
  val keepLatest: Int?,            // 最新 N 回まで保持（null = 上限なし）
  val deleteAfterPlayed: Boolean
)

@Entity(indices = [Index("serverItemId", unique = true), Index("programId")])
data class EpisodeEntity(          // 各回 = Audio
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val serverItemId: String?,
  val programId: Long,
  val title: String,
  val airedAt: Instant,            // 放送日（PremiereDate、無ければ DateCreated）
  val addedAt: Instant?,           // 取り込み日時（DateCreated）。シード由来は null
  val runtimeTicks: Long, val sizeBytes: Long,
  val container: String
)

@Entity
data class LocalFileEntity(        // ダウンロード状態の唯一の正（ADR 0003）
  @PrimaryKey val episodeId: Long,
  val state: DownloadState,        // PENDING / RUNNING / DONE / FAILED
  val path: String?,               // getExternalFilesDir("episodes") 配下の絶対パス
  val pinned: Boolean,             // 固定。保持ルールの対象外（手動ダウンロード・シード由来）
  val attemptCount: Int,           // FAILED の再試行判断用。WorkManager の backoff は request 単位で各回単位ではない
  val lastAttemptAt: Instant?,
  val downloadedAt: Instant?
)

@Entity
data class PlaybackStateEntity(    // ローカル正（ADR 0002）
  @PrimaryKey val episodeId: Long,
  val positionTicks: Long,         // 1 tick = 100ns
  val played: Boolean,
  val updatedAt: Instant,
  val syncedAt: Instant?           // null = 未送信 (dirty)
)
```

- `wifiOnly` は `Program` から外し、アプリ全体の設定（DataStore）にする
- `PlaybackStateEntity` は `LocalFileEntity` が削除されても残す。`EpisodeEntity` と同じライフサイクル
  （サーバに各回が存在する限り）で生存させる
- ticks は Entity とサーバ境界にだけ現れる。ドメイン型は `Duration`

### 再生位置と再生済み（重要、ADR 0002）

- `PlaybackState` を**ローカル正**とする。ローカルに行があればローカルが常に勝つ。
  サーバの `UserData` は再インストール・初回取得で行が無いときの初期値としてのみ読む
- `syncedAt` が null のレコードだけを、オンライン復帰時に `POST /UserItems/{itemId}/UserData` へ送る。
  一方向で衝突解決はしない
- **再生済み**の判定（`:core:domain` の純粋関数）:
  - 再生位置が **末尾から残り 2 分以内**に達したら自動で `played = true`。ただし位置 0 では判定しない
    （尺が 2 分以下の回は「再生が少しでも進んだら再生済み」。閾値を尺でスケールさせない）
  - 一度 true になったら、シークで戻しても自動では false に戻らない
  - 手動で再生済み／未再生を切り替えられる（M1 の UI に含める）。**手動切替は再生位置を変えない**
- **再開位置**（`:core:domain` の純粋関数）: 再生開始時は「残りが 2 分以下なら先頭から、そうでなければ
  保存位置から」。再生済みフラグは見ない。最後まで再生し終えた回は位置 = 尺を保存する
- 再生済みフラグと再生位置は互いに触らない。両者が絡むのは上記の再開位置だけ

## 同期エンジンの設計（M3）

同期ロジックは `:core:domain` の **副作用のない関数**として書き、ユニットテストで網羅する。
I/O（HTTP・ファイル・DB）はこの関数の外側に置く。

```
入力:  番組ごとの { 保持ルール, 同期対象か,
                    サーバ上の各回一覧: Known(list) | Unavailable | Gone,
                    手元の各回集合（サーバ ID・固定フラグ・再生済み含む） }
出力:  番組ごとの { ダウンロード対象, 削除対象 }
```

規則:

- `Unavailable`（その番組の一覧が取得できなかった）→ その番組は**判断保留**。ダウンロードも削除も出さない。
  「一覧が空」とは型で区別する
- `Gone`（サーバの番組一覧に、その番組のサーバ ID が無い）→ 同じく**判断保留**。「番組が無い」は
  再取り込み・整理であって「各回を消した」ではない（ADR 0001 の守りたいシナリオ）。UI では
  「サーバ上で見つかりません」と表示し、新しいサーバ ID への突合は ADR 0001 の未決事項（M3）に委ねる
- 同期対象でない番組 → 保持ルールを適用しない。固定された各回だけが手元に残る
- **保持ルールは独立した削除理由**。「最新 N 回まで保持」と「再生済みなら削除」のどちらかに該当すれば
  手元に置かない（保持は AND）。「最新 N 回」は再生済み・未再生を問わず放送日の新しい順、
  同着（放送日は日単位なので同日パートで起きる）は各回のタイトルの辞書順 → ローカル ID で安定ソート。
  この順序は各回一覧・連続再生と共通
- **固定**された各回は保持ルールの対象外。利用者が固定を外すか手動削除するまで残る
- サーバの一覧（`Known`）から消えた各回は手元からも削除する。サーバが各回の存在の正、手元はキャッシュ。
  ただしこの規則が届くのは **`Known` の番組に属し、かつ `serverItemId != null` の各回だけ**。
  サーバ ID を持たない各回（シード由来など）は「サーバに在る」と主張したことがないので対象外
- 保持すべき集合 − 手元の集合 → ダウンロード対象（再生済みの回は保持すべき集合に入らないので落ちない）

運用パラメータ:

- 定期同期: `PeriodicWorkRequest` 6 時間ごと、`Constraints` は充電中 + （設定が Wi-Fi のみなら）UNMETERED
- アプリ起動時: 前回同期から 1 時間以上経っていれば実行。手動プルでも実行
- ダウンロード: Worker 内で HTTP ストリームを `.part` ファイルへ書き、完了後にリネーム。
  再開は `Range` ヘッダ。進捗は `setProgress` で UI へ
- 置き場所: `getExternalFilesDir("episodes")/<放送局>/<番組>/<ファイル>`（M1 のシードと同じ階層）

## 実装順序

- **M1（今回のスコープ）**: ローカルの音声ファイルを Media3 で再生し、再生位置を Room に保存する。
  Jellyfin への接続は一切含めない。詳細は次節
- M2: Jellyfin 認証（サーバ URL → ログイン → 音楽ライブラリを 1 つ選ぶ）とライブラリ一覧の取得・表示
- M3: 同期エンジン + WorkManager による自動ダウンロード／削除、番組ごとの保持ルール編集画面
- M4: 再生位置のサーバ同期、倍速・スリープタイマー

オフラインを後から足すと破綻するため、必ずこの順序で積む。

### M1 の範囲
- **ファイルの供給**: `getExternalFilesDir("episodes")/<放送局>/<番組名>/<ファイル>.m4a|.mp3` に `adb push` する。
  この 2 階層は `radirec-tool` の出力（`<albumartist = 放送局>/<album = 番組>/<番組> YYYY-MM-DD.m4a`）を
  そのまま持ち込めるように合わせてある。デバッグビルド限定の番組一覧画面の「シード」ボタンが
  このフォルダを走査し、親フォルダを放送局（`stationName`）、サブフォルダを番組、ファイルを各回として
  `ProgramEntity` / `EpisodeEntity` / `LocalFileEntity(state = DONE, pinned = true)` を生成する。
  - 各回のタイトルはファイル名（拡張子なし）
  - 放送日は タグ（M4A `©day` / MP3 `TDRC`、`YYYY-MM-DD`）→ ファイル名の `YYYY-MM-DD` → ファイル更新日時 の順。
    日単位なので JST 00:00 の `Instant` にする。フォールバックの選択は `:core:domain` の純粋関数、
    タグ読み取りは `:app` のシードに閉じ込める
  - 尺は `MediaMetadataRetriever` の duration から。`sizeBytes` はファイルサイズ、`container` は拡張子
  - **追加専用・べき等**: 番組はフォルダ、各回は `LocalFile.path` をキーに、既存行は触らず新しいファイルだけ足す。
    ディスクから消えたファイルの扱いは M3（手元に無い各回の整合）で決める
  - サーバ ID は null。**置き場所を `filesDir`（内部）に変えないこと** — root 無しの `adb push` が通らない。
    Receiver を `exported` にして `am broadcast` で叩く方式は取らない
- **画面**: 番組一覧 → 各回一覧 → 再生画面 の 3 階層。M2 で Jellyfin から番組が来ても画面は変えず、
  データソースだけ差し替わる。保持ルール編集画面は M3
  - 番組一覧は「最新の各回の放送日が新しい順」、各回一覧は「放送日の新しい順」（同着はタイトルの辞書順）
  - 各回の行に再生済みマークと、未再生かつ位置 > 0 の回には進捗（位置 / 尺）を出す
- **再生操作**: 再生／一時停止／シーク／±30 秒スキップ／前後の回へ移動。
  連続再生は同じ番組内で **放送日の古い順** に次の回へ。再生済みの回は飛ばさない。再生済み／未再生の手動切替
  - 各回を開いた時点で、その番組の手元にある全各回（古い順）を Media3 のプレイリストとして
    `MediaSessionService` に積み、選んだ回を開始インデックスにする。`mediaId` = ローカル `episodeId`、
    各 `MediaItem` の `startPositionMs` に再開位置を与える。次／前・自動遷移・通知の ⏮⏭ は Media3 標準に任せる
- **再生位置の保存**: 一時停止・停止・回の切替時（`onMediaItemTransition` の `oldPosition`）+ 再生中 10 秒ごと。
  プロセスの強制 kill で最大 10 秒戻るのは許容
- **再起動後の復元**: 各回を開き直すと保存位置から再開できること。「最後に聴いていた回」の自動復元や
  ミニプレイヤーは M1 に含めない
- **MediaSessionService**: 通知・ロック画面操作。以下は必須
  - Android 13+ の `POST_NOTIFICATIONS` ランタイム権限要求
  - `AndroidManifest` に `foregroundServiceType="mediaPlayback"` と
    `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 権限（Android 14+ の FGS 制約）
- **モジュール**: 上記 3 モジュールを最初から切る。`applicationId` は `dev.tseki.jellyfinradio`、`minSdk` 31
- **Room**: `exportSchema = true` で `core/data/schemas/` を git 管理する
- **CI**: GitHub Actions で PR ごとに `./gradlew test`
## 作業の進め方

- 同期エンジンとデータ層はテストを先に書く
  - `:core:domain`: プレーン JUnit5。M1 分は 再生済み判定、ticks ↔ `Duration` 変換、
    放送日フォールバック（`PremiereDate` → 取り込み日時、シードは タグ → ファイル名 → ファイル更新日時）、再開位置、各回の並び順（同着のタイブレーク）
  - `:core:data`: Robolectric + `Room.inMemoryDatabaseBuilder` で DAO / TypeConverter
  - Media3: 「`Player` から位置を受け取って Room に書く」部分だけフェイク `Player` で JVM テスト
  - CI は `./gradlew test`（エミュレータ不要）
- Media3 の通知・バックグラウンド動作・省電力まわりの検証は実機で人間が行うので、
  その前提でテスト可能な範囲を切り分けること
- 各マイルストーン完了時に、実機で確認すべき項目を箇条書きで提示する
- 依存ライブラリのバージョンは実装開始時点の最新安定版を確認してから固定する

## やらないこと

- 映像の再生
- トランスコード
- サーバ管理機能
- キャスト（Chromecast）
- 複数サーバ・複数ユーザー・複数ライブラリ
- 他端末・Web との再生位置の双方向マージ（ローカル正、一方向送信のみ）
- Media3 DownloadManager / SimpleCache によるダウンロード
- `/Sessions/Playing/*` による「再生中」のサーバ通知（必要になれば M4 以降で検討）

## 参照

- `CONTEXT.md` — 用語集（番組／各回／放送日／同期対象／保持ルール／固定／判断保留／再生済み）
- `docs/adr/0001-local-surrogate-key.md` — 主キーはサーバ ID ではなく代理キー
- `docs/adr/0002-playback-position-local-authority.md` — 再生位置・再生済みはローカル正
- `docs/adr/0003-download-without-media3-downloadmanager.md` — DownloadManager を使わない
- Jellyfin 12 認証仕様: https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f
- jellyfin-sdk-kotlin Releases: https://github.com/jellyfin/jellyfin-sdk-kotlin/releases
- Jellyfin OpenAPI (stable): https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json
