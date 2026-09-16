| 項目 | 版 | 備考 |
| --- | --- | --- |
| JDK | **21** | AGP 9.4 の要件は 17+ だが、Robolectric 4.17 が SDK 37 のサンドボックスに Java 21 を要求する |
| Android SDK | platform `android-37.0`、build-tools `36.0.0`、platform-tools | `sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"` |
| Gradle | wrapper（9.7.1） | `./gradlew` が取得する |
| AGP | 9.4 | built-in Kotlin。`org.jetbrains.kotlin.android` は適用しない |
WSL2 で sudo を使わずに揃える例:
```bash
mkdir -p ~/.local/jdk && cd ~/.local/jdk
curl -sSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
tar xzf jdk.tar.gz && ln -sfn jdk-21* current
export JAVA_HOME=~/.local/jdk/current
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
実機は Windows 側の adb に USB 接続する前提（WSL2 側の `platform-tools` はビルド用）。
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb push "Z:\Radio\J-WAVE" /sdcard/Android/data/dev.tseki.jellyfinradio/files/episodes/
```
アプリを開き、番組一覧右上の「シード」（デバッグビルドのみ）を押す。走査先は
`getExternalFilesDir("episodes")`。`filesDir` に変えないこと — root 無しの `adb push` が通らない。
- [ ] `adb push` した 2 階層のツリーがシードで取り込まれ、番組一覧 → 各回一覧 → 再生画面 と辿れる
- [ ] 各回一覧が放送日の新しい順、同日の 2 本はタイトルの辞書順（`… (1)` が後）
- [ ] タグの `©day` が放送日として読める（タグが無い／読めない場合はファイル名の日付）
- [ ] 再生／一時停止／シーク／±30 秒／前後の回
- [ ] 最後まで再生すると同じ番組の次の回（放送日の古い順）へ自動で進む。再生済みの回も飛ばさない
- [ ] 一時停止 → アプリを kill → 再起動 → 同じ回を開くと保存位置から再開する
- [ ] 再生中に kill しても、戻るのは最大 10 秒
- [ ] 残り 2 分以内まで聴くと自動で再生済みになり、冒頭へシークしても解除されない
- [ ] 再生済みの回を開くと先頭から始まる
- [ ] 手動の再生済み／未再生切替で再生位置が変わらない
- [ ] 通知・ロック画面に 再生／一時停止・30 秒戻る・30 秒進む が出て操作できる
- [ ] Android 13+ で初回起動時に通知権限を求められる。拒否しても再生はできる
- [ ] 画面を閉じても再生が続き、イヤホンを抜くと止まる
