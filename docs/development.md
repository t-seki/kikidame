# 開発ガイド

## 前提

| 項目 | 版 | 備考 |
| --- | --- | --- |
| JDK | **21** | AGP 9.4 の要件は 17+ だが、Robolectric 4.17 が SDK 37 のサンドボックスに Java 21 を要求する |
| Android SDK | platform `android-37.0`、build-tools `36.0.0`、platform-tools | `sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"` |
| Gradle | wrapper（9.7.1） | `./gradlew` が取得する |
| AGP | 9.4 | built-in Kotlin。`org.jetbrains.kotlin.android` は適用しない |

WSL2 で sudo を使わずに揃える例:

```bash
# JDK（Temurin）
mkdir -p ~/.local/jdk && cd ~/.local/jdk
curl -sSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
tar xzf jdk.tar.gz && ln -sfn jdk-21* current
export JAVA_HOME=~/.local/jdk/current

# Android cmdline-tools → SDK
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -sSL -o ct.zip https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
unzip -q ct.zip && mv cmdline-tools latest
yes | latest/bin/sdkmanager --licenses
latest/bin/sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
```

リポジトリ直下に `local.properties`（git 管理外）を置く:

```
sdk.dir=/home/<you>/Android/Sdk
```

`org.gradle.java.home` はコミットしない（CI と食い違う）。JDK は `JAVA_HOME` で渡す。

## ビルド・テスト

```bash
./gradlew test            # 全ユニットテスト（エミュレータ不要）。CI と同じ
./gradlew :app:assembleDebug
```

| モジュール | テスト基盤 |
| --- | --- |
| `:core:domain` | JUnit 5（`useJUnitPlatform`） |
| `:core:data` | JUnit 4 + Robolectric、`Room.inMemoryDatabaseBuilder` |
| `:app` | JUnit 4 + Robolectric。Media3 は `SimpleBasePlayer` を継承したフェイク `Player` |

Robolectric と JUnit 5 は統一しない（Robolectric の公式ランナーは JUnit 4）。
Robolectric が SDK 37 で JDK 内部 API へアクセスするための `--add-opens` はルートの
`build.gradle.kts` で Android モジュールのテストタスクに付けている。

Room のスキーマは `core/data/schemas/` に書き出す（`room { schemaDirectory(...) }`）。
Entity を変えたら version を上げ、書き出された JSON もコミットする。

## 並行作業（複数の Claude Code セッション）
方針は `~/.claude/CLAUDE.md` の「Parallel Work」（作業セッションは全部 worktree、main のチェックアウトは統合専用）。この repo 固有の手順:
- worktree は repo 直下の `.claude/worktrees/`（`.gitignore` 済み）。`local.properties` は git 管理外なので、新しい worktree に `cp local.properties <worktree>/` する
- Gradle の成果物（`build/`）は worktree ごとに別なので初回ビルドが重い。`~/.gradle` のキャッシュは共有されるので依存の再ダウンロードは無い
- 実機は 1 台。`adb install` は main のチェックアウトからだけ行い、作業セッションは実機を触らない（触るならひと言告げる）。再生画面を開く確認は音が出るので避ける
- Room のスキーマを上げる PR がマージされたら、それより古いビルドを実機に入れない（DB のダウングレードで落ちる）
- 担当は「同じファイルを触らない」単位で分ける（例: 2026-09-20 は `EpisodeListScreen.kt` 周りの #66→#68→#70 と、テーマ・設定・ミニプレイヤーの #59→#62 に分けて衝突なし）

### 作業セッションの閉じ方（#84）

2026-09-20 に main のローカルにマージ済みブランチ 8 本と用済みの worktree 1 つが残っていた。原因は、1 つの worktree で #66→#68→#70→#82 と 4 本の PR を回して worktree とブランチが 1:1 でなかったこと、`gh pr merge --delete-branch` がチェックアウト中のブランチをローカルでは消せないこと、レビュー用に `gh pr checkout` した `pr-N` ブランチを消していなかったこと。

