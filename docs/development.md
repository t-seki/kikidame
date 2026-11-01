# 開発ガイド

## 前提

| 項目 | バージョン | 備考 |
| --- | --- | --- |
| JDK | **25** | AGP 9.4 の要件は 17+ だが、Robolectric 4.17 が SDK 37 のサンドボックスに Java 21 以上を要求する。そのうえで CI・release と揃えて 25 を使う |
| Android SDK | platform `android-37.0`、build-tools `36.0.0`、platform-tools | `sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"` |
| Gradle | wrapper（バージョンは `gradle/wrapper/gradle-wrapper.properties`） | `./gradlew` が取得する |
| AGP | 9.4 | built-in Kotlin。`org.jetbrains.kotlin.android` は適用しない |

Linux（WSL2 を含む）で sudo を使わずに揃える例:

```bash
# JDK（Temurin）
mkdir -p ~/.local/jdk && cd ~/.local/jdk
curl -sSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
tar xzf jdk.tar.gz && ln -sfn jdk-25* current
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
./gradlew lintKotlin      # ktlint。未使用 import と連続した空行だけを見る（規則は .editorconfig）。CI と同じ
./gradlew formatKotlin    # 上の 2 つの違反を自動で直す。直したら差分を目で見て、ほかの整形が混ざっていないことを確かめる
./gradlew test            # 全ユニットテスト（エミュレータ不要）。CI と同じ
./gradlew :app:lintDebug  # lint。CI と同じ。MissingTranslation（values/ と values-ja/ の片方だけの追加）はエラー。抑止は app/lint.xml に理由付きで
./gradlew :core:domain:animalsnifferMain  # core/domain（純 JVM、Android Lint の NewApi が効かない）が minSdk 31 に無い API を呼んでいないか。CI と同じ。違反は Android の API シグネチャ（android-api-level-31）に無い参照として出る
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
Entity を変えたらスキーマの版を上げ、書き出された JSON もコミットする。

設定の「オープンソースライセンス」に出す依存の一覧の元データは `app/src/main/res/raw/aboutlibraries.json`。
ビルド時には生成しない（オフラインのビルドでも同じ一覧になる）ので、依存を変えたら次で再生成してコミットする。

```bash
./gradlew :app:exportLibraryDefinitions
```

## docs と ADR の決まり

ADR（`docs/adr/`）を足したら、`docs/adr/README.md` の ADR の一覧にも足す。CI の `test` job の先頭で `scripts/check-adr-index.sh` が、`docs/adr/*.md` のファイル名（`README.md` を除く）が `docs/adr/README.md` に出ているかを確かめ、欠けていれば名前を出して落とす（手元でも repo 直下で流せる）。一覧の文面が正しいかは確かめない。

同じく `test` job で `scripts/check-issue-refs.sh` が、repo が追跡している Markdown の全部（`git ls-files '*.md'`）に、自 repo の issue・PR の番号（`#` と 1〜5 桁の数字）があると落とす。他の repo の参照（`owner/repo` の後ろの番号）と URL のアンカーは通す。コード（`.kt` など）は見ない（色の 16 進と当たるため）。手元でも repo 直下で流せる。落ちる例と通る例は `scripts/test-check-issue-refs.sh` が流す（これも CI で流す）。

同じく `test` job で `scripts/check-doc-script-refs.sh` が、`docs/**/*.md` と `README*.md` のインラインコード（バッククォートに挟まれた範囲を前から順に拾う。span の中にバッククォートがあるとずれることがある）に書かれたシェルスクリプトのファイル名が、repo の中にもリンクの中にも無いと落とす。見るのは空白で区切った語のうち末尾が拡張子 sh のもので、repo 直下、またはその Markdown のあるフォルダからの相対パスにファイルが無く、`[…](…)` の形のリンクの中にも無いものを落とす（`~/…`・`$HOME/…`・glob のようなパスの形でない語も、ファイルが無いとして落とす。コードブロックの中は見ない）。直し方は、repo の中のスクリプトは `scripts/check-adr-index.sh` のようにパス付きで書き、ほかの repo のものは `https://…` のリンクにする。手元でも repo 直下で流せる。落ちる例と通る例は `scripts/test-check-doc-script-refs.sh` が流す（これも CI で流す）。リンク先の URL が実在するかは確かめない。

ADR（`docs/adr/`）は、今の決定を書く文書にする。決定が変わったら、本文をそのまま書き換えて改める。経緯は git の履歴に任せ、末尾の注記・`superseded`・`status` は使わない。決定ごと取り消すときは、その ADR を消すか、別の ADR の「退けた案」に移す。
- frontmatter は `date:` だけにする。`date:` は最初に決めた日のままにし、書き換えても動かさない（最後に書き換えた日は git で分かる）。ほかの ADR から分けて新しく作った ADR の `date:` は、分けた元の ADR の日付にする（番号は足した順の通し番号で、日付の順とずれてよい）
- ADR に書くのは、決定・理由・退けた案・確かめた事実（根拠）だけ。どう作ったか（クラス・口・列・数値の細部）は `docs/architecture/` の該当するテーマのファイルに書き、ADR からは参照する
- 同じ決定は 1 本の ADR にだけ書く。ほかの ADR からは、その ADR を参照する

