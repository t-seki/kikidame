# 同期

[アーキテクチャの入口](../architecture.md)の一部。


## 同期エンジンの設計

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

### 実行側の規則

`planSync` の外の規則。

- **同期の 1 回** = 全走査（`SourceGateway.fetchAll`。Jellyfin では `fetchLibrary`）→ 取り込み（突合・上書き）→ `planSync` → 削除の実行 → ダウンロード対象を `PENDING`（`pinned = false`）で enqueue → `DownloadScheduler.kick()`。転送は `DownloadWorker` に任せ、同期は自分でファイルを落とさない。**削除の権限を持つ入力は全走査だけ**（ADR 0003）
- 削除対象が PENDING / RUNNING / FAILED なら `cancel` 相当。**聴いている回（現在の `MediaItem`）はその同期では削除しない**（次回に持ち越す。ただし最後まで聴き終えて止まっている回は除外しない。消された後に ▶ を押すと再生に失敗し、キューを空にして理由をスナックバーに出す）。実行順は削除 → enqueue。同期分の enqueue 順は **番組をまたいで公開日の新しい順**
- **入口は `LibraryRefresher` に一本化**する（`synchronized(lock)` と走っている実行 `Run` で同時に 1 つに保つ。`Mutex` は使っていない）。バックグラウンドの実行の最中に来た手動の呼び出しはその実行に合流し、それ以外の重なった呼び出しは何もせず null を返す。ただし同期のシートを閉じたときの 1 番組の同期だけは、合流も捨てもせず、走っている実行が終わるのを待ってから走る。定期 `SyncWorker`・起動時・番組一覧の手動プルがすべてここを通る（各回一覧の手動プルと、同期のシートを閉じたときの 1 番組の同期も）
- **番組一覧の「引っ張って更新」= ライブラリ全体の手動同期**（`planSync` まで回す。番組の画面のどのタブで引っ張っても同じ）。**各回一覧の「引っ張って更新」= 1 番組の同期**（`syncProgram`）: `/Items?ids=…` で番組の存在を確かめ（空 → 消失 = 判断保留）、その番組の各回一覧を取り、取り込み → `planSync`（この番組だけ）→ 除去・削除・予約。全走査を待たずに終わる。他の番組には触れず、最終同期の時刻も更新しない（ADR 0003）。同期を OFF にした番組でも同じで、固定していない手元のファイルは消える。番組の行が無い・取得元 ID が無い番組は全走査に切り替わる（`LibraryRefresher.syncProgram(orFullSync = true)`）。番組一覧・各回一覧の引っ張って更新は `applicationScope` で走らせ（`LibraryRefresher.launchRefresh` / `launchSyncProgram`）、画面を離れても最後まで続く。設定画面の「最終同期」の値は `lastFetchedAt`（1 番組の同期は更新しない）
- **「Wi-Fi のみ」は同期にも効く**（手動を含む。取得元が端末のフォルダなら効かせない。domain の `wifiOnlyApplies`）。`SyncWorker` の制約は `DownloadWorker` と同じ（UNMETERED / CONNECTED、充電中は課さない。同期自体の通信は一覧の JSON だけで軽く、転送は `DownloadWorker` の制約で守られる）。手動は `LibraryRefresher` で `ConnectivityManager.isActiveNetworkMetered` を見て、従量制なら「Wi-Fi に接続していないため更新しません」で終える（裏で待たせない）。判定は全走査・番組単位・起動時・定期のすべてが通るが、**ゲートは取得元への取得だけを包む**: 先頭の `reconcileMissingFiles()`（ローカル I/O のみ）は従量制でも走らせる
- **起点**: 定期は `PeriodicWorkRequest` 3 時間ごと（`UPDATE` で起動時に登録し直す）。起動時は `ProcessLifecycleOwner` の `ON_START` で、前回同期から 15 分以上（`lastFetchedAt` と `lastAttemptedAt`（失敗した全走査も数える。全走査が取得元に問い合わせる直前に成功・失敗を問わず記録）の新しい方から数える）なら `OneTimeWorkRequest`（ユニーク `sync-once`、`KEEP`）を出す。同じ制約で待つ。定期・起動時の失敗は `Result.success()` で終え次回を待つ（スナックバー無し、Log のみ）
- **周期と閾値の根拠と電池の最適化**: 必須の要求は「アプリを開いたら、新しい回が手元に来ている（来はじめる）」で、開かずに手元に来るまでの遅れも縮めたいため、周期を 3 時間、起動時の閾値を 15 分にした。App Standby のバケットが RARE になりうるので、定期の同期は遅れうる（どれだけ遅れるかは未確認）。そのため設定の「ダウンロード」の節に、電池の最適化の対象のときだけ「電池の最適化を外す」の行を出す（`PowerManager.isIgnoringBatteryOptimizations`。`ON_RESUME` のたびに判定し直す。押すと `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` で OS の確認を出す。`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を Manifest に足した。Play を通さずに配るので方針の縛りは受けない。ADR 0005）。やらないこと: 録音時刻に合わせて起こす・取得元からの知らせ（webhook）を受ける（端末に外から届く経路が要り、FCM は非目標）、「次の予定」の時刻の表示（RARE では予定どおりに走らないことがあり誤解を招く）、同期の試みを数日分残す記録（「最終同期」の行に任せる）
- **同期の表示と結果**: 走っている間は裏の同期か操作による同期かを `LibraryRefresher.isSyncingInBackground` などで区別し、手動の操作が合流したら裏の表示から操作の表示に切り替える。共有フォルダの全走査のタグの読み込みの進み具合は `LibraryRefresher.tagProgress`。結果は変化だけを出す: 新しい回（取り込みで手元に新しく増えた各回。突合で結び直した回は数えない。`RefreshResult.newEpisodes`）・ダウンロード予約・削除の数、判断保留の番組があればその数。0 は省く。手動は変化が無ければ「最新の状態です」、裏の同期（定期・起動時）は変化があったときだけ出す（`messages` を collect している番組一覧・各回一覧を開いているときだけ。それ以外の画面にいる間や、閉じている間の成功した裏の同期の結果は後から出さない）。`RefreshResult` に `enqueued` / `deleted` / `onHold` を持つ。文言と見せ方は [ui.md](../ui.md)
- **失敗の記録と通知**: 全走査の最後の試み（成功・失敗とその理由・「Wi-Fi ではない」の見送り）と時刻を、手動でも裏でも `LibraryRefresher` が `SessionRepository.recordSyncAttempt` で DataStore に記録する
  （`SessionState.Ready.lastSyncAttempt`。`lastAttemptedAt` とは別の値で、`SyncScheduler.isStale` は変えない。1 番組の同期・401・取り消しは記録しない）。設定の「最終同期」の行は、
  それが `lastFetchedAt` より新しく成功でないときに補足の行を出す。裏の同期（`SyncWorker` → `LibraryRefresher.refreshInBackground`。手動が合流したら手動として扱う）が失敗で終わり、
  `lastFetchedAt` から 24 時間（`SyncWorker.NOTIFY_AFTER`）以上たっていれば、同期の失敗の通知（`sync/SyncFailureNotifier.kt`、チャンネル `sync`、`IMPORTANCE_LOW`）を 1 回出す。
  出したことを記録し（`syncFailureNotified`）、全走査が成功するまで出し直さない。成功すると通知と記録を消す。ログアウトと取得元を変えるときも消す（記録は同期の時刻と一緒に `SessionStore` で消え、
  通知は `LibraryRefresher.cancelAndAwait`（走っている実行の取り消しを待った後）と、401 でログアウトする 3 か所（`LibraryRefresher`・`DownloadWorker`・`LibraryPickViewModel`）で消す。
  `SyncWorker` も頭で、記録が無いのに通知が残っていれば消す）
- **手動削除と同期の往復は仕様として受け入れる**: 同期対象の番組で保持すべき回のファイルを手で消しても、次の同期で落とし直される。削除時にそれを知らせる（文言は [ui.md](../ui.md)）。聴かない回は再生済みにするのが正規の操作。「除外」状態は作らない
- **同期対象 OFF**: シートを閉じたときの 1 番組の同期でその番組の非固定ファイルが全部消える（`CONTEXT.md`「固定」の定義どおり）。OFF に切り替えるとき手元に非固定の回があれば確認を出す
- **保持ルール編集**: 各回一覧の同期のシートで、同期対象のスイッチ、「最新 N 回まで保持」（選択式 1 / 3 / 5 / 10 / 20 / 上限なし）、「再生済みなら削除」のスイッチ（後の 2 つは同期 OFF で無効）を編集する。
  ON に切り替えるたび `keepLatest` が null なら **既定 3** を入れる（「上限なし」にしたい場合は ON にした後で選ぶ。OFF → ON を往復すると 3 に戻る。「未設定」と「上限なし」を区別する列は足さない）。
  スイッチやチップを触った時点では保存するだけで同期しない。シートを閉じたとき、開いたときから同期の設定（ON / OFF、最新 N 回、再生済みなら削除）が変わっていれば、その番組の 1 番組の同期（`LibraryRefresher.launchSyncProgramAfterRunning`）が走る
  （閉じる経路は `onDismiss` でも「番組を手元から消す」の確認への遷移でも、開いたまま画面が作り直されたとき（回転など）でもよい。`EpisodeListContent` の `DisposableEffect` で伝える。OFF のときは保持ルールを比べないので、OFF → ON → OFF と戻して閉じても走らない）。
  走っている同期（手動・裏）があれば、合流も捨てもせず終わるのを待ってから走る（待っている間に `cancelAndAwait` されたら走らせない）。`applicationScope` で走り、画面を離れても続く。結果の文言は引っ張って更新と同じ。
  `sourceItemId` の無い番組はスイッチを無効にする（取得元に見つかっていないため同期できない）
- Room のスキーマ: `syncEnabled` / `keepLatest` / `deleteAfterPlayed` は番組の行にある。`Episode.addedAt` は差分取得を作らないので読み手が無いが、列は残す（取り込み日時の表示の補助に使える。消すならスキーマ変更が要る）

