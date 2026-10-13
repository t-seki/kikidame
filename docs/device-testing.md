# 実機で試す手順

実機（debug 版）で確かめるときに今も使う手順。#305 で [development.md](development.md) から分けた。
チェックリストは [device-checklists.md](device-checklists.md)（未確認の項目が残るもの）と [device-checklists-archive.md](device-checklists-archive.md)（済んだもの）にある。
ビルド・テスト・リリース・Supervisor の手順は [development.md](development.md)。

## 実機で試す（M1）

### 接続: WSL2 の adb からワイヤレスデバッグで直接つなぐ（推奨）

WSL2 は USB を見られないが、LAN 上の端末には TCP で届く。Windows の adb を経由しないので
ファイアウォール設定は不要。PC が有線でも、端末と同じルータの下にいればよい。

1. 端末: 開発者向けオプション → ワイヤレスデバッグ ON → 「ペア設定コードによるデバイスのペア設定」
2. WSL2（初回と、鍵が合わなくなったとき。ダイアログを開いたまま）:
   ```bash
   ADB=~/Android/Sdk/platform-tools/adb
   PKG=dev.tseki.kikidame.debug
   $ADB mdns services                   # _adb-tls-pairing._tcp の行がペア設定の IP:ポート（ダイアログを開いている間だけ出る）
   $ADB pair <ペア設定の IP:ポート> <6 桁コード>
   ```
   `PKG` は debug 版の applicationId で、以下の手順の `$PKG` はこれを指す。release 版（`dev.tseki.kikidame`、Releases の APK）は
   debuggable でないので `run-as` が効かない。開発中の確認は debug 版で行う（#128）

   人に聞くのは 6 桁コードだけでよい
3. 接続（端末の再起動後はポートが変わるので都度）:
   ```bash
   $ADB mdns services                   # _adb-tls-connect._tcp の行が接続用の IP:ポート
   $ADB connect <接続用の IP:ポート>     # ワイヤレスデバッグ画面の上部に出ている方と同じ
   $ADB devices                         # 同じ端末が TCP と mDNS で複数見えることがある
   ```
   `connect` が `failed to connect` になったら、adb サーバのログを見る:
   ```bash
   tail -n 20 /tmp/adb.$(id -u).log
   ```
   `SSLV3_ALERT_CERTIFICATE_UNKNOWN` なら、端末がこの PC の鍵（`~/.android/adbkey`）を知らない。1 からペアリングし直す
   （2026-09-23 は、adb デーモンの起動時に鍵が新しく作られていてこうなった）。端末の開発者向けオプションの
   「ADB 認証のタイムアウトを無効にする」が OFF だと、7 日使わなかった PC の承認も切れる
4. インストールは Gradle から。複数に見えるときは `ANDROID_SERIAL` で接続用の名前を指定する:
   ```bash
   export JAVA_HOME=~/.local/jdk/current ANDROID_SERIAL=<接続用の IP:ポート>
   ./gradlew :app:installDebug
   ```

`adb mdns services` は、adb サーバを起動した直後には何も出さず、少し待つと出たことがある（2026-10-07、素の Linux。原因は未確認）。空なら少し待って打ち直す。

### debug の署名鍵をマシン間で共有する