- worktree の寿命は 1 セッション = 1 issue（密結合した issue の連鎖 1 本まで）。マージしたら別の仕事に流用せず閉じる。次の仕事は EnterWorktree で入り直す（既定で `origin/main` から切るので「main の HEAD から切る」も満たす）
- 閉じる手順（worktree の中で）:
  ```bash
  /usr/bin/git checkout --detach origin/main     # 掴んでいるブランチを離す
  /usr/bin/git branch -D feat/<自分で切ったブランチ>
  ```
  そのあと ExitWorktree(remove)。ExitWorktree はユーザーが言ったときだけ動くので、マージ後に「worktree を消して抜けて」と一言（セッション終了時の keep/remove で remove でもよい）。ExitWorktree が消すのは EnterWorktree が作った `worktree-<name>` ブランチだけなので、自分で切ったブランチは先に消しておく
- レビューは `gh pr diff` / `gh pr view` で読む。テストを走らせて確かめるときだけ worktree に入って checkout し、終わったら同じ手順で消す。実機に入れるのは上の通り main からだけ。main 側に `pr-N` ブランチを作らない

### 調整役セッション（main のチェックアウト）の責務

作業セッションは自分の issue しか見ていないので、横断する仕事は main のチェックアウトにいるセッションが持つ。

- 入口: epic を sub-issue に割り、各 issue に「触るファイル」を書く。作業セッションは issue を読んで自分で EnterWorktree する
- 出口: PR が来たら `/code-review:code-review` を回す（レビュー用の subagent は `pr-reviewer`（read-only）。general-purpose に振ると `gh pr checkout` して main 側に `pr-N` を作ることがある）。マージ順を決め、`gh pr merge --squash --delete-branch` → `git pull --ff-only` → `installDebug`。Room のスキーマ版数を上げる PR の後に古いビルドを入れないチェックもここ
- 掃除（マージのたび、セッション終了時に必ず）:
  ```bash
  git fetch --prune
  git worktree prune
  git worktree list      # 残っていれば git worktree remove <path>
  git for-each-ref refs/heads --format='%(refname:short) %(upstream:track)' | awk '$2=="[gone]" || $1 ~ /^pr-?[0-9]/{print $1}' | xargs -r git branch -D
  for b in $(git for-each-ref refs/heads --format='%(refname:short)' | grep '^worktree-'); do [ -d ".claude/worktrees/${b#worktree-}" ] || git branch -D "$b"; done
  ```
  1 行目は `gone`（`--delete-branch` でマージ済みのもの）と、general-purpose のレビュー subagent が `gh pr checkout` で作る `pr-N` を消す。チェックアウト中のブランチは `-D` が失敗して次に進むだけ。
  2 行目は EnterWorktree / `Agent` が作る `worktree-<name>` を、対応する `.claude/worktrees/<name>` が無いときだけ消す（作業中のセッションは `feat/…` に乗り換えた後も `worktree-<name>` を持っているので、ディレクトリの有無で見る）。
  `git branch` ではなく `for-each-ref` なのは、rtk が `git branch` の出力を整形して `* ` の行を足すため。upstream 無しのブランチを全部消す条件にはしない: worktree で切って push 前に worktree だけ消した `feat/…` まで消える（2026-09-20 に #85 のマージ後、`worktree-agent-<id>` と `pr85-view` が残ったのがこの手順の由来）
- 横串: 先にマージされた PR が後の PR に影響するとき、該当 issue にコメントを書く（「#N がマージされたので rebase して」）。同じマシンのセッションには `SendMessage` で通知してもよいが、正とするのは issue コメント（メッセージは揮発する）
- やらないこと: 実装（小物でも worktree に振る）、会話を状態の置き場にすること（cold start しても GitHub だけで復帰できる状態を保つ）、`/loop` での PR 監視（人が「PR 出た」と一言投げる）
- 対話が要らない issue（docs、issue を読めば完結する小さな実装）は、調整役から `Agent` を `isolation: "worktree"` で起動して任せてもよい。subagent は人に質問できないので、grill は起動前に調整役で済ませて issue / ADR に落としておく。subagent の worktree は変更があれば残るので、マージ後に上の掃除で消す

### `~/.claude/CLAUDE.md` の「Parallel Work」との関係

方針（全部 worktree・main は統合専用・共有は GitHub と docs と実機だけ）はそのまま。上の 2 節は repo 固有の手順で、CLAUDE.md は変えない。閉じ方と調整役の責務が他の repo でも使えると分かったら、その時点で CLAUDE.md（dotfiles）に格上げする。

## 実機で試す（M1）

### 接続: WSL2 の adb からワイヤレスデバッグで直接つなぐ（推奨）

