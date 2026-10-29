# 実機で試す手順

実機（debug ビルド）で確かめるときに使う手順。ビルド・テスト・リリースの手順は [development.md](development.md)。

debug ビルドは applicationId が `dev.tseki.kikidame.debug`、アプリ名が「Kikidame (debug)」で、release ビルド（`dev.tseki.kikidame`、Releases の APK）とは別のアプリとして並ぶ。以下の `$ADB` は adb のパス、`$PKG` は debug ビルドの applicationId を指す。

## 接続

### ワイヤレスデバッグでつなぐ（推奨）

LAN 上の端末には TCP で届くので、USB が見えない環境でもつなげる。PC が有線でも、端末と同じルータの下にいればよい。

1. 端末: 開発者向けオプション → ワイヤレスデバッグ ON → 「ペア設定コードによるデバイスのペア設定」
2. PC（初回と、鍵が合わなくなったとき。ダイアログを開いたまま）:
   ```bash
   ADB=~/Android/Sdk/platform-tools/adb
   PKG=dev.tseki.kikidame.debug
   $ADB mdns services                   # _adb-tls-pairing._tcp の行がペア設定の IP:ポート（ダイアログを開いている間だけ出る）
   $ADB pair <ペア設定の IP:ポート> <6 桁コード>
   ```
   release ビルドは debuggable でないので `run-as` が効かない。開発中の確認は debug ビルドで行う
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
   `SSLV3_ALERT_CERTIFICATE_UNKNOWN` なら、端末がこの PC の鍵（`~/.android/adbkey`）を知らない。1 からペアリングし直す（adb デーモンの起動時に鍵が新しく作られた場合にも起きる）。端末の開発者向けオプションの「ADB 認証のタイムアウトを無効にする」が OFF だと、7 日使わなかった PC の承認も切れる
4. インストールは Gradle から。複数に見えるときは `ANDROID_SERIAL` で接続用の名前を指定する:
   ```bash
   export JAVA_HOME=~/.local/jdk/current ANDROID_SERIAL=<接続用の IP:ポート>
   ./gradlew :app:installDebug
   ```

`adb mdns services` は、adb サーバを起動した直後には何も出さず、少し待つと出る場合がある。空なら少し待って打ち直す。

マシンごとに初回は `adb pair` が要る（ペア設定は端末とマシンの組ごと）。`adb mdns services` で端末が見つからない場合がある。そのときは、端末のワイヤレスデバッグ画面に出ている IP:ポートを見て、`adb connect` する。

つないだ後は、同じ端末が「IP:ポート」と「mDNS の名前（`adb-…._adb-tls-connect._tcp`）」の 2 つで `adb devices` に出ることがある。`-s` も `ANDROID_SERIAL` も無いと `more than one device` で失敗するので、`ANDROID_SERIAL` に IP:ポートを入れて export する（手順 4 と同じ）。

```bash
export ANDROID_SERIAL=<IP:ポート>
$ADB devices
$ADB shell getprop ro.product.model
```

### つながらないときの切り分け

- **Android 17 / targetSdk 37 は `ACCESS_LOCAL_NETWORK` が要る**。無いと LAN 内のサーバ宛の TCP が黙って落ち、
  DNS だけ通る（名前解決は成功し、接続がタイムアウトする）。初回起動の権限ダイアログで許可する。
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

### 別の PC でビルドした debug ビルドが入っているとき

