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
| 日時 | `kotlin.time.Instant`（stdlib）+ kotlinx-datetime（`LocalDate` / `TimeZone`）。時間長は `kotlin.time.Duration` |
| テスト | `:core:domain` は JUnit5、Android モジュールは JUnit4 + Robolectric。Media3 は `SimpleBasePlayer` 継承のフェイク `Player` |

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

- Jellyfin **12.0** を対象にする（2026-09 リリース）。自宅の実サーバは 2026-09-17 時点で 10.11.11（認証形式は同じ）
- **レガシー認証は使用不可**。`?api_key=`、`X-Emby-Token`、`X-Emby-Authorization` は 12.0 で廃止された。
  `Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="...", Token="..."`
  形式のみを使う
- `/emby/`・`/mediabrowser/` のパスは削除済み。使わない
- OpenAPI 仕様に載っていないエンドポイントは使わない。認証・API アクセスは
  jellyfin-sdk-kotlin に任せ、自前で HTTP を組み立てない
- ダウンロードは URL（`getDownloadUrl`）と `Authorization` ヘッダ（`AuthorizationHeaderBuilder`）を SDK に作らせ、
  転送だけ OkHttp のストリームで行い、Worker がファイルへ直接書く（ADR 0003。SDK にストリーミング API が無いため）。Media3 の `HttpDataSource.Factory` に Authorization ヘッダを
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
| `DateCreated` | 取り込み日時 (Added At) — 保存するが新しさの判定には使わない。放送日の代用として並び順に効くのは上の行の経路（差分取得は ADR 0004 で見送り） |
| `Id` | サーバ ID — アプリ内の主キーではない（ADR 0001） |

主に使う取得パス（SDK 経由で呼ぶ）:

- ライブラリ一覧: `/UserViews`。`CollectionType = music` のものだけ選択できる（他は理由付きでグレーアウト）
- 番組一覧: `/Items?ParentId={libraryId}&IncludeItemTypes=MusicAlbum&Recursive=true`（1 回）
- 各回一覧: `/Items?ParentId={libraryId}&IncludeItemTypes=Audio&Recursive=true&Fields=DateCreated,ParentId&SortBy=DateCreated&SortOrder=Descending&StartIndex=…&Limit=500`
  （ライブラリ全体を 500 件ずつ。番組ごとには呼ばない。`AlbumId` で番組に結び付け、どの番組にも属さない Audio は取り込まない。
  **`Fields=MediaSources` は付けない**: SDK 1.9 は `MediaStream.IsOriginal` を必須と見なすが 10.11 は返さず、デコードで落ちる。
  ファイルサイズは M3 のダウンロード時に確定させる）
- 原本ダウンロード: `/Items/{itemId}/Download`（トランスコードは使わない。direct play 前提）
- 再生位置・再生済みの送信: **`POST /UserItems/{itemId}/UserData`**（`UpdateUserItemDataDto` の
  `PlaybackPositionTicks` / `Played` / `LastPlayedDate`）。`/Sessions/Playing/*` は使わない
- 再生位置の初期取得: `GET /UserItems/{itemId}/UserData`（ローカルに行が無いときだけ）

取得は毎回フル走査（実機 153 番組 / 6,004 回で約 12 秒）。差分取得（`DateCreated` 降順で取り、ローカルの最大取り込み日時に
到達したら打ち切る）は**作らない**（ADR 0004）: 不完全な一覧を同期の入力にすると載っていないだけの各回を削除してしまい、
全走査が 12 秒で終わる今は取り込み専用の経路を持つ価値が無い。各回一覧の「引っ張って更新」だけは番組単位取得（#12、M3-b）。

サーバの `PremiereDate`（無ければ `DateCreated`）は日時で来るが、**日付部分だけ取って JST 0 時の `Instant`** にする
（日単位の値として並び順を安定させる）。

## Room スキーマ（この形で作る）

主キーはすべてローカル代理キー。サーバ ID は nullable unique（ADR 0001）。
サーバ ID を持たない行（未結合）は正規の状態（ADR 0001）。M1〜M3-b にあったシード（手元フォルダの走査）は M3-c で削除した（#21）。

