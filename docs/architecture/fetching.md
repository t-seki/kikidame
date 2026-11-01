# 取得

[アーキテクチャの入口](../architecture.md)の一部。


## 取得元の境界とセッション

- 同期とダウンロードは、取得元の境界 `SourceGateway`（`fetchAll` / `fetchProgram` / `fetchProgramEpisodes` / `fetchProgramImage` / `fetchFileSize` / `openDownload`、`:core:data` の `data.source`）だけを通す（ADR 0007）。実装は Jellyfin の `JellyfinSource` と共有フォルダの `SharedFolderSource`。入口は `SessionSourceGateway`（`SourceGateway` の bind）で、今の接続の種類で振り分ける
- `:core:domain` に取得元のスナップショット型（`SourceProgram` / `SourceEpisode` / `SourceSnapshot`）と突合の純粋関数 `LibraryMatching.match` を置く。`:core:data` に `JellyfinGateway`（`signIn` / `listLibraries` / `fetchLibrary` ほか）と jellyfin-sdk-kotlin 実装、`SessionStore` を置く。Repository が突合結果を 1 トランザクションで Room に適用する。テストはフェイクのゲートウェイで行う
- `SessionState` は `SignedOut`（直前の取得元の入力補助として `lastServerUrl`・`lastUserName`・`lastSmb`・`lastDeviceFolder` を持つ）／`NeedsLibrary`（Jellyfin だけ）／`Ready`。`Ready` は `source: ConnectedSource`（`Jellyfin(session, library)`・`Smb(connection)`・`Device(folder)`）と `lastFetchedAt`・`lastAttemptedAt` を持つ
- DataStore（`SessionStore`）は、`smb_host` があれば SMB、`device_tree_uri` があれば端末のフォルダ、どちらも無ければ Jellyfin とみなす。SMB は `smb_host`・`smb_share`・`smb_path`・`smb_user`・`smb_password`（暗号化）・`smb_guest`、端末のフォルダは `device_tree_uri`・`device_name`・`device_signed_out` のキーで持つ
- 一度も接続していなければ、取得元の選択（「Jellyfin に接続」「NAS の共有フォルダ（SMB）」「端末のフォルダ」）から始まる。ログアウトした後は直前の取得元の接続画面へ戻る。設定の「取得元を変える」は確認の上でローカルデータ（音声ファイルを含む）を全部消す（処理は `SourceChanger`）。画面は [ui.md](../ui.md)
- Jellyfin のライブラリ選択は常に出す。`/UserViews` の全ライブラリを列挙し、音楽とブック以外は選べない。選べるライブラリが 1 つなら選択済みにする
- SMB は `SessionRepository.connectSharedFolder` が、共有の直下が読めたら保存する（失敗は `SourceException`: 認証の失敗は `Unauthorized`、届かないは `Unreachable`、読めないは `Failed`）。SMB の認証の失敗は 401 と同じく接続をやり直させる。名前が引けない・タイムアウトは到達不能（判断保留）。SMB の接続は 1 回の操作ごとに張って閉じる
- 端末のフォルダは `SessionRepository.connectDeviceFolder(treeUri)` が、authority が `com.android.externalstorage.documents` でなければ `UnsupportedFolderException`、そうなら永続権限（読み取り）を取り、直下が読めたら保存し、ほかに持っている永続権限を手放す（`releasePersistableUriPermission`）。「取得元を変える」でも、持っている永続権限を手放す。フォルダは OS のフォルダ選択（`ACTION_OPEN_DOCUMENT_TREE`）で選び、選んだ後は直下が読めるかだけを確かめる
  - 木は `DeviceFolderTree`（`FolderTree` の SAF 実装。`DocumentsContract` の窓口 `DeviceDocuments` を `ContentResolverDeviceDocuments` が実装し、テストは `MemoryDeviceDocuments`）で、相対パスから文書 ID への対応は親を一覧して名前で引く
  - 最初の操作で永続権限と根があることを確かめ、無いと分かったら `SourceException.Unauthorized`（`LibraryRefresher`・`DownloadWorker` がログアウトし、フォルダの選び直しへ）。「無いと分かった」とするのは、永続権限の一覧に読み取りが無い、provider が `SecurityException` を投げた、根の問い合わせに provider が「無い」（行の無い結果）と答えたときだけで、それ以外の失敗は SMB と同じく通常の失敗（到達不能）にする。根が無いのを null で返さない（全番組が消失扱いになるため）
  - 画面に出す場所は `DeviceFolder.displayPath` がツリーの文書 ID から作る（`primary:Dir/Sub` の形は `ExternalStorageProvider` の実装の決まりで、公開の契約ではない）。形が崩れていれば保存済みの `name` に戻す
