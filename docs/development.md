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