```kotlin
@Entity(indices = [Index("serverItemId", unique = true), Index("stationName", "name")])
data class ProgramEntity(          // 番組 = MusicAlbum。(放送局, 番組名) は突合のキーだが一意ではない（v2）
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val serverItemId: String?,
  val name: String,
  val stationName: String?,        // 放送局（MusicAlbum.AlbumArtist）
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
  val addedAt: Instant?,           // 取り込み日時（DateCreated）
  val runtimeTicks: Long, val sizeBytes: Long,
  val container: String
)

@Entity
data class LocalFileEntity(        // ダウンロード状態の唯一の正（ADR 0003）
  @PrimaryKey val episodeId: Long,
  val state: DownloadState,        // PENDING / RUNNING / DONE / FAILED
  val path: String?,               // getExternalFilesDir("episodes") 配下の絶対パス
  val pinned: Boolean,             // 固定。保持ルールの対象外（手動ダウンロード）
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
- ~~`syncedAt` が null のレコードだけを、オンライン復帰時に `POST /UserItems/{itemId}/UserData` へ送る~~ —
  **送らない**（ADR 0007、2026-09-19）。`syncedAt` は使わないまま残す
- **再生済み**の判定（`:core:domain` の純粋関数）:
  - 再生位置が **末尾から残り 2 分以内**に達したら自動で `played = true`。ただし位置 0 では判定しない
    （尺が 2 分以下の回は「再生が少しでも進んだら再生済み」。閾値を尺でスケールさせない）
  - 一度 true になったら、シークで戻しても自動では false に戻らない
  - 手動で再生済み／未再生を切り替えられる（M1 の UI に含める）。**手動切替は再生位置を変えない**
- **再開位置**（`:core:domain` の純粋関数）: 再生開始時は「残りが 2 分以下なら先頭から、そうでなければ
  保存位置から」。再生済みフラグは見ない。最後まで再生し終えた回は位置 = 尺を保存する。
  「再生開始」は各回をタップしたとき（止まっている回を含む）と、次／前・自動遷移で別の回に入ったとき。
  再生中の回の画面に戻っただけのときは触らない
- 再生済みフラグと再生位置は互いに書き換えない。両方を読んで決めるのは上記の再開位置と「聴き始めていない」（#7）だけ

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
- **固定**された各回は保持ルールの対象外。利用者が固定を外すか手動削除するまで残る（例外はサーバの一覧から消えた回。次項）
- サーバの一覧（`Known`）から消えた各回は手元からも削除する。サーバが各回の存在の正、手元はキャッシュ。
  ただしこの規則が届くのは **`Known` の番組に属し、かつ `serverItemId != null` の各回だけ**。
  サーバ ID を持たない各回は「サーバに在る」と主張したことがないので対象外
- 保持すべき集合 − 手元の集合 → ダウンロード対象（再生済みの回は保持すべき集合に入らないので落ちない）

運用パラメータ:

- 定期同期: `PeriodicWorkRequest` 6 時間ごと、`Constraints` は `DownloadWorker` と同じ（Wi-Fi のみなら UNMETERED、
  そうでなければ CONNECTED）。充電中の条件は課さない（同期自体の通信は一覧の JSON だけで軽く、転送は `DownloadWorker` の制約で守られる）
- アプリ起動時: 前回同期から 1 時間以上経っていれば実行（同じ制約で待つ）。番組一覧の手動プルは同期そのもの（M3-b の範囲を参照）
- ダウンロード: Worker 内で HTTP ストリームを `.part` ファイルへ書き、完了後にリネーム。
  再開は `Range` ヘッダ。進捗は `setProgress` で UI へ
- 置き場所: `getExternalFilesDir("episodes")/<放送局>/<番組>/<ファイル>`（radirec-tool の出力と同じ階層）

## 実装順序

- **M1（今回のスコープ）**: ローカルの音声ファイルを Media3 で再生し、再生位置を Room に保存する。
  Jellyfin への接続は一切含めない。詳細は次節
- M2: Jellyfin 認証（サーバ URL → ログイン → 音楽ライブラリを 1 つ選ぶ）とライブラリ一覧の取得・表示
- M3: 同期エンジン + WorkManager による自動ダウンロード／削除、番組ごとの保持ルール編集画面
- M4: 倍速・スリープタイマー・位置 0 行の抑止（epic #34）。再生位置のサーバ同期は **やらない**（ADR 0007）

オフラインを後から足すと破綻するため、必ずこの順序で積む。

### M1 の範囲
- **ファイルの供給**（**M3-c で削除**、#21。以下は M1 当時の記録）: `getExternalFilesDir("episodes")/<放送局>/<番組名>/<ファイル>.m4a|.mp3` に `adb push` する。
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
    ディスクから消えたファイルの扱いは M3-a で決めた（「M3-a の範囲」の削除の規則・整合。#5）
  - サーバ ID は null。**置き場所を `filesDir`（内部）に変えないこと** — root 無しの `adb push` が通らない。
    Receiver を `exported` にして `am broadcast` で叩く方式は取らない
- **画面**: 番組一覧 → 各回一覧 → 再生画面 の 3 階層。M2 で Jellyfin から番組が来ても画面は変えず、
  データソースだけ差し替わる。保持ルール編集画面は M3
  - 番組一覧は「最新の各回の放送日が新しい順」（#16 以降は「よく聴く」の番組を上の節に分け、各節内でこの順）、各回一覧は「放送日の新しい順」（同着はタイトルの辞書順）
  - 各回の行に再生済みマークと、次に開いたとき途中から再開する回（再開位置の規則で先頭に戻らない回。
    再生済みかどうかは見ない）には進捗（再開位置 / 尺）を出す
- **再生操作**: 再生／一時停止／シーク／±10 秒スキップ（M1 では ±30 秒。#29 で 10 秒にし、左右スワイプの秒単位シークを足した）／前後の回へ移動。
  連続再生は同じ番組内で **放送日の古い順** に次の回へ。再生済みの回は飛ばさない。再生済み／未再生の手動切替
  - 各回を開いた時点で、その番組の手元にある全各回（古い順）を Media3 のプレイリストとして
    `MediaSessionService` に積み、選んだ回を開始インデックス・再開位置で `setMediaItems` する。
    `mediaId` = ローカル `episodeId`。Media3 のプレイリストは先頭の回にしか開始位置を持てないので、
    次／前・自動遷移で別の回に入ったときはサービス側で保存位置（再開位置の規則）へシークする
- **再生位置の保存**: 一時停止・停止・回の切替時（`onMediaItemTransition` の `oldPosition`）+ 再生中 10 秒ごと。
  ただし行が無く、位置 5 秒未満・未再生（`PlaybackRules.NOT_STARTED_THRESHOLD`、聴き始めていない）なら書かない（#7）。
  プロセスの強制 kill で最大 10 秒戻るのは許容
- **再起動後の復元**: 各回を開き直すと保存位置から再開できること。「最後に聴いていた回」の自動復元や
  ミニプレイヤーは M1 に含めない
- **MediaSessionService**: 通知・ロック画面操作。以下は必須
  - Android 13+ の `POST_NOTIFICATIONS` ランタイム権限要求
  - `AndroidManifest` に `foregroundServiceType="mediaPlayback"` と
    `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 権限（Android 14+ の FGS 制約）
  - **Android 17+（targetSdk 37）の `ACCESS_LOCAL_NETWORK` ランタイム権限**（M2 で判明）。無いと LAN 内の
    サーバ宛の TCP が黙って落ちる（DNS だけ通るので名前解決は成功し、接続がタイムアウトする）。
    `adb shell` や古い targetSdk のアプリは対象外なので、切り分けでは `run-as <pkg> nc -z <host> <port>` で
    アプリの UID から試すこと
