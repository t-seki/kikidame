# ダウンロードと差し替え

[アーキテクチャの入口](../architecture.md)の一部。


## ダウンロード

- **転送**: `SourceGateway.openDownload(episodeId, rangeStart)`（Jellyfin の実装は `JellyfinGateway.openDownload(credentials, episodeSourceId, rangeStart)`）。SDK にはストリーミング取得の API が無い（`getDownload` / `request` は本文を `byte[]` に全部読む）ので、**URL は SDK の `getDownloadUrl`、`Authorization` ヘッダは SDK の `AuthorizationHeaderBuilder` に作らせ、転送だけ OkHttp（SDK の依存に同梱）で行う**。エンドポイントと認証形式は手書きしない。
  戻り値は `resumedFrom`（206 で `Range` が効いたか）・`totalBytes`・本文ストリーム。サーバが `Range` を無視して 200 を返したら `.part` を書き直す。テストはフェイク（206 再開／200 全体再送の両方）
- **Worker**: WorkManager のユニーク Worker（`download-queue`、`KEEP`）が、`LocalFile.state = PENDING` または `replaceState = PENDING` の行を **1 本ずつ**処理する。順は `nextPending()` の `(state = PENDING かつ pinned) DESC, enqueuedAt IS NULL, enqueuedAt ASC, publishedAt DESC, title ASC, id ASC`: 手動の予約（固定した回の新規ダウンロード）が先で、そのなかはキューに入れた順（FIFO）。差し替えは同期の予約なので、固定した回の差し替えでも手動の予約の後に並ぶ。`enqueuedAt` は nullable なので NULL を先頭に来させない。再試行は列の末尾へ。
  `<配信元>/<番組>/<タイトル>.<container>.part` に追記し、完了でリネームする。既存の `.part` は `Range: bytes=<size>-` で再開する。進捗は `setProgress` で UI へ出す。通知は出さない（フォアグラウンドサービスにしない）。
  同期分（`pinned = false`）の行に利用者が「ダウンロード」を選んだら `pinned = true` にする
- **条件**: 設定の「Wi-Fi のみ」（既定 ON、`AppSettings` DataStore）。ON なら `UNMETERED`、OFF なら `CONNECTED`。待ちの間は「Wi-Fi 待ち」と表示する。取得元が端末のフォルダならネットワークの条件をかけない（`NOT_REQUIRED`）。充電中の条件は課さない
- **失敗**: 1 本失敗しても次へ。`attemptCount` +1、`lastAttemptAt`、`FAILED`。同一実行内では再試行しない。次の起動で `attemptCount < 3` を PENDING に戻す。3 回超えは手動の再試行だけ。401 は `LibraryRefresher` と同じくログアウト
- **キャンセル**: DONE 以外の行を消し `.part` も消す。Worker は約 1 MB ごとに DB を見てスキップする
- **行の操作**（各回一覧。画面は [ui.md](../ui.md)）: 右端のアイコンは行の状態を表し、タップで最も自然な 1 操作を行う（雲 → ダウンロード（= 固定）、進捗リング → キャンセル、警告 → 再試行、手元にある回は再生済み切替）。長押しのシートに、固定する／固定を外す／ファイルを削除／再生済み切替／ダウンロード／詳細がある。
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

## 差し替え

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

## 手元のファイルの整理

- **取得元に見つからない番組をまとめて消す**: 対象は上の「手元から消せる番組」と同じ範囲で、0 番組なら操作を出さない。確認では番組の数・手元の回（`DONE` でパスがある回）の数・うち固定の数を出し、固定した回や再生途中の回も区別せずに消す（`DownloadRepository.removeUnmatchedPrograms` が番組ごとに `removeProgram` を呼ぶ）。聴いている回（`NowPlaying.excludedFromSync` と同じ定義）の番組だけは消さずに残して知らせる。同期の間（`isRefreshing` か `isSyncingInBackground`）は操作を無効にする。画面は [ui.md](../ui.md)
- **手元のファイルの内訳と、選んだ回を消す**: 内訳の画面（`StorageScreen`）の手元の回は `DONE` でパスのある回（`LOCAL_FILE_PRESENT`、合計の `observeUsage` と同じ定義）で、容量は各回の `sizeBytes` の合計（ストレージ上の実際のファイルサイズは測らない）。番組ごとの内訳は重ならない 3 つ（固定・固定でない再生済み・固定でない未再生。`LocalFileDao.observeProgramStorage`）で、全部の回の一覧は `observeLocalEpisodes`。番組ごとの手元の回の一覧は `StorageProgramScreen`。
  消すのは `LocalStorageRepository.deleteLocalEpisodes` で、今も手元にある回だけを 1 回ずつ `DownloadRepository.deleteLocal`（`deletionScopeFor` の規則）で消し、消す時点の聴いている回は除いて知らせる。固定は `local_files.pinned` が持つので、固定の回を消すと次の同期では非固定として扱われ、「最新 N 回」に入れば落とし直され、別の非固定の回を枠から押し出しうる（`SyncPlanner.planProgram`）。どの回が落とし直されるかは計算しない。同期の間は消すボタンを無効にする。「すべて選ぶ」と、条件でまとめて消す操作は作らない。各回一覧のシートの「ファイルを削除」（確認なし・聴いている回を除かない）は別の操作。画面は [ui.md](../ui.md)。`LocalStorageRepository` の束ね（`@Binds`）は `di/DataModule.kt` ではなく `repository/RoomLocalStorageRepository.kt` の `LocalStorageModule` に置く