debug の APK はマシンごとの `~/.android/debug.keystore` で署名される。別のマシンでビルドした debug 版が端末に入っていると、このマシンのビルドの上書きインストールが `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で失敗する（`uninstall` すればデータが消える）。消さずに続けるには、端末に入っている方のマシンの鍵をコピーする。

確かめたこと（2026-10-07）: WSL の鍵を devbox にコピーして `INSTALL_FAILED_UPDATE_INCOMPATIBLE` を解消した。解消後のビルドの APK で `apksigner verify --print-certs` の SHA-256 が、端末の debug 版の署名と一致した。

次は流していない手順の案（**未確認**）。コピーの方法は `scp` 以外でもよい。

```bash
# 元のマシンで（コピー先の ~/.android は無ければ作っておく）
scp ~/.android/debug.keystore <host>:~/.android/debug.keystore
# コピー先で
chmod 600 ~/.android/debug.keystore
```

指紋は、コピー後の鍵でビルドした APK の証明書の SHA-256 を、端末に入っている debug 版の署名と比べる。端末の署名の取り方は記録が無く、**未確認**（案: `$ADB shell pm path dev.tseki.kikidame.debug` で APK のパスを見て `$ADB pull` し、同じ `apksigner` にかける）。`apksigner` は build-tools の中にあり、版は `ls ~/Android/Sdk/build-tools/` で確かめる。

```bash
APKSIGNER=~/Android/Sdk/build-tools/36.0.0/apksigner   # 版は ls ~/Android/Sdk/build-tools/ で確かめる
$APKSIGNER verify --print-certs app/build/outputs/apk/debug/app-debug.apk | grep SHA-256
```

鍵のファイルをチャットや issue に貼らない。

### 代替: Windows 側の adb に USB 接続

```powershell
adb install -r \\wsl.localhost\Ubuntu\home\<you>\dev\<repo>\app\build\outputs\apk\debug\app-debug.apk
```

## 実機で試す（M2）

### つながらないときの切り分け

- **Android 17 / targetSdk 37 は `ACCESS_LOCAL_NETWORK` が要る**。無いと LAN 内のサーバ宛の TCP が黙って落ち、
  DNS だけ通る（名前解決は成功し、接続が 6 秒でタイムアウトする）。初回起動の権限ダイアログで許可する。
  `adb shell` の `nc` は対象外なので疎通確認に使えない。アプリの UID で試す:
  ```bash
  $ADB shell "run-as $PKG sh -c 'timeout 6 nc -z <host> 443; echo rc=\$?'"   # rc=124 なら落ちている
  ```
- ゲートウェイの失敗は `JellyfinGateway` タグに原因の連鎖を出す:
  ```bash
  $ADB logcat -d | grep -E "JellyfinGateway|o.j.s.a.o.OkHttpClient" | tail
  ```
- 機内モードのテストで TCP のワイヤレス接続が切れることがある。`$ADB devices` の mDNS 側の名前
  （`adb-…_adb-tls-connect._tcp`）を `ANDROID_SERIAL` に使えばそのまま届く

## 実機で試す（M3-a: ダウンロード）

### 再開（`.part` + `Range`）の確認

LAN では 10 MB が 0.5 秒で落ちるので、ダウンロード中に kill しても再開の経路を踏めない。代わりに「途中まで落ちた状態」を作る:

```bash
$ADB shell am force-stop $PKG
# DB を引き出し、対象の local_files.state を 'DONE' → 'PENDING' に書き換えて戻す（debug 版の run-as。「裏の同期をすぐ起こす」と同じ形）
W=$(mktemp -d)   # repo の外に置く
for f in kikidame.db kikidame.db-wal; do $ADB exec-out run-as $PKG cat databases/$f > $W/$f; done
python3 -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); print(c.execute(\"UPDATE local_files SET state='PENDING' WHERE episodeId=? AND state='DONE'\", (sys.argv[2],)).rowcount); c.commit()" $W/kikidame.db '<episodeId>'   # 1 なら書き換わった（WAL は開いたときに取り込まれる）
$ADB shell run-as $PKG rm -f databases/kikidame.db-wal databases/kikidame.db-shm
$ADB exec-in run-as $PKG sh -c 'cat > databases/kikidame.db' < $W/kikidame.db
rm -r $W
$ADB shell "head -c 5000000 '<path>' > '<path>.part' && rm '<path>'"
$ADB logcat -c && <アプリを起動>
$ADB logcat -d | grep JellyfinGateway   # download <id> from 5000000 -> HTTP 206 Content-Range=bytes 5000000-.../...
```

`HTTP 206` と `Content-Range` が出て、ファイルが元のサイズで完成すれば再開できている。200 が出た場合は
サーバが `Range` を無視しており、Worker は `.part` を書き直す（フェイクでテスト済み）。

## 実機で試す（M3-b: 同期）

同期の入口は 1 つ（`LibraryRefresher`）。番組一覧を引っ張る＝ライブラリ全体の同期（153 番組 / 6,003 回で約 45 秒）、
各回一覧を引っ張る＝その番組だけ同期（1〜2 秒。シートの「この番組を今すぐ同期」は #281 でなくした。取り込み → この番組だけの `planSync`。#279）。
定期同期は WorkManager の `sync-periodic`（6 時間）、起動時同期は `sync-once`。状態は次で見える:

```bash
$ADB shell dumpsys jobscheduler | grep -A3 "$PKG" | head -40
$ADB logcat -d | grep -E "SyncWorker|LibraryRefresher"   # "sync: programs=N episodes=M new=… enqueued=… deleted=… onHold=…"
```

起動時同期は前回の全走査から 1 時間以上あけないと積まれない。前回の全走査は成功（`lastFetchedAt`）と試み（`lastAttemptedAt`。
サーバに届かず失敗した回も含む）の新しい方で見る（#135。外出中に前面に出すたびサーバへ接続し直さないため）。
手動・定期の全走査も試みとして数える。「Wi-Fi のみ」で打ち切った回と 1 番組の同期は数えない。
ログイン直後の初回取得も同期なので、ログインし直しでは試せない。

### 裏の同期をすぐ起こす（#140）

確実なのは、セッションの Preferences DataStore から前回の全走査の時刻（`last_fetched_at` / `last_attempted_at`）を消してから開く方法。
次に開いたとき起動時同期が必ず積まれ、何度でも繰り返せる。`run-as` を使うので debug 版だけ。
ファイルにはトークンの暗号文が入るので、中身を表示しない・コミットしない。キーを消すのは `scripts/datastore-drop-keys.py`（キー名だけ出す）:

```bash
$ADB shell am force-stop $PKG      # 動いている DataStore に上書きされないよう先に止める
F=/tmp/kikidame-session.pb       # repo の外に置く（うっかりコミットしないため）
(umask 077; $ADB exec-out run-as $PKG cat files/datastore/session.preferences_pb > $F)   # 最初から 0600 で作る
scripts/datastore-drop-keys.py $F $F last_fetched_at last_attempted_at   # dropped: … / kept: …
$ADB exec-in run-as $PKG sh -c 'cat > files/datastore/session.preferences_pb' < $F
rm $F
$ADB logcat -c && <アプリを開く>
$ADB logcat -d | grep -E "SyncWorker|LibraryRefresher"
```

`kept:` にログイン（サーバ・トークン等）とライブラリのキーが残っていればよい。`not found:` は、そのキーがまだ書かれていない
（全走査を一度もしていない）ということで、そのまま進めてよい。

`jobscheduler` から直接走らせる手もあるが、当てにならない:

- WorkManager のジョブは namespace `androidx.work.systemjobscheduler` に入るので、`-n` を付けないと `Could not find job N` になる。
  ジョブ ID は `dumpsys jobscheduler` で見る:
  ```bash
  $ADB shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler $PKG <jobId>
  ```
- ジョブ ID はアプリを前面に出すたびに（WorkManager が登録を `UPDATE` し直すので）変わる。打つ直前に見直す
- 定期同期（`sync-periodic`）は次の時刻より前だと、走っても WorkManager が
  `Delaying execution for SyncWorker because it is being executed before schedule.` で見送る
- 起動時同期（`sync-once`）は上の 1 時間の条件を満たさないとそもそも積まれない

ワイヤレス ADB のまま Wi-Fi を切って外出中を再現すると ADB も切れる。ログは Wi-Fi を戻して接続し直してから
`logcat -d` で後から読む。繋ぎ直すときはポートが変わっていることがあるので `$ADB mdns services` で見直す。

## Android Auto を DHU で確認する（#96）

車が無くても、Desktop Head Unit（DHU）が車載機の代わりになる。Android Auto はアプリを**スマホ側で**動かし、車載機には画面と操作だけを投影する。つまり DHU に出るブラウズツリーも、そこから鳴る音も `PlaybackService`（`MediaLibraryService`）と Room の中身であり、再生位置と再生済みは車で聴いてもアプリの各回一覧にそのまま反映される（別デバイスではないので同期は要らない）。

### 準備（1 回だけ）

1. SDK Manager（`sdkmanager --install "extras;google;auto"`、または Android Studio の SDK Tools → "Android Auto Desktop Head Unit Emulator"）で DHU を入れる。`~/Android/Sdk/extras/google/auto/desktop-head-unit` に置かれる。WSL2 では WSLg があれば GUI がそのまま出る（Linux 版のバイナリなので `chmod +x` が要ることがある）
2. スマホに Play から Android Auto アプリを入れ、開発者モードにする: Android Auto の設定 → 「バージョン」を 10 回タップ → 右上メニュー「開発者向け設定」→ **「提供元不明のアプリ」を ON**（debug 版の「Kikidame (debug)」も release 版も Play を通っていないので、これが無いと一覧に出ない）→ 同じメニューの「ヘッドユニット サーバーを起動」
3. アプリを実機に入れる（`./gradlew :app:installDebug`。main のチェックアウトから）

### つなぐ

```bash
$ADB forward tcp:5277 tcp:5277
~/Android/Sdk/extras/google/auto/desktop-head-unit
```

DHU の窓が開き、スマホ側で Android Auto が始まる。DHU のメディアタブ（音符のアイコン）に Kikidame が出る（debug 版と release 版の両方が入っていれば 2 つ並ぶ。確認するのは「Kikidame (debug)」）。出ないときは「提供元不明のアプリ」と、`AndroidManifest.xml` の `<meta-data android:name="com.google.android.gms.car.application">` と `android.media.browse.MediaBrowserService` の action を疑う。DHU 側で `Ctrl+M` の後に `d` でデイ／ナイトが切り替わり、コンソールには `help` でコマンド一覧が出る（`mic play <file>` は使わない: 音声アシスタントは非目標）。

Auto はブラウズツリーを開き直すたびに `onGetChildren` を呼ぶので、ダウンロードや同期で手元の回が増減したら、タブを行き来すれば最新になる（変化の押し通知は今はしない）。

### 落とし穴（2026-09-20 に #112 で踏んだもの）

- DHU は `libc++1` `libc++abi1` が要る（Ubuntu: `sudo apt install -y libc++1 libc++abi1`）。`ldd ~/Android/Sdk/extras/google/auto/desktop-head-unit | grep 'not found'` が空になれば足りている
- バックグラウンド（Claude Code 等）から起動すると stdin が EOF になって即終了する（`Version: 2.0-linux` を出して exit 0）。`sleep 1d | ~/Android/Sdk/extras/google/auto/desktop-head-unit` か、FIFO を stdin にする（作業ディレクトリはどこでもよい）:

  ```bash
  mkfifo dhu.in; exec 3<>dhu.in
  ~/Android/Sdk/extras/google/auto/desktop-head-unit <&3 &
  printf 'tap 350 140\n' > dhu.in     # コンソールコマンド（tap x y / dpad / keycode）を外から送れる
  ```

  コンソールの出力（`help` の一覧など）は tty でないと見えない
- 初回はスマホに「この車で使う」許可画面（FRX）が出る。5 秒以内に進めないと `PROJECTION_NOT_STARTED` で切れる（logcat に `CarInfo authorization: UNKNOWN` → `Triggering FRX`）ので、スマホの画面を見ながら繋ぐ
- DHU を途中で落とすとスマホのヘッドユニットサーバーが「Already connected」で固まる（`DeveloperHeadUnitNetworkService` の FATAL）。DHU 側は「Waiting for phone」のまま。Android Auto の ⋮ から「ヘッドユニット サーバーを停止」→「起動」で直る
- WSLg では `DISPLAY=:0 xwininfo -root -tree` で "Android Auto - Desktop Head Unit" の窓 ID を取り、`DISPLAY=:0 import -window <id> shot.png`（ImageMagick）で DHU の画面が撮れる。右半分に Google マップの現在地が映るので、共有するときは注意
- portaudio の `Device unavailable` は無視してよい（DHU 側で音は出ないが再生は進む）
- debug 版は applicationId が別（`dev.tseki.kikidame.debug`、#128）なので、`installDebug` は release 版に触らない（#128 より前は同じ ID だったので、release 版の入った実機では `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（署名不一致）で失敗していた）。release 版そのものをデータを残したまま手元のビルドで更新するなら、release 署名で上書きする:

  ```bash
  source ~/.android-keys/kikidame.env          # export KIKIDAME_KEYSTORE=… 等を書いた chmod 600 のファイル（git 管理外）
  ./gradlew :app:assembleRelease
  $ADB pull "$($ADB shell pm path dev.tseki.kikidame | cut -d: -f2)" installed.apk
  APKSIGNER=~/Android/Sdk/build-tools/36.0.0/apksigner   # 版は ls ~/Android/Sdk/build-tools/ で確かめる
  $APKSIGNER verify --print-certs installed.apk | grep SHA-256                        # 実機の証明書
  $APKSIGNER verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256   # 一致すること
  $ADB install -r app/build/outputs/apk/release/app-release.apk
  ```

  **`.env` を `grep` や `cat` で表示しない**（rtk は `grep -c` でも一致行を出す）。存在確認は `test -r` で

## 実機で試す（#198: SMB の共有フォルダ）

### 準備

- 共有に `<配信元>/<番組>/<各回のファイル>` の 3 段で、m4a と mp3 を置く（タグの付いたものと、タグの無いものの両方）。`@eaDir` や `#recycle` のような名前が `.`・`@`・`#` で始まるフォルダも混ぜておく
- debug 版は `dev.tseki.kikidame.debug`（[development.md](development.md) の「マージ前の検証」の手順 3）。`$ADB` と `$PKG` は「実機で試す（M1）」の接続の節のとおり
- 失敗の原因は logcat に出る。接続画面で失敗したときは `SmbConnect` タグ、同期・ダウンロードで共有フォルダに触って失敗したときは `SessionSourceGateway` タグに、原因の連鎖付きで出る（認証の失敗は `SourceException.Unauthorized`、届かないは `Unreachable`）。クラッシュは `AndroidRuntime`:
  ```bash
  $ADB logcat -d | grep -E "SmbConnect|SessionSourceGateway|AndroidRuntime" | tail -50
  ```

## 実機で試す（#199: 端末のフォルダ）

### 準備

- debug 版は `dev.tseki.kikidame.debug`（[development.md](development.md) の「マージ前の検証」の手順 3）。`$ADB` は「実機で試す（M1）」の接続の節のとおり
- 端末の内部ストレージに、試験用の 3 段のフォルダを `adb push` で置く。`SRC` は手元の m4a と mp3（タグの付いたものと無いもの）を置いたフォルダに読み替える。`@eaDir` のような無視する名前のフォルダと、音声でないファイルも混ぜる:
  ```bash
  SRC=~/kikidame-test-audio
  $ADB shell mkdir -p /sdcard/KikidameTest/Radio/TBS/Junk /sdcard/KikidameTest/Radio/TBS/Empty /sdcard/KikidameTest/Radio/@eaDir
  $ADB push "$SRC/tagged.m4a" /sdcard/KikidameTest/Radio/TBS/Junk/2026-10-01.m4a
  $ADB push "$SRC/untagged.mp3" /sdcard/KikidameTest/Radio/TBS/Junk/2026-10-08.mp3
  $ADB push "$SRC/tagged.m4a" /sdcard/KikidameTest/Radio/@eaDir/ignored.m4a
  $ADB shell 'echo memo > /sdcard/KikidameTest/Radio/TBS/Junk/notes.txt'
  ```
  OS のフォルダ選択では `KikidameTest/Radio` を選ぶ（`Radio` の下が `<配信元>/<番組>/<各回>`）
- 失敗の原因は logcat に出る。選ぶ画面で失敗したときは `DeviceFolder` タグ、同期・ダウンロードで失敗したときは `SessionSourceGateway` タグ:
  ```bash
  $ADB logcat -d | grep -E "DeviceFolder|SessionSourceGateway|LibraryRefresher|AndroidRuntime" | tail -50
  ```
- 片付け（試験用のフォルダを消す）:
  ```bash
  $ADB shell rm -r /sdcard/KikidameTest
  ```

この節のコマンドは、worker の環境に実機が無いため worker は流していない。PR #214 のマージ前の検証（2026-10-11）で、Supervisor と人がこの節の形で試験用のフォルダを置いて確かめた（[device-checklists.md](device-checklists.md) の「実機チェックリスト（#199）」の結果）。

## 手元のファイルと DB の突き合わせ（#50）

`files/episodes/` にあるのに `local_files` のどの行の `path` でもないファイル（行の無いファイル）は、アプリからは二度と到達できず、容量の表示（#42）にも数えられない。
アプリの削除経路はすべて行のあとにファイルも消し、「取得元を変える」（旧「別のサーバに接続」）も #50 でファイルを消すようになった（再生とダウンロード・同期の Worker を止めてから消す）。
それでも残るのは、**開発手順で DB だけを消した／書き換えたとき**と、消している最中にプロセスが死んだときくらい。
DB を消すときは `files/episodes/` も一緒に消すこと:

```bash
D=/storage/emulated/0/Android/data/$PKG/files/episodes
$ADB shell "rm -rf '$D'"
```

突き合わせ（行の無いファイルと、行はあるのにファイルが無い回を列挙し、前者を `stray.txt` に書く）:

```bash
D=/storage/emulated/0/Android/data/$PKG/files/episodes
for f in kikidame.db kikidame.db-wal; do
  $ADB shell "run-as $PKG sh -c 'cat databases/$f'" > dev-$f < /dev/null
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