- **モジュール**: 上記 3 モジュールを最初から切る。`applicationId` は `dev.tseki.jellyfinradio`、`minSdk` 31
- **Room**: `exportSchema = true` で `core/data/schemas/` を git 管理する
- **CI**: GitHub Actions で PR ごとに `./gradlew test`

### M2 の範囲（2026-09-17 の grilling で確定）

- **経路**: `https://` 固定（正規証明書）。平文 HTTP は許可しない。URL 入力はスキーム省略可
- **画面**: 未ログインなら 接続画面（URL・ユーザー名・パスワード）→ ライブラリ選択。ログイン済みなら M1 の番組一覧
  - ライブラリ選択は**常に出す**。`/UserViews` の全ライブラリを列挙し、音楽以外は「音楽ライブラリのみ選べます」で
    グレーアウト。音楽が 1 つなら選択済みにして「決定」だけ。0 なら決定不可
  - 設定画面（番組一覧の歯車）: サーバ URL・ユーザー名・ライブラリ名（タップで選び直し）・最終取得日時、
    **ログアウト**（認証情報だけ消す。手元のデータは残る）、**別のサーバに接続**（確認の上ローカルデータを全部消す）。
    デバッグ用シードはここに移す（接続画面にもデバッグ節として置く。M3-c で削除）
  - 401 はログアウトと同じ処理をして接続画面へ（URL とユーザー名は入力済み）