- Android 17+（targetSdk 37）の `ACCESS_LOCAL_NETWORK` ランタイム権限が要る。無いと LAN 内のサーバ宛の TCP が黙って落ちる（DNS だけ通るので名前解決は成功し、接続がタイムアウトする）。`adb shell` や古い targetSdk のアプリは対象外なので、疎通の切り分けはアプリの UID で行う（[device-testing.md](../device-testing.md) の「つながらないときの切り分け」）。要求する場面は ADR 0007 の「端末のフォルダ」節の 7 で、判定は domain の `SessionState.usesLocalNetwork`

## Jellyfin サーバの前提

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

## Jellyfin のライブラリ構造と取得パス

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
  ▶ の直前は `GET /UserItems/{itemId}/UserData` を 1 回だけ読む（待つ長さと読めなかったときは「再生位置と再生済み」の節）

取得は毎回フル走査にする。差分取得（`DateCreated` 降順で取り、ローカルの最大取り込み日時に到達したら打ち切る）は**作らない**（ADR 0003）: 不完全な一覧を同期の入力にすると載っていないだけの各回を削除してしまい、
全走査を定期に回しても許容できる重さなら取り込み専用の経路を持つ価値が無い。各回一覧の「引っ張って更新」も、取り込みだけの経路ではなく 1 番組の同期（`syncProgram`）にそろえる。

サーバの `PremiereDate`（無ければ `DateCreated`）は日時で来るが、**日付部分だけ取って JST 0 時の `Instant`** にする
（日単位の値として並び順を安定させる）。

## 共有フォルダの走査

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
タグの読み取りは、遅いのは転送量ではなく往復の回数と見て、`BlockReader` が 256 KB ずつまとめて読み（位置ごとの塊を 4 つまで持つ）、往復を減らす。並列では読まない。進み具合は `ScanListener.onTagProgress` → `LibraryRefreshRepository.refresh(onProgress)` → `LibraryRefresher.tagProgress` で番組一覧の `BackgroundSyncBar` に出す。
聴いている回（削除と差し替えの予約から外す回。ADR 0011）は `refresh(excluded = { … })` で番組ごとに読み直す。番組ごとに予約した数は `onEnqueued` で流れ、`LibraryRefresher` は全走査が途中で失敗してもそれが 1 以上なら Worker を起こす（取り消し・認証の失敗では起こさない。取得元を変えるときは `SourceChanger` が Worker を止めてから走査を取り消し、手元を消すため）。

## 番組の画像

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
- **表示**: Coil（`coil-compose`、Apache-2.0）の `AsyncImage` に手元のファイルを渡す（`ui/ProgramImage.kt` の `ProgramImageView`）。出す場所と切り抜き方は [ui.md](../ui.md)。ミニプレイヤーは `MiniPlayerViewModel.programImage` が聴いている回の番組を購読して引く（`NowPlayingState` は画像を持たない）。
  プレビューは `ProgramImagePreview`（Coil の `LocalAsyncImagePreviewHandler`）で単色に差し替える
- **回の画像と再生まわり**: 回の画像（各回のファイルに埋め込まれた画像）は走査では読まず、再生に入った回の分だけ `EpisodeImage`（`MediaMetadataRetriever.embeddedPicture`。無ければ番組の画像のファイル）が読む。
  長辺 512px 以下に縮め（透過があれば PNG、無ければ JPEG 85）、`EpisodeImageAttacher`（`PlaybackService` が付けるリスナー）が聴く回に替わるたびに現在の項目を `replaceMediaItem` で `setArtworkData` 付きに差し替える（キューの全件は読まない）。
  通知・ロック画面・Auto の再生中の表示と、再生画面（`PlayerViewModel.episodeImage`。Coil に ByteArray を渡す `EpisodeImageView`）は、この `artworkData` を読む。ContentProvider は足さない。
  Auto のブラウズツリーの各項目（`BrowseTree`）には画像を出さない。Media3 が再生中のファイルの埋め込み画像を自分で `Player.mediaMetadata` に載せるかは確かめていない（未確認）。
  差し替えで音が途切れず再生位置も戻らないことは、release ビルドで実機を使って確かめた。ExoPlayer が同じ URI の項目の更新を作り直しなしで受けているか（内部の挙動）は確かめていない
- **チャプター**: 各回のファイルに埋め込まれたチャプターは、`PlayerViewModel` が `MediaController.currentTracks` の音声トラックの `Format.metadata`（media3 の `Chapter`、`@UnstableApi`）から読み、`PlayerUiState.chapters` に載せる（ADR 0004 が、再生画面は `MediaController` を購読するとしている範囲。`PlaybackService` 側では読まない）。取り出す処理は純粋な関数に分けた（`ui/player/EpisodeChapters.kt`）: `chaptersOf(Tracks)` は `isHidden` を除き開始の順に返し、`currentChapter(chapters, positionMs)` は開始が位置以下のうちで最後のもの（最初の開始より前なら無し）を返す。再生画面は同じプロセスの controller なので `Format.metadata` がオブジェクトのまま届く見込みだが、実機では確かめていない（未確認）。