WSL2 は USB を見られないが、LAN 上の端末には TCP で届く。Windows の adb を経由しないので
ファイアウォール設定は不要。PC が有線でも、端末と同じルータの下にいればよい。

1. 端末: 開発者向けオプション → ワイヤレスデバッグ ON → 「ペア設定コードによるデバイスのペア設定」
2. WSL2（初回のみ。ダイアログを開いたまま）:
   ```bash
   ADB=~/Android/Sdk/platform-tools/adb
   $ADB pair <ペア設定の IP:ポート> <6 桁コード>
   ```
3. 接続（端末の再起動後はポートが変わるので都度）:
   ```bash
   $ADB connect <接続用の IP:ポート>     # ワイヤレスデバッグ画面の上部に出ている方
   $ADB devices                         # 同じ端末が TCP と mDNS で複数見えることがある
   ```
4. インストールは Gradle から。複数に見えるときは `ANDROID_SERIAL` で接続用の名前を指定する:
   ```bash
   export JAVA_HOME=~/.local/jdk/current ANDROID_SERIAL=<接続用の IP:ポート>
   ./gradlew :app:installDebug
   ```

### 代替: Windows 側の adb に USB 接続

```powershell
adb install -r \\wsl.localhost\Ubuntu\home\<you>\dev\<repo>\app\build\outputs\apk\debug\app-debug.apk
```

### 音声ファイルを入れる（M1 当時の手順。シードは M3-c で削除）

M1 ではサーバ無しで試すため、`getExternalFilesDir("episodes")/<放送局>/<番組>/` に `adb push` したファイルを
デバッグビルドの「シード」が走査して取り込んでいた。M2 以降はサーバから落とせるので、この機能は #21 で削除した。
手元のファイルを直接入れたい場合は、番組・各回の行に結び付ける仕組みが無いので別途設計が要る（#21 の非目標）。

### 実機チェックリスト（M1）

2026-09-17 に Pixel 7a（Android 17）で確認済み。`©day` の項目のみ未確認。

- [x] （M1 当時）`adb push` した 2 階層のツリーがシードで取り込まれ、番組一覧 → 各回一覧 → 再生画面 と辿れる
- [x] 各回一覧が放送日の新しい順、同日の 2 本はタイトルの辞書順（`… (1)` / `-2` が後）
- [ ] タグの `©day` が放送日として読める（タグが無い／読めない場合はファイル名の日付）
- [x] 再生／一時停止／シーク／±30 秒／前後の回
- [x] 最後まで再生すると同じ番組の次の回（放送日の古い順）へ自動で進む。再生済みの回も飛ばさない
- [x] 一時停止 → アプリを kill → 再起動 → 同じ回を開くと保存位置から再開する
- [x] 再生中に kill しても、戻るのは最大 10 秒
- [x] 残り 2 分以内まで聴くと自動で再生済みになり、冒頭へシークしても解除されない
- [x] 再生済みの回を開くと先頭から始まる（止まっている回をタップしたとき。再生中の回の画面に戻っただけなら続き）
- [x] 手動の再生済み／未再生切替で再生位置が変わらない
- [x] 一覧の進捗バーは「次に開いたとき途中から再開する回」に出る（再生済みでも途中で止めた回に出る）
- [x] 通知・ロック画面に 再生／一時停止・30 秒戻る・30 秒進む が出て操作できる
- [x] Android 13+ で初回起動時に通知権限を求められる。拒否しても再生はできる
- [x] 画面を閉じても再生が続き、イヤホンを抜くと止まる

## 実機で試す（M2）

### つながらないときの切り分け

- **Android 17 / targetSdk 37 は `ACCESS_LOCAL_NETWORK` が要る**。無いと LAN 内のサーバ宛の TCP が黙って落ち、
  DNS だけ通る（名前解決は成功し、接続が 6 秒でタイムアウトする）。初回起動の権限ダイアログで許可する。
  `adb shell` の `nc` は対象外なので疎通確認に使えない。アプリの UID で試す:
  ```bash
  $ADB shell "run-as dev.tseki.kikidame sh -c 'timeout 6 nc -z <host> 443; echo rc=\$?'"   # rc=124 なら落ちている
  ```
- ゲートウェイの失敗は `JellyfinGateway` タグに原因の連鎖を出す:
  ```bash
  $ADB logcat -d | grep -E "JellyfinGateway|o.j.s.a.o.OkHttpClient" | tail
  ```