- **認証情報**: `Client="Jellyfin Radio"`, `Version=versionName`。`Device` / `DeviceId` は jellyfin-sdk-kotlin の Android 既定
  （端末のモデル名 / `ANDROID_ID` 由来）に任せ、自前の UUID は持たない（実装時に変更。保存する値が 1 つ減る）。
  トークンは Keystore の鍵で AES-GCM 暗号化して Preferences DataStore に保存（`EncryptedSharedPreferences` は使わない）。
  パスワードは保存しない。`SessionStore`（`:core:data`）が持つ
- **取得の起点**（M2 時点）: ログイン直後と、番組一覧・各回一覧の「引っ張って更新」だけ。M3-b で番組一覧の更新は同期、各回一覧の更新は番組単位取得になり、定期・起動時の同期が加わった（「M3-b の範囲」）。
  失敗はスナックバーで、一覧は Room のまま
- **突合（CONTEXT.md「突合」）**: `serverItemId = NULL` の行にだけ行う。番組は (放送局, 番組名) = (`AlbumArtist`, `Name`)、
  各回は同じ番組内の `Name` 完全一致。候補が複数なら結ばない（新規行）。表記ゆれは吸収しない（radirec-tool の alias の責務）
- **取り込み**: サーバ由来の各回はサーバの値で上書き（タイトル・放送日・取り込み日時・尺・サイズ・コンテナ）。
  `LocalFile` と `PlaybackState` は触らない。サーバの一覧に無い番組・各回は M2 では何もしない（削除・判断保留は M3）
- **手元に無い各回**: 一覧に同じ並びで出すが薄く表示し、タップしても再生画面へ行かない。右端は雲アイコン（M3 でダウンロードボタンになる）。
  番組一覧は「手元 M / 全 N 回」。連続再生のキューは手元にある回だけ（手元に無い回は飛ばす）
- **ライブラリ切替**: 同じサーバなので手元の行は触らない。旧ライブラリの番組は M3 の判断保留と同じ扱いになる
- **コードの置き場**: `:core:domain` にサーバのスナップショット型（`ServerProgram` / `ServerEpisode`）と突合の純粋関数
  `LibraryMatching.match`（JUnit 5）。`:core:data` に `JellyfinGateway` インターフェース（`signIn` / `listLibraries` / `fetchLibrary`）と
  jellyfin-sdk-kotlin 実装、`SessionStore`。Repository が突合結果を 1 トランザクションで Room に適用。テストはフェイクのゲートウェイ。
  モジュールは 3 つのまま

### M3 の分割（2026-09-17 の grilling で確定）

M3 は epic（#13）の下で 3 本の PR に分け、それぞれ実機確認してマージする。
**M3-a ダウンロード基盤**（#14）→ **M3-b 同期エンジンと保持ルール**（`planSync`、編集画面、Wi-Fi のみ、定期・起動時同期、#12）→
**M3-c 消失と突合**（#3、#2、#9）。b・c の細部は a を動かしてから grilling する。

### M3-a の範囲

- **転送**: `JellyfinGateway.openDownload(episodeServerId, rangeStart)`。SDK にはストリーミング取得の API が無い
  （`getDownload` / `request` は本文を `byte[]` に全部読む）ので、**URL は SDK の `getDownloadUrl`、`Authorization` ヘッダは SDK の
  `AuthorizationHeaderBuilder` に作らせ、転送だけ OkHttp（SDK の依存に同梱）で行う**。エンドポイントと認証形式は手書きしない。
  戻り値は `resumedFrom`（206 で `Range` が効いたか）・`totalBytes`・本文ストリーム。サーバが `Range` を無視して 200 を返したら
  `.part` を書き直す。テストはフェイク（206 再開／200 全体再送の両方）