docs・ADR・README・`CONTEXT.md`・コードのコメントには、自 repo の issue・PR の番号（`#` と数字）を書かない。issue・PR への URL のリンクは書いてよいが、理由を issue の中にしか書かない形にはしない（理由は docs の本文に書く）。docs は今の挙動とその理由を書き、経緯は issue・PR・git に任せるため。Markdown は `scripts/check-issue-refs.sh` が CI で確かめる。

## テスト用 Jellyfin サーバ

対応範囲は Jellyfin **10.10 以上**。Jellyfin のバージョンごとの差（`MediaStream.IsOriginal` を返さない Jellyfin サーバがあり、SDK 1.9 はそれを必須と見なす、等。どのバージョンかは確かめていない）は
本物のサーバに当てないと分からないので、コンテナで立てて `SdkJellyfinGateway` の統合テストを回す。docker と ffmpeg と curl と python3 が要る。

```bash
scripts/jellyfin-testserver.sh up 1010   # Jellyfin 10.10.x → http://localhost:8097
scripts/jellyfin-testserver.sh up 12     # Jellyfin 12.x    → http://localhost:8098
scripts/jellyfin-testserver.sh down      # 両方止めて消す

KIKIDAME_JELLYFIN_URL=http://localhost:8097 ./gradlew :core:data:testDebugUnitTest \
  --tests dev.tseki.kikidame.data.jellyfin.SdkJellyfinGatewayIntegrationTest
```

- `up` は ffmpeg で無音の m4a を作った合成ライブラリ（番組 2 × 各回 3。同じ公開日の 2 本、タイトルが日付でない回、出演者の無い回を含む。番組 A のフォルダにだけ `folder.jpg` を置く）を
  `/media` にマウントし、初期セットアップと音楽ライブラリ `radio` の作成を REST で済ませ、スキャンが終わるまで待つ。
  置き場は `$KIKIDAME_JF_DIR`（既定 `/tmp/kikidame-jf`）。ユーザーは `kikidame` / `kikidame-test`
- ブックのライブラリ `radio-book` も作る。中身は音楽と同じ 6 本と `folder.jpg` に、各回が 1 本だけの番組 C を足したもので、
  別のディレクトリ（`$KIKIDAME_JF_DIR/media-book` を `/media-book` にマウント）に置く。同じパスを 2 つのライブラリに入れると、
  アイテムが使い回されてブックのライブラリでも Audio のまま見えるため。配信元のフォルダ（ニッポン放送）にも `folder.jpg` を置き、
  各回が 1 本だけの番組（各回の親が配信元のフォルダになる）の画像にならないことを見る
- `KIKIDAME_JF_LIBRARY=showcase` を付けて `media` / `up` すると、ストア向けスクリーンショット用の英語の架空ライブラリ
  （Example FM / Example Public Radio の 5 番組、45 回）になる。統合テストの期待値は日本語ライブラリ前提なので、
  そのときは統合テストを回さない
- 統合テストは `KIKIDAME_JELLYFIN_URL` が無ければ `Assume` でスキップするので、CI と普段の `./gradlew test` には出てこない。
  付いているときはキャッシュを使わず毎回走る（`core/data/build.gradle.kts`）
- `JellyfinGateway` の全メソッド（ログイン・ライブラリ一覧・番組と各回の取得・1 番組の各回・番組の存在確認・ダウンロードと `Range`・
  認証切れ・到達不能）を 1 回ずつ呼ぶ。`fetchProgramImage` も呼び、番組 A は `ImageTags.Primary` と JPEG の中身、番組 B は画像なし（null）を見る。新しい Jellyfin が出たら `resolve()` にタグを足して同じテストを当てる
- ブックのライブラリ（`radio-book`）でも、一覧（AudioBook が `Album`・`AlbumArtist` のタグで 3 番組・7 本にまとまること）・1 番組の各回・番組の存在確認・
  番組の画像（番組 A は番組のフォルダの画像、番組 B と番組 C は画像なし）・ダウンロードと `Range` を呼ぶ
- ブックの各回の UserData（再生の共有）: `writePlayState` で位置（尺の中の 7 秒）・再生済み・`LastPlayedDate` を書き、書き込みの応答・`fetchPlayState`（時間の上限なしと上限つき）・
  一覧（`fetchLibrary` の各回の `shared`）のどれでも同じ値で読み戻せること、再生済みにする・未再生に戻すのも同じことを見る。音楽のライブラリの一覧は UserData を載せない（`shared` が null）ことも見る。
  書いた回は最後に位置 0・未再生に書き戻す
- 差し替え（ADR 0011）: 音楽とブックのどちらのライブラリでも、ダウンロードの確かめ（`assertDownloads`）の中で、一覧の各回のファイルの版（`Etag`）が空でないことと、
  `fetchFileSize`（`Range: bytes=0-0` の 206 の `Content-Range` の全長）が落とした本文の長さと同じことを見る。Jellyfin 10.10.7 と 12.0 で通した