- 機内モードのテストで TCP のワイヤレス接続が切れることがある。`$ADB devices` の mDNS 側の名前
  （`adb-…_adb-tls-connect._tcp`）を `ANDROID_SERIAL` に使えばそのまま届く

### 実機チェックリスト（M2）

2026-09-17 に Pixel 7a（Android 17）と Jellyfin 10.11.11（`https://jellyfin.example.net`）で確認済み。

- [x] 接続画面でサーバ・ユーザー名・パスワードを入れてログインできる。違うパスワードは「ユーザー名またはパスワードが違います」
- [x] ライブラリ選択に全ライブラリが並び、音楽以外はグレーで「音楽ライブラリのみ選べます」。音楽が 1 つなら選択済み
- [x] 決定直後に「番組 N / 各回 M を取得しました」（153 番組 / 6,004 回、約 12 秒）
- [x] （M2 当時）シード済みの番組がサーバの番組と突合され重複しない（各回はタイトルが違うので別行 → #9）
- [x] 番組一覧に「手元 M / 全 N 回」。引っ張って更新で再取得
- [x] 手元に無い各回が薄く、右端が雲、タップしても再生画面へ行かない。手元の回は再生位置・再生済みが残る
- [x] 設定にサーバ・ユーザー・ライブラリ・最終取得。ライブラリの選び直しができる
- [x] ログアウト → 接続画面に URL とユーザー名が入力済み → ログインし直すと番組・再生位置が残っている
- [x] アプリを kill して再起動しても接続画面は出ず番組一覧
- [x] 機内モードで更新 → 「サーバに接続できません。手元の一覧を表示しています」→ 手元の回を再生できる
- [x] 「別のサーバに接続」→ 番組・各回・再生位置・セッションが消え、音声ファイルは残る（M2 当時。#50 からは音声ファイルも消える）（DB で確認）

## 実機で試す（M3-a: ダウンロード）

### 再開（`.part` + `Range`）の確認

LAN では 10 MB が 0.5 秒で落ちるので、ダウンロード中に kill しても再開の経路を踏めない。代わりに「途中まで落ちた状態」を作る:

```bash
$ADB shell am force-stop dev.tseki.kikidame
# DB を引き出し、対象の local_files.state を 'DONE' → 'PENDING' に書き換えて戻す（development.md 上の「DB だけ消す」と同じ run-as 手順）
$ADB shell "head -c 5000000 '<path>' > '<path>.part' && rm '<path>'"
$ADB logcat -c && <アプリを起動>
$ADB logcat -d | grep JellyfinGateway   # download <id> from 5000000 -> HTTP 206 Content-Range=bytes 5000000-.../...
```

`HTTP 206` と `Content-Range` が出て、ファイルが元のサイズで完成すれば再開できている。200 が出た場合は
サーバが `Range` を無視しており、Worker は `.part` を書き直す（フェイクでテスト済み）。

### 実機チェックリスト（M3-a）

2026-09-17 に Pixel 7a（Android 17）と Jellyfin 10.11.11 で確認済み。

- [x] 雲アイコンで 1 本落とし、再生できる。落とした回にピンが付く
- [x] ダウンロード中のキャンセルで行が雲に戻る
- [x] 「Wi-Fi のみ」ON でモバイル回線だと「Wi-Fi 待ち」のまま落ちず、Wi-Fi に戻ると落ちる
- [x] 途中の `.part` から `Range` で再開する（サーバは 206 を返す）
- [x] 長押し →「ファイルを削除」で雲に戻り、再生位置が残る。同期対象でない番組では「固定を外す」が出ない
- [x] ファイルを消した状態で更新すると「手元に無くなっていた 1 回の記録を整理しました」と出て雲に戻る
- [ ] 失敗の表示と再試行（機内モードでは Worker が起動しないため未確認。フェイクでテスト済み）

## 実機で試す（M3-b: 同期）

同期の入口は 1 つ（`LibraryRefresher`）。番組一覧を引っ張る＝ライブラリ全体の同期（153 番組 / 6,003 回で約 45 秒）、
シートの「この番組を今すぐ同期」＝その番組だけ同期（1〜2 秒）、各回一覧を引っ張る＝その番組だけ取り込む（削除なし）。
定期同期は WorkManager の `sync-periodic`（6 時間）、起動時同期は `sync-once`。状態は次で見える:

```bash
$ADB shell dumpsys jobscheduler | grep -A3 "dev.tseki.kikidame" | head -40
$ADB logcat -d | grep -E "SyncWorker|LibraryRefresher"   # "sync: 番組 N / 各回 M を取得。…"
```

起動時同期は前回同期から 1 時間以上あけないと積まれない（ログイン直後の初回取得も同期なので、ログインし直しでは試せない）。
待たずに Worker を走らせるなら、`dumpsys jobscheduler` で WorkManager のジョブ ID を見て `adb shell cmd jobscheduler run -f dev.tseki.kikidame <jobId>`。

### 実機チェックリスト（M3-b）

2026-09-18 に Pixel 7a（Android 17）と Jellyfin 10.11.11（153 番組 / 6,003 回）で確認。試験には「イースト駅前クリニックpresents川島明のねごと」（6 回）を使った。

- [x] 各回一覧の同期アイコン → シートで「この番組を同期する」を ON にすると「最新 3 回」が選ばれ、番組一覧の行に同期の印が付く
- [x] 「この番組を今すぐ同期」で最新 3 回がキューに入り（ピン無し）、順に落ちる。スナックバーに「3 回をダウンロード予約」
- [x] 「この番組を今すぐ同期」は数秒で終わり他の番組に触れない。番組一覧の引っ張りは全体同期（約 45 秒）で「番組 153 / 各回 6003 を取得…」
- [ ] 同期分が落ちている最中に別の回を手動で落とすと、手動の回が先に落ちる（LAN では 1 本 0.4 秒で落ちるため目視できず。Robolectric で担保）
- [x] N を 3 → 1 にして同期すると、外れた回が消えて再生位置は残る（サーバに新しい回が入る代わりに N を減らして確認）
- [x] 固定した回は N の外で残る（N = 1 で固定 1 本 + 最新 1 本が手元に残った）
- [x] 「再生済みなら削除」ON で再生済みにした回が次の同期で消え、落とし直されない
- [x] 保持されるはずの回を手で削除すると「…次の同期で落とし直されます」と出て、同期で戻ってくる
- [x] 同期 OFF にすると「固定されていない N 回のファイルが次の同期で削除されます」の確認が出る。確定後の同期で非固定の回が全部消え、固定は残る
- [x] 再生中の回が削除対象でも、その同期では消えない（再生位置 13 秒が残り、別の回を再生した後の同期で消えた）
- [ ] サーバ側で各回を消して同期すると固定していても一覧から消える／番組ごと消えると判断保留（サーバ側の操作が要るため未実施。`RoomSyncTest` で担保）
- [ ] サーバを止めて同期すると「サーバに接続できません」で何も消えず何も落ちない（同上。`LibraryRefresherTest` で担保）
- [x] 「Wi-Fi のみ」ON でモバイル回線で引っ張ると「Wi-Fi に接続していないため更新しません」
- [x] 各回一覧の引っ張って更新が 1 秒前後で終わり「各回 6 を取得しました」（#12）。削除も予約もしない
- [x] 起動時に `sync-once` が走る（logcat「sync: 番組 153 / 各回 6003 を取得」、全番組が同期 OFF なので何も落ちず何も消えない）。
  `sync-periodic` が登録され、前面に戻しても次回実行（Minimum latency ≈ 6h）がリセットされない

## 実機で試す（M3-c: 突合の結び直し）

再取り込み（サーバ ID の変更）は自宅ライブラリを作り直す必要があるため実機では確認せず、`RoomRematchTest`（番組・各回の ID が全部変わる／各回だけ変わる／本当に消えた回だけ消える／1 番組の同期で結び直す）で担保する（2026-09-18）。
手元に影響が出る変化は「再取り込み後に番組が二重にならない」「各回の ID だけ変わっても消えない」で、どちらも以前は起きていた。

## 実機で試す（M3-c: 消失の表示）

サーバ上に無い番組を作る手段: (a) 設定でライブラリを切り替える（旧ライブラリの番組が全部消失になる。音楽ライブラリが 2 つ以上あるとき）、
(b) サーバ側で 1 番組のフォルダを外してスキャン、(c) デバッグビルドなら端末の DB を書き換える（2026-09-18 に採用）:

```bash
$ADB shell am force-stop dev.tseki.kikidame
$ADB shell "run-as dev.tseki.kikidame sh -c 'cat databases/kikidame.db'" > dev.db   # -wal も取り、python の sqlite3 で checkpoint
# programs の 1 行を UPDATE: serverItemId を偽の UUID に、name に「（旧）」を付ける（同名だと突合で結び直される）
$ADB push dev.db /data/local/tmp/ && $ADB shell "run-as dev.tseki.kikidame sh -c 'rm -f databases/kikidame.db-wal databases/kikidame.db-shm; cat /data/local/tmp/dev.db > databases/kikidame.db'"
```

番組一覧を引っ張ると「1 番組はサーバ上で見つからず、そのままにしました」と出る。(c) では各回の ID が本物のままなので、各回はサーバの言うとおり
本物の番組（新しい行）に移り、「（旧）」は各回 0 の空の行になる（本当の再取り込みでは各回の ID も変わるのでこの形にはならない）。

### 実機チェックリスト（M3-c: 消失）

2026-09-18 に Pixel 7a で (c) の方法で確認。

- [x] 消失した番組の行に「サーバ上で見つかりません」と `CloudOff` アイコンが付き、並び順は変わらない（各回 0 なので末尾に来た）
- [x] その番組の同期シートでスイッチが無効になり「サーバ上で見つかりません（2026-09-18 から）。同期は止まっています…」と出る。「この番組を今すぐ同期」は出ず「この番組を手元から消す」が出る
- [ ] 消失した番組の手元の回はそのまま再生できる（(c) では各回が本物の行に移るため確認できず。固定していた 08-23 は移った先で残った）
- [x] 「この番組を手元から消す」→ 確認 → 番組一覧に戻り、番組が消えている（DB でも行が無い）
- [ ] 元のライブラリに戻して同期すると印が消える（`goneSince` が null に戻る）（実機では未実施。`RoomSyncTest` で全体同期・1 番組の同期・番組単位の更新の 3 経路を担保）
- [x] 設定画面・接続画面にデバッグ節（シード）が出ない（#21）

## 手元のファイルと DB の突き合わせ（#50）

`files/episodes/` にあるのに `local_files` のどの行の `path` でもないファイル（行の無いファイル）は、アプリからは二度と到達できず、容量の表示（#42）にも数えられない。
アプリの削除経路はすべて行のあとにファイルも消し、「別のサーバに接続」も #50 でファイルを消すようになった（再生とダウンロード・同期の Worker を止めてから消す）。
それでも残るのは、**開発手順で DB だけを消した／書き換えたとき**と、消している最中にプロセスが死んだときくらい。
DB を消すときは `files/episodes/` も一緒に消すこと:

```bash
D=/storage/emulated/0/Android/data/dev.tseki.kikidame/files/episodes
$ADB shell "rm -rf '$D'"
```

突き合わせ（行の無いファイルと、行はあるのにファイルが無い回を列挙し、前者を `stray.txt` に書く）:

```bash
D=/storage/emulated/0/Android/data/dev.tseki.kikidame/files/episodes
for f in kikidame.db kikidame.db-wal; do
  $ADB shell "run-as dev.tseki.kikidame sh -c 'cat databases/$f'" > dev-$f < /dev/null
done
$ADB shell "find '$D' -type f ! -name '*.part'" | sort > disk.txt
python3 - <<'PY'
import sqlite3
db = {r[0] for r in sqlite3.connect('dev-kikidame.db').execute("SELECT path FROM local_files WHERE path IS NOT NULL")}
disk = {l.strip() for l in open('disk.txt')}
stray = sorted(disk - db)
print('行の無いファイル:', *stray, sep='\n  ')
print('ファイルの無い行:', *sorted(db - disk), sep='\n  ')
open('stray.txt', 'w').write(''.join(p + '\n' for p in stray))
PY
```

行の無いファイルを消す（ループ内の `adb shell` は標準入力を食うので `< /dev/null` を付ける。付けないと 1 周で止まる）:

```bash
while IFS= read -r p; do $ADB shell "rm -f '$p'" < /dev/null; done < stray.txt
$ADB shell "find '$D' -type d -empty -delete"
```

ファイルの無い行は `reconcileMissingFiles`（#5）が次の同期で整える。2026-09-19 に M1 のシードの名残 6 ファイル・164.6 MB をこの手順で消し、ディスクと DB の合計が一致した。