- **Worker**: WorkManager のユニーク Worker（`download-queue`、`KEEP`）が `LocalFile.state = PENDING` の行を**キューに入れた順（FIFO、
  `enqueuedAt`。実機確認で「タップした順に落ちてほしい」と決定）**に **1 本ずつ**処理する。再試行は列の末尾へ。M3-b の同期が積む分の優先順位はそこで決める。`<局>/<番組>/<タイトル>.<container>.part` に追記し、完了でリネーム。既存の `.part` は `Range: bytes=<size>-` で再開。
  進捗は `setProgress`。通知は出さない（フォアグラウンドサービスにしない）
- **条件**: 設定の「Wi-Fi のみ」（既定 ON、`AppSettings` DataStore）。ON なら `UNMETERED`、OFF なら `CONNECTED`。待ちの間は「Wi-Fi 待ち」表示
- **失敗**: 1 本失敗しても次へ。`attemptCount` +1、`lastAttemptAt`、`FAILED`。同一実行内では再試行しない。次の起動で
  `attemptCount < 3` を PENDING に戻す。3 回超えは手動の再試行だけ。401 は `LibraryRefresher` と同じくログアウト
- **キャンセル**: DONE 以外の行を消し `.part` も消す。Worker は約 1 MB ごとに DB を見てスキップする
- **行の操作**（各回一覧）: 右端アイコンは状態を表し、タップで最も自然な 1 操作
  （雲 → ダウンロード（= 固定）、進捗リング → キャンセル、警告 → 再試行、手元にある回は再生済み切替）。
  長押しでボトムシート（固定を外す／ファイルを削除／再生済み切替／ダウンロード）。固定中はタイトルの前にピン。
  同期対象でない番組では「固定を外す」を出さない（外すと次の同期で消えるため）
- **削除の規則**（手動削除・保持ルール・消えたファイルの整合で共通）:
  - `serverItemId` がある回: ファイルと `LocalFile` 行だけ消す。`Episode` / `PlaybackState` は残る（ADR 0002。落とし直せば続きから）。
    ただし**サーバの一覧から消えた回**は落とし直せないので `Episode` ごと消す（M3-b の `remove`。固定でも。M3-c 以降は突合で結び直せなかった回に限る）
  - `serverItemId` が無い回（サーバを経由していない回。シード削除後は通常存在しない）: 二度と手に入らないので `Episode` ごと消す（`PlaybackState` は cascade）。
    各回が 0 になった `serverItemId` 無しの番組も消す
- **消えたファイルの整合（#5）**: 同期・更新の開始時に `DONE` 行を全走査し、再生開始時にも存在を確認する。無ければ上記の削除規則を
  自動で適用し、スナックバー「ファイルが見つかりません」
- **完了時**: `LocalFile(DONE, path, pinned = true, downloadedAt)`、`Episode.sizeBytes` を実バイト数で更新（M2 で保留した値をここで確定）。
  同期によるダウンロード（M3-b）は `pinned = false`
- **命名**: 放送局 null は `_`、`container` 空は `m4a`、禁止文字（`/ \ : * ? " < > |`）は `_`、同名衝突は ` (2)`
- 「手元 M / 全 N 回」の M は `DONE` のみ。手元に無い回の再生は M3-a でも不可（ストリーミングは M4 以降）

### M3-b の範囲（2026-09-17 の grilling で確定）

- **同期の 1 回** = 全走査（`fetchLibrary`）→ 取り込み（M2 の突合・上書き）→ `planSync` → 削除の実行 → ダウンロード対象を
  `PENDING`（`pinned = false`）で enqueue → `DownloadScheduler.kick()`。転送は `DownloadWorker` に任せ、同期は自分でファイルを落とさない。
  **削除の権限を持つ入力は全走査だけ**（ADR 0004）。差分取得は作らない
