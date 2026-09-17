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
adb install -r \\wsl.localhost\Ubuntu\home\<you>\dev\jellyfin-radio\app\build\outputs\apk\debug\app-debug.apk
```

### 音声ファイルを入れる

radirec-tool の出力ツリーをそのまま push する（`<放送局>/<番組>/<番組> YYYY-MM-DD.m4a`）。

```bash
$ADB push "/mnt/z/Radio/TBSラジオ/番組名" "/sdcard/Android/data/dev.tseki.jellyfinradio/files/episodes/TBSラジオ/"
$ADB shell ls -R /sdcard/Android/data/dev.tseki.jellyfinradio/files/episodes
```

- Windows の adb で日本語パスを push すると、**引数末尾のマルチバイト文字が欠ける**ことがある
  （`TBSラジオ/` → `TBSラジ`）。WSL2 の adb（UTF-8）なら起きない。化けたときは改名コマンドを
  UTF-8・LF のシェルスクリプトにして `adb push` → `adb shell sh <script>` で実行する（引数に日本語を通さない）
- シード済みのファイルを消して入れ直すときは DB も消す（パスをキーに既存行をスキップするため。
  消えたファイルの行の整理は #5）。`pm clear` は push したファイルまで消すので、デバッグ特権で DB だけ消す:
  ```bash
  $ADB shell am force-stop dev.tseki.jellyfinradio
  $ADB shell run-as dev.tseki.jellyfinradio rm -f databases/jellyfin-radio.db databases/jellyfin-radio.db-wal databases/jellyfin-radio.db-shm
  ```

アプリを開き、番組一覧右上の「シード」（デバッグビルドのみ）を押す。走査先は
`getExternalFilesDir("episodes")`。`filesDir` に変えないこと — root 無しの `adb push` が通らない。

タグ（`©day`）が読めているかを切り分けたいときは、日付を含まないファイル名（`tagtest.m4a` など）で 1 本
入れる。各回一覧の日付が元の放送日ならタグ経由、push した日ならファイル更新日時へのフォールバック。

### 実機チェックリスト（M1）

2026-09-17 に Pixel 7a（Android 17）で確認済み。`©day` の項目のみ未確認。

- [x] `adb push` した 2 階層のツリーがシードで取り込まれ、番組一覧 → 各回一覧 → 再生画面 と辿れる
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
  $ADB shell "run-as dev.tseki.jellyfinradio sh -c 'timeout 6 nc -z <host> 443; echo rc=\$?'"   # rc=124 なら落ちている
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
- [x] シード済みの番組がサーバの番組と突合され重複しない（各回はタイトルが違うので別行 → #9）
- [x] 番組一覧に「手元 M / 全 N 回」。引っ張って更新で再取得
- [x] 手元に無い各回が薄く、右端が雲、タップしても再生画面へ行かない。手元の回は再生位置・再生済みが残る
- [x] 設定にサーバ・ユーザー・ライブラリ・最終取得。ライブラリの選び直しができる
- [x] ログアウト → 接続画面に URL とユーザー名が入力済み → ログインし直すと番組・再生位置が残っている
- [x] アプリを kill して再起動しても接続画面は出ず番組一覧
- [x] 機内モードで更新 → 「サーバに接続できません。手元の一覧を表示しています」→ 手元の回を再生できる
- [x] 「別のサーバに接続」→ 番組・各回・再生位置・セッションが消え、音声ファイルは残る（DB で確認）

## 実機で試す（M3-a: ダウンロード）

### 再開（`.part` + `Range`）の確認

LAN では 10 MB が 0.5 秒で落ちるので、ダウンロード中に kill しても再開の経路を踏めない。代わりに「途中まで落ちた状態」を作る:

```bash
$ADB shell am force-stop dev.tseki.jellyfinradio
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
$ADB shell dumpsys jobscheduler | grep -A3 "dev.tseki.jellyfinradio" | head -40
$ADB logcat -d | grep -E "SyncWorker|LibraryRefresher"   # "sync: 番組 N / 各回 M を取得。…"
```

起動時同期は前回同期から 1 時間以上あけないと積まれない（ログイン直後の初回取得も同期なので、ログインし直しでは試せない）。
待たずに Worker を走らせるなら、`dumpsys jobscheduler` で WorkManager のジョブ ID を見て `adb shell cmd jobscheduler run -f dev.tseki.jellyfinradio <jobId>`。

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