- 認証は SDK が付ける `Authorization: MediaBrowser Client="Kikidame", Version="…", DeviceId="…", Device="…"` の 1 形式だけ
  （レガシーの `X-Emby-Authorization` は送らない）
- 実機（Android）からコンテナに繋ぐには、実機から届く HTTPS の URL が要る。アプリは `http://` の URL を受け付けない（`normalizeServerUrl` が `plain http is not supported` で弾く）ので、`adb reverse` で `http://localhost` につなぐ形は使えない。開発機のコンテナのポート（Jellyfin 12 なら `http://localhost:8098`）を、実機から届く HTTPS で公開する（リバースプロキシや、VPN の HTTPS 公開機能など。手順は使う道具による）。アプリの接続画面では、その URL とユーザー `kikidame` / パスワード `kikidame-test` で入る。ただし「取得元を変える」は手元のデータを消すので、本番のサーバに繋いだ実機では試さない

確認した Jellyfin（統合テスト 5 件、番組 2 / 各回 6）:

| Jellyfin | 結果 |
| --- | --- |
| Jellyfin 10.10.7 | 通る |
| Jellyfin 12.0.0 | 通る |

## リリース

この節はメンテナ向け（署名鍵を持ち、リリースを出す人）。コントリビューターは読まなくてよい。

配布は GitHub Releases の署名済み APK（ADR 0005）。`v*` のタグを push すると `.github/workflows/release.yml` が
release APK をビルドし、`kikidame-<version>.apk` と R8 の `mapping-<version>.txt` を Release に添付する。

### 署名鍵

- upload 鍵は repo の外（例: `~/.android-keys/kikidame-upload.jks`、alias `kikidame`）。**失うと以後の更新を配れない**ので、
  keystore ファイルとパスフレーズをパスワードマネージャとオフラインの 2 箇所に控える
- ビルドは環境変数から読む。無ければ署名無しでビルドする（CI の `test`、CI の `release-build`（署名なしで `assembleRelease`）、鍵を持たない人の `assembleRelease` を通すため）

  ```bash
  export KIKIDAME_KEYSTORE=~/.android-keys/kikidame-upload.jks
  export KIKIDAME_KEYSTORE_PASSWORD=…   # 鍵のパスワードが別なら KIKIDAME_KEY_PASSWORD も
  export KIKIDAME_KEY_ALIAS=kikidame
  ./gradlew :app:assembleRelease         # app/build/outputs/apk/release/app-release.apk
  ```

- 手元では上の `export` を `~/.android-keys/kikidame.env`（chmod 600、git 管理外）に書いて `source` する。値を表示するコマンド（`cat` / `grep`）は打たない
- GitHub Actions 用の Secrets: `KEYSTORE_BASE64`（`base64 -w0 kikidame-upload.jks`）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
- 署名を確かめる: `apksigner verify --print-certs app-release.apk`（`build-tools/<バージョン>/apksigner`）。Releases の APK と手元のビルドで証明書の SHA-256 が一致すること

### バージョンを上げて出す

1. `app/build.gradle.kts` の `versionCode`（単調増加の整数。F-Droid はこれを見る）と `versionName`（`X.Y.Z`）を上げる PR をマージする
   - バージョンを上げる PR では、`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` と `ja-JP` の同名のファイルを足す。書き方は既存のファイルに合わせ、前のリリースからマージされた利用者から見える変更を箇条書きにする
   - changelog は 1 ファイル 500 文字までにし、`wc -m` で数える
   - バージョンを上げる PR では、リリースしたバージョンに何が入っているかを述べた記述（例:「まだリリースしたバージョンには入っていない」「debug ビルドだけ」「not in the released builds」「debug build only」）を README（英日）と `docs/` で探し、出すバージョンに合わせて直す。当たった行は人が読み、出すバージョンと食い違う記述だけを直す（この項自身、直った後の文にも当たる）
     ```bash
     grep -rnE "リリースしたバージョン|リリース済み|released build|release build|debug ビルドだけ|debug ビルドのみ|debug build only|debug builds? only" README*.md docs
     ```
2. `git tag vX.Y.Z && git push origin vX.Y.Z`。タグと `versionName` が食い違うと workflow が止まる
3. Release が作られたら、Obtainium で更新が見えることと、実機で `adb install -r` して既存データが残ることを確認する

release ビルドは R8 と resource shrinking を有効にしている（APK は debug より大きく縮む）。
ライブラリの consumer rules で足りていて `app/proguard-rules.pro` は最小限。縮小で壊れたら、`mapping-<version>.txt` でスタックトレースを戻せる。
`res/raw/aboutlibraries.json` はコードから参照しているので shrinker に消されない（消えたら設定のライセンス一覧が空になる）。

## 実機の確認

実機の手順（adb でのワイヤレスデバッグの接続、裏の同期をすぐ起こす、Android Auto（DHU）、共有フォルダ・端末のフォルダの準備、手元のファイルと DB の突き合わせなど）は [device-testing.md](device-testing.md)。