- **入口は `LibraryRefresher` に一本化**（mutex 共有）。定期 `SyncWorker`・起動時・番組一覧の手動プル・ボトムシートの「この番組を今すぐ同期」が
  すべてここを通る。**番組一覧の「引っ張って更新」= ライブラリ全体の手動同期**（`planSync` まで回す）。**各回一覧の「引っ張って更新」= 番組単位取得（#12）**で、
  突合・上書き・追加はするが削除しない。**シートの「この番組を今すぐ同期」= 1 番組の同期**（`syncProgram`）: `/Items/{albumId}` で番組の存在を確かめ
  （404 → 消失 = 判断保留）、その番組の各回一覧を取り、取り込み → `planSync`（この番組だけ）→ 除去・削除・予約。全走査（約 45 秒）を待たずに
  1〜2 秒で終わる。他の番組には触れず、最終同期の時刻も更新しない（ADR 0004 の追記）。設定画面の「最終取得」は「最終同期」（値は `lastFetchedAt`。番組単位取得は更新しない）。
  ログイン直後の初回取得も同期になるが、全番組が同期対象 OFF なので何も落ちず何も消えない
- **「Wi-Fi のみ」は同期にも効く**（手動を含む）。`SyncWorker` の制約は `DownloadWorker` と同じ（UNMETERED / CONNECTED、充電中は課さない）。
  手動は `LibraryRefresher` で `ConnectivityManager.isActiveNetworkMetered` を見て、従量制ならスナックバー
  「Wi-Fi に接続していないため更新しません」で終える（裏で待たせない）。判定は全走査・番組単位・起動時・定期のすべてが通るが、
  **ゲートはサーバへの取得だけを包む**: 先頭の `reconcileMissingFiles()`（#5、ローカル I/O のみ）は従量制でも走らせる
- **起点**: 定期は `PeriodicWorkRequest` 6 時間（`UPDATE` で起動時に登録し直す）。起動時は `ProcessLifecycleOwner` の `ON_START` で
  前回同期から 1 時間以上なら `OneTimeWorkRequest`（ユニーク `sync-once`、`KEEP`）。定期・起動時の失敗は `Result.success()` で終え次回を待つ
  （スナックバー無し、Log のみ）。401 は `DownloadWorker` と同じくログアウト
- **`planSync`**（`:core:domain`、純粋、JUnit 5）: 入力は番組ごとの `{ syncEnabled, retentionRule, server: Known(list) | Unavailable | Gone,
  local: List<LocalEpisodeState(episodeId, serverItemId?, airedAt, title, pinned, played, hasLocalFile)> }`。`hasLocalFile` は
  PENDING / RUNNING / DONE / FAILED のいずれかの行があること（**FAILED も「手元にある」**。再試行は M3-a の規則のまま Worker 起動時に
  `attemptCount < 3` を PENDING に戻す。3 回超は手動のみ）。出力は `{ download: List<EpisodeId>, delete: List<EpisodeId>, remove: List<EpisodeId>, onHold: Boolean }`
  - **`syncEnabled = false` でも関数は動く**: `download` は空、`delete` は非固定の手元ファイル全部、`remove` はサーバから消えた
    `serverItemId != null` の回（固定含む）。`syncEnabled` が効くのは保持ルールの適用（何を `download` し、固定でない何を `delete` するか）だけ。
    `Unavailable` / `Gone` は `syncEnabled` に関わらず全部空 + `onHold`
  - **固定は N に数えない**（別枠）。`keepLatest` は固定を除いた放送日の新しい順で数える（同着は `EpisodeOrder`）
  - **サーバの一覧から消えた `serverItemId != null` の各回は固定でも `Episode` 行ごと消す**（`remove`。`LocalFile` / `PlaybackState` は cascade、
    ファイルも消す）。保持ルールによる削除（`delete`）は M3-a の削除の規則に従う: `serverItemId` がある回は `FILE_ONLY`（`PlaybackState` は残す、ADR 0002）、
    無い回（サーバを経由していない回）は落とし直せないので `Episode` ごと。`Known` の番組で各回が 0 になっても番組は残す
  - `Unavailable` / `Gone` は `onHold`。M3-b では保存も表示もしない（M3-c、#3 で `goneSince` として保存）。全走査なので番組ごとの `Unavailable` になる経路は無い
- **実行側の例外**（`planSync` の外）: 削除対象が PENDING / RUNNING / FAILED なら `cancel` 相当。**聴いている回（現在の `MediaItem`）は今回は削除しない**
  （次回に持ち越し。ただし最後まで聴き終えて止まっている回は除外しない — #27。消された後に ▶ を押すと再生に失敗し、キューを空にして理由をスナックバーに出す）。聴いている回は `PlaybackService` が `Player.Listener` で `@Singleton` の `NowPlaying`（`StateFlow<NowPlayingState?>`、`:app`）に
  書き、同期の実行側はそれを読む（Worker と Service は同一プロセス。MediaController を Worker から結ばない）。
  実行順は削除 → enqueue。同期分の enqueue 順は **番組をまたいで放送日の新しい順**