debug の APK は PC ごとの `~/.android/debug.keystore` で署名される。別の PC でビルドした debug ビルドが端末に入っていると、この PC のビルドの上書きインストールが `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で失敗する。`uninstall` すれば入れ直せるが、debug ビルドのデータは消える。

## ダウンロード

### 再開（`.part` + `Range`）の確認

LAN では短時間で落ち切るので、ダウンロード中に kill しても再開の経路を踏めない。代わりに「途中まで落ちた状態」を作る:

```bash
$ADB shell am force-stop $PKG
# DB を引き出し、対象の local_files.state を 'DONE' → 'PENDING' に書き換えて戻す（debug ビルドの run-as。「裏の同期をすぐ起こす」と同じ形）
W=$(mktemp -d)   # repo の外に置く
for f in kikidame.db kikidame.db-wal; do $ADB exec-out run-as $PKG cat databases/$f > $W/$f; done
python3 -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); print(c.execute(\"UPDATE local_files SET state='PENDING' WHERE episodeId=? AND state='DONE'\", (sys.argv[2],)).rowcount); c.commit(); c.execute('PRAGMA wal_checkpoint(TRUNCATE)'); c.close()" $W/kikidame.db '<episodeId>'   # 1 なら書き換わった。checkpoint で WAL を本体に書き込む
$ADB shell run-as $PKG rm -f databases/kikidame.db-wal databases/kikidame.db-shm
$ADB exec-in run-as $PKG sh -c 'cat > databases/kikidame.db' < $W/kikidame.db
rm -r $W
$ADB shell "head -c 5000000 '<path>' > '<path>.part' && rm '<path>'"
$ADB logcat -c && <アプリを起動>
$ADB logcat -d | grep JellyfinGateway   # download <id> from 5000000 -> HTTP 206 Content-Range=bytes 5000000-.../...
```

`HTTP 206` と `Content-Range` が出て、ファイルが元のサイズで完成すれば再開できている。200 が出た場合は
サーバが `Range` を無視しており、Worker は `.part` を書き直す（フェイクでテスト済み）。

## 同期

同期の入口は 1 つ（`LibraryRefresher`）。番組一覧を引っ張る＝ライブラリ全体の同期（全走査を伴うので、1 番組の同期より重い）、
各回一覧を引っ張る＝その番組だけの同期（取り込み → この番組だけの `planSync`。全走査を待たずに終わる）。
定期同期は WorkManager の `sync-periodic`（3 時間）、起動時同期は `sync-once`。状態は次で見える:

```bash
$ADB shell dumpsys jobscheduler | grep -A3 "$PKG" | head -40
$ADB logcat -d | grep -E "SyncWorker|LibraryRefresher"   # "sync: programs=N episodes=M new=… enqueued=… deleted=… onHold=…"
```

起動時同期は前回の全走査から 15 分以上あけないと積まれない。前回の全走査は成功（`lastFetchedAt`）と試み（`lastAttemptedAt`。
サーバに届かず失敗した回も含む）の新しい方で見る（外出中に前面に出すたびサーバへ接続し直さないため）。
手動・定期の全走査も試みとして数える。「Wi-Fi のみ」で打ち切った回と 1 番組の同期は数えない。
ログイン直後の初回取得も同期なので、ログインし直しでは試せない。

### 裏の同期をすぐ起こす

確実なのは、セッションの Preferences DataStore から前回の全走査の時刻（`last_fetched_at` / `last_attempted_at`）を消してから開く方法。
次に開いたとき起動時同期が必ず積まれ、何度でも繰り返せる。`run-as` を使うので debug ビルドだけ。
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
- 起動時同期（`sync-once`）は上の 15 分の条件を満たさないとそもそも積まれない

ワイヤレス ADB のまま Wi-Fi を切って外出中を再現すると ADB も切れる。ログは Wi-Fi を戻して接続し直してから
`logcat -d` で後から読む。繋ぎ直すときはポートが変わっていることがあるので `$ADB mdns services` で見直す。

## Android Auto を DHU で確認する

車が無くても、Desktop Head Unit（DHU）が車載機の代わりになる。Android Auto はアプリを**スマホ側で**動かし、車載機には画面と操作だけを投影する。つまり DHU に出るブラウズツリーも、そこから鳴る音も `PlaybackService`（`MediaLibraryService`）と Room の中身であり、再生位置と再生済みは車で聴いてもアプリの各回一覧にそのまま反映される（別デバイスではないので同期は要らない）。

### 準備（1 回だけ）

1. SDK Manager で DHU を入れる（Android Studio の SDK Tools → "Android Auto Desktop Head Unit Emulator" でもよい）:
   ```bash
   sdkmanager --install "extras;google;auto"
   ```
   `~/Android/Sdk/extras/google/auto/desktop-head-unit` に置かれる。Linux 用のバイナリなので `chmod +x` が要ることがある
2. スマホに Play から Android Auto アプリを入れ、開発者モードにする: Android Auto の設定 → 「バージョン」を 10 回タップ → 右上メニュー「開発者向け設定」→ **「提供元不明のアプリ」を ON**（debug ビルドの「Kikidame (debug)」も release ビルドも Play を通っていないので、これが無いと一覧に出ない）→ 同じメニューの「ヘッドユニット サーバーを起動」
3. アプリを実機に入れる:
   ```bash
   ./gradlew :app:installDebug
   ```

### つなぐ

```bash
$ADB forward tcp:5277 tcp:5277
~/Android/Sdk/extras/google/auto/desktop-head-unit
```

DHU の窓が開き、スマホ側で Android Auto が始まる。DHU のメディアタブ（音符のアイコン）に Kikidame が出る（debug ビルドと release ビルドの両方が入っていれば 2 つ並ぶ。確認するのは「Kikidame (debug)」）。出ないときは「提供元不明のアプリ」と、`AndroidManifest.xml` の `<meta-data android:name="com.google.android.gms.car.application">` と `android.media.browse.MediaBrowserService` の action を疑う。DHU 側で `Ctrl+M` の後に `d` でデイ／ナイトが切り替わり、コンソールには `help` でコマンド一覧が出る（`mic play <file>` は使わない: 音声アシスタントは非目標）。

Auto はブラウズツリーを開き直すたびに `onGetChildren` を呼ぶので、ダウンロードや同期で手元の回が増減したら、タブを行き来すれば最新になる（変化の押し通知は今はしない）。

### 落とし穴

- DHU は `libc++1` `libc++abi1` が要る（Ubuntu: `sudo apt install -y libc++1 libc++abi1`）。`ldd ~/Android/Sdk/extras/google/auto/desktop-head-unit | grep 'not found'` が空になれば足りている
- バックグラウンド（端末に繋がっていない実行）から起動すると stdin が EOF になって即終了する（`Version: 2.0-linux` を出して exit 0）。`sleep 1d | ~/Android/Sdk/extras/google/auto/desktop-head-unit` か、FIFO を stdin にする（作業ディレクトリはどこでもよい）:

  ```bash
  mkfifo dhu.in; exec 3<>dhu.in
  ~/Android/Sdk/extras/google/auto/desktop-head-unit <&3 &
  printf 'tap 350 140\n' > dhu.in     # コンソールコマンド（tap x y / dpad / keycode）を外から送れる
  ```

  コンソールの出力（`help` の一覧など）は tty でないと見えない
- 初回はスマホに「この車で使う」許可画面（FRX）が出る。5 秒以内に進めないと `PROJECTION_NOT_STARTED` で切れる（logcat に `CarInfo authorization: UNKNOWN` → `Triggering FRX`）ので、スマホの画面を見ながら繋ぐ
- DHU を途中で落とすとスマホのヘッドユニットサーバーが「Already connected」で固まる（`DeveloperHeadUnitNetworkService` の FATAL）。DHU 側は「Waiting for phone」のまま。Android Auto の ⋮ から「ヘッドユニット サーバーを停止」→「起動」で直る
- portaudio の `Device unavailable` は無視してよい（DHU 側で音は出ないが再生は進む）
- `installDebug` は applicationId が別なので release ビルドに触らない。release ビルドそのものをデータを残したまま手元のビルドで更新するなら、release 署名で上書きする:

  ```bash
  source ~/.android-keys/kikidame.env          # export KIKIDAME_KEYSTORE=… 等を書いた chmod 600 のファイル（git 管理外）
  ./gradlew :app:assembleRelease
  $ADB pull "$($ADB shell pm path dev.tseki.kikidame | cut -d: -f2)" installed.apk
  APKSIGNER=~/Android/Sdk/build-tools/36.0.0/apksigner   # バージョンは ls ~/Android/Sdk/build-tools/ で確かめる
  $APKSIGNER verify --print-certs installed.apk | grep SHA-256                        # 実機の証明書
  $APKSIGNER verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256   # 一致すること
  $ADB install -r app/build/outputs/apk/release/app-release.apk
  ```

  **`.env` を `grep` や `cat` で表示しない**（`grep -c` でも一致行を出す環境がある）。存在確認は `test -r` で

## 共有フォルダ（SMB）

### 準備

- 共有に `<配信元>/<番組>/<各回のファイル>` の 3 段で、m4a と mp3 を置く（タグの付いたものと、タグの無いものの両方）。`@eaDir` や `#recycle` のような名前が `.`・`@`・`#` で始まるフォルダも混ぜておく
- debug ビルドを実機に入れておく（[接続](#接続)）
- 失敗の原因は logcat に出る。接続画面で失敗したときは `SmbConnect` タグ、同期・ダウンロードで共有フォルダに触って失敗したときは `SessionSourceGateway` タグに、原因の連鎖付きで出る（認証の失敗は `SourceException.Unauthorized`、届かないは `Unreachable`）。クラッシュは `AndroidRuntime`:
  ```bash
  $ADB logcat -d | grep -E "SmbConnect|SessionSourceGateway|AndroidRuntime" | tail -50
  ```

## 端末のフォルダ

### 準備

- debug ビルドを実機に入れておく（[接続](#接続)）
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

## 手元のファイルと DB の突き合わせ

`files/episodes/` にあるのに `local_files` のどの行の `path` でもないファイル（行の無いファイル）は、アプリからは二度と到達できず、容量の表示にも数えられない。
アプリの削除経路はすべて行のあとにファイルも消し、「取得元を変える」も手元のファイルを消す（再生とダウンロード・同期の Worker を止めてから消す）。
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

ファイルの無い行は `reconcileMissingFiles` が次の同期で整える。