- **キューの優先順位**（M3-a から持ち越し）: `nextPending()` を `pinned DESC, enqueuedAt IS NULL, enqueuedAt ASC, airedAt DESC` に変える（手動が常に先。
  `enqueuedAt` は v3 で足した nullable なので NULL を先頭に来させない）。
  同期分（`pinned = false`）の行に利用者が「ダウンロード」を選んだら `pinned = true` にする（既存の `enqueue` の「行があれば何もしない」を変える）
- **手動削除と同期の往復は仕様として受け入れる**: 同期対象の番組で保持すべき回のファイルを手で消しても、次の同期で落とし直される。
  削除時のスナックバーに「同期対象の番組なので次の同期で落とし直されます」を足す。聴かない回は再生済みにするのが正規の操作。「除外」状態は作らない
- **同期対象 OFF**: 次の同期でその番組の非固定ファイルが全部消える（CONTEXT.md「固定」の定義どおり）。OFF に切り替えるとき手元に非固定の回が
  N 本あれば「N 回のファイルが次の同期で削除されます。残したい回は固定してください」の確認ダイアログ
- **保持ルール編集**: 各回一覧のトップバーの同期アイコン → ボトムシート。「この番組を同期する」スイッチ、「最新 N 回まで保持」
  （選択式 1 / 3 / 5 / 10 / 20 / 上限なし）、「再生済みなら削除」スイッチ（後の 2 つは同期 OFF でグレーアウト）、「この番組を今すぐ同期」ボタン。
  同期していない番組のアイコンは `SyncDisabled`（斜線入り）で形で区別する（filled / outlined の `Sync` は同じ形）。
  ON に切り替えるたび `keepLatest` が null なら **既定 3** を入れる（「上限なし」にしたい場合は ON にした後で選ぶ。OFF → ON を往復すると 3 に戻る。
  「未設定」と「上限なし」を区別する列は足さない）。ON にしても即同期はしない。
  `serverItemId` の無い番組はスイッチ無効 + 「サーバ上で見つかっていないため同期できません」。番組一覧の行に同期対象の印
- **結果の文言**: 手動は「番組 X / 各回 Y を取得。Z 回をダウンロード予約、W 回を削除」（0 は省く）+ 判断保留があれば
  「N 番組はサーバ上で見つからず、そのままにしました」。`RefreshResult` に `enqueued` / `deleted` / `onHold` を足す。通知は出さない
- **#12 番組単位の更新**: `JellyfinGateway.fetchProgramEpisodes(credentials, programServerId)`、
  `LibraryRefreshRepository.refreshProgram(programId)`（突合は「番組 1 つ + その各回」のスナップショットで `LibraryMatching.match`、削除なし）。
  `serverItemId` の無い番組は手動同期にフォールバック
- Room のスキーマ変更は無し（`syncEnabled` / `keepLatest` / `deleteAfterPlayed` は v1 から在る）。`Episode.addedAt` は差分取得を見送ったことで
  読み手が無くなるが、列は残す（取り込み日時の表示や #9 の補助に使える。消すならスキーマ変更が要る）

### M3-c の範囲（2026-09-18 の grilling で確定）

3 本の PR に分ける。**PR 1 = #2（突合の拡張、ADR 0005。第二段も含む）** → **PR 2 = #21（シード機能の削除）** → **PR 3 = #3（消失の記録と UI）**。
#9 は「シードは実運用で使わない」と判断して close（2026-09-18）。第二段の突合は再取り込みと同時にタイトルを付け直したケースの保険として残す。

- **突合の対象を「未結合」に広げる**（CONTEXT.md「未結合」「突合」、ADR 0005）: サーバ ID を持たない行に加えて、**持っているサーバ ID が今回の完全な一覧に無い行**も
  対象にする。`LibraryMatching.match` の「`serverItemId == null` だけ」を「未結合」に変え、キーは従来どおり（番組は (放送局, 番組名)、各回は同じ番組内のタイトル完全一致、双方 1 対 1）。
  結び付けたら `serverItemId` を書き換えるだけで `LocalFile` / `PlaybackState` は触らない。突合は `newPrograms` の挿入より前（今の順序）なので、再取り込み後に番組が二重になることは無い
- **第二段（元 #9）**: タイトルで結べなかった未結合の各回に対し、同じ番組内で **放送日（日単位）が同じ ＋ 尺の差が 5 秒以内** の候補が双方 1 対 1 なら結ぶ。
  尺 0（不明）は対象外。同日に複数本あって尺で絞れなければ結ばない。古い ID の行にも同じ第二段を適用する。
  結んだ後はサーバの値で上書き（タイトルは `2026-09-16 (1)` になる）。`EpisodeKeyRow` / `LocalEpisodeKey` に `airedAt` と `runtime` を足す
- **同期との順序**: 全体同期（`refresh`）も 1 番組の同期（`syncProgram`）も、取り込み（突合を含む）→ `planSync` の順は今のまま。結び直せなかった古い ID の各回だけが
  `remove` に落ちる（本当にサーバから消えた回）。1 番組の同期で番組が 404 のときは番組一覧が無いので結び直さず判断保留
- **確度の境界**: 「双方 1 対 1 でなければ結ばない」だけ。閾値付きの自動結合も利用者の確認 UI も作らない（ADR 0005）
- **消失の記録（#3）**: `programs` に `goneSince: Instant?` を足す（スキーマ v4、`AutoMigration(3, 4)`）。全体同期で「番組一覧に無く突合でも結べなかった」番組に立て、
  見つかれば（結び直しを含めて）null に戻す。1 番組の同期で 404 なら立てる。`ProgramSummary` / `Program` に載せる
- **消失の表示**: 番組一覧の行に「サーバ上で見つかりません」と `CloudOff` アイコン、並び順は変えない。各回一覧の同期シートはスイッチを無効化して
  「サーバ上で見つかりません（M/d から）。同期は止まっています。手元の回はそのまま聴けます」。その下に **「この番組を手元から消す」**（確認 → 番組・各回・ファイル・
  再生位置をすべて消す）。この操作は**消失した番組とサーバ ID の無い番組だけ**に出す（サーバに在る番組を消しても次の同期で戻り、再生位置だけ失うため）
- **到達不能の表示は無し**: 全走査しかないので番組単位の到達不能は起きない。ライブラリ全体の到達不能は今のスナックバーのまま。通知も出さない
- **実機確認**: #2 の再取り込みは自宅ライブラリを作り直す必要があるため **Robolectric のみ**（`FakeJellyfinGateway` で番組 ID・各回 ID の変更を模す）。
  シードの再現データは端末に残っておらず、シード自体を削除する（#21）ので第二段の実機確認はしない

## 作業の進め方

- 同期エンジンとデータ層はテストを先に書く
  - `:core:domain`: プレーン JUnit5。M1 分は 再生済み判定、ticks ↔ `Duration` 変換、
    放送日フォールバック（`PremiereDate` → 取り込み日時）、再開位置、各回の並び順（同着のタイブレーク）
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
- 他端末・Web との再生位置の共有（ローカル正。送信もしない、ADR 0007）
- Media3 DownloadManager / SimpleCache によるダウンロード
- `/Sessions/Playing/*` による「再生中」のサーバ通知（必要になれば M4 以降で検討）

## 参照

- `CONTEXT.md` — 用語集（番組／各回／放送日／同期対象／保持ルール／固定／判断保留／再生済み）
- `docs/adr/0001-local-surrogate-key.md` — 主キーはサーバ ID ではなく代理キー
- `docs/adr/0002-playback-position-local-authority.md` — 再生位置・再生済みはローカル正
- `docs/adr/0003-download-without-media3-downloadmanager.md` — DownloadManager を使わない
- `docs/adr/0004-sync-deletes-only-from-full-listing.md` — 同期の削除の権限は全走査の一覧だけ
- `docs/adr/0005-rematch-unlinked-rows-before-sync-deletes.md` — 突合の対象を未結合に広げ、同期の削除判断の前に走らせる
- Jellyfin 12 認証仕様: https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f
- jellyfin-sdk-kotlin Releases: https://github.com/jellyfin/jellyfin-sdk-kotlin/releases
- Jellyfin OpenAPI (stable): https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json
