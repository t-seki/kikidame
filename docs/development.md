# 開発ガイド

## 前提

| 項目 | 版 | 備考 |
| --- | --- | --- |
| JDK | **25** | AGP 9.4 の要件は 17+ だが、Robolectric 4.17 が SDK 37 のサンドボックスに Java 21 以上を要求する。そのうえで CI・release（#179）と揃えて 25 を使う。JDK 25 で `./gradlew test` と lint が通ることは #179 の CI で確かめた |
| Android SDK | platform `android-37.0`、build-tools `36.0.0`、platform-tools | `sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"` |
| Gradle | wrapper（版は `gradle/wrapper/gradle-wrapper.properties`） | `./gradlew` が取得する |
| AGP | 9.4 | built-in Kotlin。`org.jetbrains.kotlin.android` は適用しない |

WSL2 で sudo を使わずに揃える例:

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

この手順は WSL2 を前提に書いてある。素の Linux（devbox）でも、同じ手順で JDK と SDK を入れられ、下の「実機で試す（M1）」のワイヤレスデバッグでつなげた（2026-10-07 に確かめたのは素の Linux の方）。

origin の URL は SSH（`git@github.com:...`）にしておく。HTTPS のままで credential helper が無いと、worker の素の `git push` が `could not read Username` で失敗する。HTTPS で使うなら `gh auth setup-git` で credential helper を入れる。

リポジトリ直下に `local.properties`（git 管理外）を置く:

```
sdk.dir=/home/<you>/Android/Sdk
```

`org.gradle.java.home` はコミットしない（CI と食い違う）。JDK は `JAVA_HOME` で渡す。

エディタで Kotlin LSP（Zed の Kotlin 拡張など）を使うなら、repo の `gradle.properties` ではなく、ユーザー単位の `~/.gradle/gradle.properties` に JDK 25 を書く（こちらはコミットされないので書いてよい）:

```
org.gradle.java.home=/home/<you>/.local/jdk/current
```

この設定はそのユーザーの全 Gradle プロジェクトに効き、`JAVA_HOME` より優先される（コマンドラインで `JAVA_HOME` を付けても、Gradle のデーモンはこの JDK で動く）。kikidame のコマンドラインのビルドも同じ JDK 25 なので影響は無いが、別の JDK を使うプロジェクトがあるなら注意する。

JetBrains の kotlin-lsp は同梱の JBR で Gradle を動かすが、JBR には `jlink` が無い。Android の JDK イメージ変換（`JdkImageTransform`）が `jlink executable .../jbr/bin/jlink does not exist` で落ち、Android のクラスが解決できず Project Diagnostics がエラーだらけになる。書いたら言語サーバーを再起動する。Zed なら取り込みのログは `~/.local/share/zed/logs/server-workspace-*.log` の `intellij/importLog` に出る。同じログの `Failed to call 'onVariants' in 'androidComponents' extension` は kotlin-lsp の Android 対応の限界で、診断には影響しない。

## ビルド・テスト

```bash
./gradlew test            # 全ユニットテスト（エミュレータ不要）。CI と同じ
./gradlew :app:lintDebug  # lint。CI と同じ。MissingTranslation（values/ と values-ja/ の片方だけの追加）はエラー。抑止は app/lint.xml に理由付きで
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

### テスト用 Jellyfin サーバ（#97）

対応範囲は Jellyfin **10.10 以上**。版ごとの差（10.10 は `MediaStream.IsOriginal` を返さず、SDK 1.9 は必須と見なす、等）は
本物のサーバに当てないと分からないので、コンテナで立てて `SdkJellyfinGateway` の統合テストを回す。docker と ffmpeg と curl と python3 が要る。

```bash
scripts/jellyfin-testserver.sh up 1010   # Jellyfin 10.10.x → http://localhost:8097
scripts/jellyfin-testserver.sh up 12     # Jellyfin 12.x    → http://localhost:8098
scripts/jellyfin-testserver.sh down      # 両方止めて消す

KIKIDAME_JELLYFIN_URL=http://localhost:8097 ./gradlew :core:data:testDebugUnitTest \
  --tests dev.tseki.kikidame.data.jellyfin.SdkJellyfinGatewayIntegrationTest
```

- `up` は ffmpeg で無音の m4a を作った合成ライブラリ（番組 2 × 各回 3。同じ公開日の 2 本、タイトルが日付でない回、出演者の無い回を含む）を
  `/media` にマウントし、初期セットアップと音楽ライブラリ `radio` の作成を REST で済ませ、スキャンが終わるまで待つ。
  置き場は `$KIKIDAME_JF_DIR`（既定 `/tmp/kikidame-jf`）。ユーザーは `kikidame` / `kikidame-test`
- `KIKIDAME_JF_LIBRARY=showcase` を付けて `media` / `up` すると、ストア向けスクリーンショット用の英語の架空ライブラリ
  （Example FM / Example Public Radio の 5 番組、45 回。#95）になる。実機のスクリーンショットは #95 の PR のときにこのライブラリで撮った（実機からは下の HTTPS の手順でつなぐ）。統合テストの期待値は日本語ライブラリ前提なので、
  そのときは統合テストを回さない
- 統合テストは `KIKIDAME_JELLYFIN_URL` が無ければ `Assume` でスキップするので、CI と普段の `./gradlew test` には出てこない。
  付いているときはキャッシュを使わず毎回走る（`core/data/build.gradle.kts`）
- `JellyfinGateway` の全メソッド（ログイン・ライブラリ一覧・番組と各回の取得・1 番組の各回・番組の存在確認・ダウンロードと `Range`・
  認証切れ・到達不能）を 1 回ずつ呼ぶ。新しい版が出たら `resolve()` にタグを足して同じテストを当てる
- 認証は SDK が付ける `Authorization: MediaBrowser Client="Kikidame", Version="…", DeviceId="…", Device="…"` の 1 形式だけ
  （レガシーの `X-Emby-Authorization` は送らない。2026-09-21 に記録用サーバで確認）
- 実機（Android）からコンテナに繋ぐには、Tailscale の HTTPS（`tailscale serve`）で出す。アプリは `http://` の URL を受け付けない（`normalizeServerUrl` が `plain http is not supported` で弾く）ので、`adb reverse` で `http://localhost` につなぐ形は使えない。実機は tailnet 経由でつなぐ。
  下の例は Jellyfin 12（`up 12`、`http://localhost:8098`）に向けたもので、撮影（#154）で通った。`<host>.<tailnet>.ts.net` は自分のマシンと tailnet の名前に読み替える
  1. tailnet で HTTPS 証明書を有効にする（1 回だけ）。管理画面の [DNS ページ](https://login.tailscale.com/admin/dns)で MagicDNS を有効にし、
     HTTPS Certificates の Enable HTTPS を押す（[Tailscale の docs「Enabling HTTPS」](https://tailscale.com/docs/how-to/set-up-https-certificates)）。
     有効にする前に `tailscale serve` を打つと「Serve is not enabled on your tailnet」と出て止まり、有効にするための URL が出る
  2. 実機の Tailscale アプリをオンにして、tailnet に入れる
  3. 開発機で、コンテナのポートを HTTPS で出す。sudo なしだと「Access denied: serve config denied」で止まる

     ```bash
     sudo tailscale serve --bg --https=443 http://localhost:8098
     ```

  4. `tailscale serve status` が `https://<host>.<tailnet>.ts.net (tailnet only)` / `|-- / proxy http://localhost:8098` を出し、
     `curl` が 200 を返すことを確かめる

     ```bash
     tailscale serve status
     curl https://<host>.<tailnet>.ts.net/System/Info/Public
     ```

  5. アプリの接続画面で URL `https://<host>.<tailnet>.ts.net`、ユーザー `kikidame` / パスワード `kikidame-test` で入る。
     ただし「取得元を変える」は手元のデータを消すので、本番サーバに繋いだ実機では試さない
  6. 済んだら止める（止めるのにも sudo が要る）

     ```bash
     sudo tailscale serve reset
     ```

確認した版（統合テスト 5 件、番組 2 / 各回 6）:

| 版 | 日付 | 結果 |
| --- | --- | --- |
| 10.10.7 | 2026-09-21 | 通る（`fetchProgram` を `getItems(ids=…)` に変えて通した。#97） |
| 12.0.0 | 2026-09-21 | 通る |

## リリース（#94）

配布は GitHub Releases の署名済み APK（ADR 0008）。`v*` のタグを push すると `.github/workflows/release.yml` が
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
- 署名を確かめる: `apksigner verify --print-certs app-release.apk`（`build-tools/<ver>/apksigner`）。Releases の APK と手元のビルドで証明書の SHA-256 が一致すること

### 版を上げて出す

1. `app/build.gradle.kts` の `versionCode`（単調増加の整数。F-Droid はこれを見る）と `versionName`（`X.Y.Z`）を上げる PR をマージする
2. `git tag vX.Y.Z && git push origin vX.Y.Z`。タグと `versionName` が食い違うと workflow が止まる
3. Release が作られたら、Obtainium で更新が見えることと、実機で `adb install -r` して既存データが残ることを確認する

release ビルドは R8 と resource shrinking を有効にしている（APK は約 8 MB、debug は約 85 MB）。
ライブラリの consumer rules で足りていて `app/proguard-rules.pro` は最小限。縮小で壊れたら、`mapping-<version>.txt` でスタックトレースを戻せる。
`res/raw/aboutlibraries.json` はコードから参照しているので shrinker に消されない（消えたら設定のライセンス一覧が空になる）。

## 並行作業（複数の Claude Code セッション）

方針は `~/.claude/CLAUDE.md` の「Parallel Work」（作業セッションは全部 worktree、main のチェックアウトは統合専用、作業セッションの閉じ方もそこ）。
main のチェックアウトから fork subagent に並列で実装させて回す手順は、共通 skill `/supervise`（dotfiles）にある。skill はこの repo 固有の手順として下の「Supervisor」節を読む。人が立ち上げる別セッションで作業するときも、worktree の準備は同じ節に従う。

- 実機は 1 台。触るのは Supervisor（main のチェックアウトにいるセッション）だけで、作業セッションは実機を触らない（触るならひと言告げる）
- 閉じ方と掃除の手順は、2026-09-20 に main のローカルにマージ済みブランチ 8 本と用済みの worktree 1 つが残っていたのが由来（#84）。今は CLAUDE.md と `/supervise` にある

## Supervisor

`/supervise`（dotfiles の共通 skill）が読む repo 固有の手順。2026-09-20 からこの docs に書いて回していた手順のうち、どの repo でも同じ部分を #128 で skill に移した。

### worktree の準備

- fork の worktree は repo 直下の `.claude/worktrees/`（`.gitignore` 済み）に切られる
- worktree には `local.properties`（git 管理外）を置かない。コピーしない（`cp local.properties <worktree>/` は、2026-10-07 に #157 の worker で auto mode の分類器に止められた。分類器が毎回止めるのか、場面によるのかは未確認（1 回止められただけ））。`local.properties` の中身は `sdk.dir` の 1 行だけなので、代わりに環境変数 `ANDROID_HOME` で SDK を渡す
- Gradle は `ANDROID_HOME=~/Android/Sdk JAVA_HOME=~/.local/jdk/current ./gradlew <タスク>` の形で回す（例: `./gradlew :app:assembleDebug`。#161 の head `59784fc` でこの形のビルドが通ることを確かめた）。`build/` は worktree ごとに別なので初回ビルドが重い。`~/.gradle` のキャッシュは共有されるので依存の再ダウンロードは無い
- Gradle のデーモンも `~/.gradle` 単位で、main と全 worktree が共有している。ほかの worktree でビルドが走っている間は `./gradlew --stop` を打たない（走っているビルドが `Gradle build daemon has been stopped: stop command received` で落ちる。2026-10-08 に Supervisor が検証用の worktree を消す前に打ち、worker の `assembleDebug` が落ちた）。検証用の worktree は `--stop` なしで `git worktree remove` してよい
- 画面（UI）を変える issue は、「触るファイル」と完了条件に `docs/ui.md` と `docs/claude-code-handoff.md` の該当箇所を入れる。入れ忘れると PR のレビューで「docs が古い」と指摘されて直すことになる（過去に #79・#139・#143・#152 で起きた）
- 担当は「同じファイルを触らない」単位で分ける（例: 2026-09-20 は `EpisodeListScreen.kt` 周りの #66→#68→#70 と、テーマ・設定・ミニプレイヤーの #59→#62 に分けて衝突なし）

### マージ前の検証

docs だけの PR は 1（CI）だけ。2〜5 はアプリに変更がある PR で行う。Renovate の PR は、APK に入る Gradle の依存・プラグインの更新なら 2〜5 も行い、GitHub Actions・Gradle wrapper・統合テスト用のコンテナだけの更新なら 1（CI）だけ（「マージの承認」）。両方が 1 つの PR に混ざるとき（preset は minor / patch を 1 つの PR にまとめる）は、実機で見る側に倒す。Renovate の PR には担当の worker がいないので、2 のビルドは Supervisor が検証用の worktree で行う（`/supervise` の手順 7）。

1. CI（`test` job の `./gradlew test` と `./gradlew :app:lintDebug`、`release-build` job の署名なしの `./gradlew :app:assembleRelease` と `aboutlibraries.json` の鮮度の検査）が通っている
   - release ビルド（R8 の minify・resource shrink を含む）は CI の `release-build` が PR ごとに試すので、PR のたびに手元で `assembleRelease` を流して確かめなくてよい。署名と APK の配布はリリース手順（「リリース」節）で行う
   - `release-build` は `exportLibraryDefinitions` を流し、`app/src/main/res/raw/aboutlibraries.json` に差分が出ると落ちる。ログに差分と直し方（`./gradlew :app:exportLibraryDefinitions` を流して `app/src/main/res/raw/aboutlibraries.json` をコミットする）が出る。自動ではコミットしない
   - Renovate の PR でこの検査が落ちたら、Supervisor が worker を起動し、Renovate のブランチに再生成のコミット（例: PR #174 の `9ec415f chore(deps): aboutlibraries.json を再生成する`）を積ませる。`/supervise` の「bot の版更新 PR のブランチには積まない」の例外。skill 側の対応は t-seki/dotfiles#349
2. 担当の fork に `./gradlew :app:assembleDebug` を頼み（「worktree の準備」の形で、`ANDROID_HOME` と `JAVA_HOME` を付けて回す）、APK のフルパス（`<worktree>/app/build/outputs/apk/debug/app-debug.apk`）を報告させる
3. Supervisor が `$ADB install -r <APK>` で実機の **debug 版**に入れる。debug 版は applicationId が `dev.tseki.kikidame.debug`、アプリ名が「Kikidame (debug)」で、普段使いの **release 版**（`dev.tseki.kikidame`、Releases の APK）とは別アプリとして並ぶ。release 版には触らない
4. Room のスキーマを上げる PR も入れてよい（影響は debug 版に閉じる）。その後にスキーマの古いビルドを入れてダウングレードで落ちたら、`$ADB uninstall dev.tseki.kikidame.debug` してから入れ直す（debug 版のログイン・DB・手元のファイルが消える）
5. 人に実機で見てほしい点を PR ごとに示す。再生画面を開く確認は音が出るので避ける（出すなら実機の音量をハードキーで 0 にしてから）

debug 版は初回（とアンインストールの後）にサーバへのログインと同期が要る。以後は `install -r` でデータが残る。

### マージの承認

承認制。

- アプリに変更がある PR: ビルドを実機の debug 版に入れ、人が見てからマージする
- docs だけの PR: これも承認制。実機の確認は無く、人が差分を見て決める
- Renovate の PR（依存の更新）は、APK に入るかどうかで分ける。どちらも承認制で、自動マージはしない
  - 承認の仕方: APK に入る Gradle の依存・プラグイン（`gradle/libs.versions.toml`、`build.gradle.kts` 系。AGP、Kotlin、Media3、Room など）の更新は、アプリに変更がある PR と同じく実機の debug 版で見てから承認する。GitHub Actions・Gradle wrapper・統合テスト用のコンテナだけの更新は、docs だけの PR と同じく CI と差分を見て承認する。両方が 1 つの PR に混ざるとき（preset は minor / patch を 1 つの PR にまとめる）は、実機で見る側に倒す
  - 自動マージを切っている理由: 共通の preset（`local>t-seki/renovate-config`）は minor / patch と pin / pinDigest / digest の更新を自動マージする。この repo の auto-merge は無効（`allow_auto_merge: false`）で、Renovate は「platform の auto-merge が使えないときは Renovate 自身の自動マージに切り替える」（Renovate の docs、configuration-options の `platformAutomerge`）ため、CI が通っただけで実機の確認なしにマージされうる
  - 切っている場所: `renovate.json` の `packageRules`（`matchPackageNames: ["*"]` で `automerge` / `platformAutomerge` を false にして、preset の設定を上書きしている）

### マージ後の確認（デプロイ）

docs だけの PR は `git pull --ff-only` までで、以下は行わない。以下はアプリに変更がある PR をマージしたとき。
Renovate の PR は、Gradle の依存・プラグインの更新ならアプリに変更がある PR 側（`installDebug` で入れ直す）、GitHub Actions・Gradle wrapper・統合テスト用のコンテナだけの更新なら docs だけの PR 側（`git pull --ff-only` まで）。両方が混ざるときはアプリに変更がある PR 側（「マージの承認」）。

- main で `git pull --ff-only` した後、`./gradlew :app:installDebug` で debug 版を main のビルドに入れ直す（接続は下の「実機で試す（M1）」）
- Room のスキーマを上げた PR をマージした後は、それより古い debug ビルドを入れない（入れるなら先にアンインストールする）
- release 版は、Supervisor がマージのたびに触るものではない。更新は「リリース」節の手順（タグ → Releases → Obtainium / `adb install -r`）で行う

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

### 音声ファイルを入れる（M1 当時の手順。シードは M3-c で削除）

M1 ではサーバ無しで試すため、`getExternalFilesDir("episodes")/<配信元>/<番組>/` に `adb push` したファイルを
デバッグビルドの「シード」が走査して取り込んでいた。M2 以降はサーバから落とせるので、この機能は #21 で削除した。
手元のファイルを直接入れたい場合は、番組・各回の行に結び付ける仕組みが無いので別途設計が要る（#21 の非目標）。

### 実機チェックリスト（M1）

以下のチェックリストを通したときは、**確認したサーバの版**（Jellyfin の設定 → ダッシュボード、または `/System/Info/Public` の `Version`）を結果と一緒に issue に書く（#97）。これまでの実機確認は 10.11.x の本番サーバに対するもの。10.10 / 12.0 はコンテナと統合テストで確認している（上の「テスト用 Jellyfin サーバ」）。

2026-09-17 に Pixel 7a（Android 17）で確認済み。`©day` の項目のみ未確認。

- [x] （M1 当時）`adb push` した 2 階層のツリーがシードで取り込まれ、番組一覧 → 各回一覧 → 再生画面 と辿れる
- [x] 各回一覧が公開日の新しい順、同日の 2 本はタイトルの辞書順（`… (1)` / `-2` が後）
- [ ] タグの `©day` が公開日として読める（タグが無い／読めない場合はファイル名の日付）
- [x] 再生／一時停止／シーク／±30 秒／前後の回
- [x] 最後まで再生すると同じ番組の次の回（公開日の古い順）へ自動で進む。再生済みの回も飛ばさない
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
  $ADB shell "run-as $PKG sh -c 'timeout 6 nc -z <host> 443; echo rc=\$?'"   # rc=124 なら落ちている
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
$ADB shell am force-stop $PKG
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
$ADB shell dumpsys jobscheduler | grep -A3 "$PKG" | head -40
$ADB logcat -d | grep -E "SyncWorker|LibraryRefresher"   # "sync: programs=N episodes=M new=… enqueued=… deleted=… onHold=…"
```

起動時同期は前回の全走査から 1 時間以上あけないと積まれない。前回の全走査は成功（`lastFetchedAt`）と試み（`lastAttemptedAt`。
サーバに届かず失敗した回も含む）の新しい方で見る（#135。外出中に前面に出すたびサーバへ接続し直さないため）。
手動・定期の全走査も試みとして数える。「Wi-Fi のみ」で打ち切った回と番組単位の同期・取り込みは数えない。
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
- [ ] サーバを止めて同期すると「取得元に接続できません」で何も消えず何も落ちない（同上。`LibraryRefresherTest` で担保）
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
$ADB shell am force-stop $PKG
$ADB shell "run-as $PKG sh -c 'cat databases/kikidame.db'" > dev.db   # -wal も取り、python の sqlite3 で checkpoint
# programs の 1 行を UPDATE: sourceItemId を偽の UUID に、name に「（旧）」を付ける（同名だと突合で結び直される）
$ADB push dev.db /data/local/tmp/ && $ADB shell "run-as $PKG sh -c 'rm -f databases/kikidame.db-wal databases/kikidame.db-shm; cat /data/local/tmp/dev.db > databases/kikidame.db'"
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

### 実機チェックリスト（#96: Android Auto）

DHU での確認。fork の作業セッションでは行わない。マージ前に fork が作った debug APK を Supervisor が実機の debug 版に入れて行う（「Supervisor」節の「マージ前の検証」）。

- [ ] DHU のメディア一覧に Kikidame (debug) が出る
- [ ] ルートに「続きから」「よく聴く」「番組」のタブが出る（よく聴くの印を全部外すと「よく聴く」が消え、途中の回が無ければ「続きから」が消える）
- [ ] 「続きから」（#108）は途中まで聴いた回が番組をまたいで最近聴いた順に並び、再生済みの回・手元に無い回・数秒しか聴いていない回は出ない
- [ ] 「番組」は手元に回がある番組だけで、最新回の公開日順。番組を開くと手元にある回だけが新しい順に並び、番組名・配信元・公開日が見える
- [ ] 途中まで聴いた回に進捗の印、再生済みの回に完了の印が付く（Auto が `EXTRAS_KEY_COMPLETION_STATUS` を表示に使う。表示は Auto のバージョンに依る）
- [ ] 回をタップすると再生が始まり、保存位置から続く（末尾 2 分以内なら先頭から。アプリと同じ規則）。「キュー」に同じ番組の手元にある回が古い順に全部積まれていて、そこから次の回へ移れる（「次へ」ボタンは出ない: ±10 秒が back / forward のスロットを使う）
- [ ] 車で聴いた分の再生位置・再生済みが、スマホの各回一覧と詳細に出ている
- [ ] Auto の再生画面に「10 秒戻る」「10 秒進む」が出る（Media3 の `setMediaButtonPreferences` の `SLOT_BACK` / `SLOT_FORWARD`。出なければ `SessionCommand` のカスタムコマンドで足す）
- [ ] DHU を閉じてもスマホの通知・ロック画面の操作（再生・一時停止・±10 秒・倍速・スリープタイマー）に退行が無い
- [ ] アプリを開かずに DHU から再生を始めると、その後スマホでアプリを開いたときミニプレイヤーにその回が載る（ADR 0006 の addendum）
- [ ] 再開（#108）: 再生を止めてアプリのプロセスを殺し（`adb shell am force-stop $PKG`）、DHU の Kikidame (debug) の ▶ か「最近」（端末の再起動直後にシステムが出す再開の候補）から、最後に聴いていた回がその番組のキューごと保存位置から始まる（`onPlaybackResumption`）。スマホ側では、プロセスを殺した後に通知シェードに Kikidame (debug) の再開カードが出て、▶ で同じく続きから始まる（`MediaButtonReceiver` の宣言が要る。#119）

2026-09-20 に #112 で DHU で確認した。未確認のまま残っているのは「よく聴くを全部外した場合にタブが消えること」「再生済みの回の印」「ロック画面の操作」と、英語 UI の実機確認、#108 で足した「続きから」タブと「再開」の 2 項目。各回の副題に公開日が出ない件は #113。

## 実機で試す（#198: SMB の共有フォルダ）

SMB の取得元（epic #195 の C、#198）は、worker の環境に NAS も実機も無いため、**実機では何も確かめていない**。単体テストと CI が見ているのは、パスの組み立て、失敗の分類、セッションの型と DataStore の読み書き、取得元の振り分けまで（smbj の通信そのものは見ていない）。マージ前の検証で、人が次を実機と NAS で確かめる。取得元の選択画面の SMB は、#198 では debug 版でだけ出していた。#209 で走査を作り直し、release 版でも出すようにした。確かめるのは、ほかの PR と同じく debug 版で行う（「マージ前の検証」）。

### 準備

- 共有に `<配信元>/<番組>/<各回のファイル>` の 3 段で、m4a と mp3 を置く（タグの付いたものと、タグの無いものの両方）。`@eaDir` や `#recycle` のような名前が `.`・`@`・`#` で始まるフォルダも混ぜておく
- debug 版は `dev.tseki.kikidame.debug`（「マージ前の検証」の手順 3）。`$ADB` と `$PKG` は「実機で試す（M1）」の接続の節のとおり
- 失敗の原因は logcat に出る。接続画面で失敗したときは `SmbConnect` タグ、同期・ダウンロードで共有フォルダに触って失敗したときは `SessionSourceGateway` タグに、原因の連鎖付きで出る（認証の失敗は `ServerException.Unauthorized`、届かないは `Unreachable`）。クラッシュは `AndroidRuntime`:
  ```bash
  $ADB logcat -d | grep -E "SmbConnect|SessionSourceGateway|AndroidRuntime" | tail -50
  ```

### 実機チェックリスト（#198）

未確認（2026-10-10 時点でどれも実機で試していない）。

- [ ] 初回の起動で取得元の選択が出る。「NAS の共有フォルダ（SMB）」→ ホスト（IP）・共有名・ユーザー名・パスワードで接続でき、初回の全走査の間に、タグを読み終えた番組から番組一覧に出る（#209 で、全走査の後にまとめて出す形から改めた。下の「実機チェックリスト（#209）」）
- [ ] **初回の全走査の所要時間と通信量を測る**（番組数・各回数も控える）。時間は「接続」を押してから、番組一覧の「タグを読み込み中」の帯が消えるまでをストップウォッチで測る（#209 から番組は読み終えた順に出るので、番組が出そろうことでは測らない）。通信量は NAS 側のネットワークの統計の前後の差か、端末の設定のアプリ別のデータ使用量で見る（端末側の測り方は未確認）。タグの読み取りは `MediaDataSource` の部分読みなので、各回のファイル全体を転送しないはずだが、実測で確かめる
- [ ] 2 回目以降の全走査（引っ張って更新）は一覧（名前・サイズ・更新日時）だけで、タグを読み直さない。通信量と所要時間が初回よりずっと小さい
- [ ] **更新日時の精度**: SMB（smbj）が返す更新日時が、2 回続けた全走査で同じ値になるか（同じでなければ毎回タグを読み直す）。ファイル名を変えたときに更新日時が保たれるか（保たれない場合、名前を変えた回は未結合になった後でタグを読み直す）
- [ ] **タグの読み取り（`RetrieverTagReader`、`MediaMetadataRetriever`）**: m4a と mp3 で、title・artist・日付・尺が読めるか。日付は `METADATA_KEY_YEAR` / `METADATA_KEY_DATE` にどの形式で入るか、MP4 の日時が 0 のときの `1904-01-01` が公開日にならず更新日時で補われるか。タグの無いファイルが、ファイル名のタイトルと更新日時の公開日で入るか
- [ ] ホストに IP でなく名前（NAS のホスト名、`<名前>.local`）を入れて接続できるか。IP で通って名前で通らなければ名前の解決の問題（`ACCESS_LOCAL_NETWORK` の権限ではない）
- [ ] Android 17 の `ACCESS_LOCAL_NETWORK`（宣言済み、実行時の要求も実装済み）だけで SMB（TCP 445）に接続できるか。権限を拒否した状態では「NAS に接続できません」に権限の案内（`connect_hint_local_network_permission`）が付くか（ADR 0010 の「SMB の接続がこの権限だけで足りるかは未確認」はここで確かめる）
- [ ] ゲスト接続がお使いの NAS で通るか（`AuthenticationContext.guest()` で送る。NAS によっては匿名の接続を受け付けない）
- [ ] 間違ったパスワードは「ユーザー名かパスワードが違うか、この共有に入る権限がありません」。存在しない共有名は「共有フォルダを読めませんでした: share not found」。存在しない共有の中のフォルダは「…shared folder not found」。NAS の電源を切る／別のネットワークでは「NAS に接続できません」
- [ ] NAS の電源を切ったまま同期（引っ張って更新）しても、番組が消失（判断保留）にならず手元の一覧のまま、「取得元に接続できません」が出る
- [ ] NAS 側でパスワードを変えてから同期すると、「取得元の認証が切れました。もう一度接続してください」→ SMB の接続画面（ホスト・共有名・ユーザー名は入力済み、パスワードは空）
- [ ] SMB の各回をダウンロードして再生できる（アプリの領域にコピーしてから再生）。ダウンロードの途中で機内モードにして戻すと、続きから再開する（`.part`）
- [ ] 設定に取得元（共有フォルダ（SMB））・ホスト・共有名・フォルダ・ユーザーが出る。ログアウトすると SMB の接続画面から始まる。「取得元を変える」で手元のデータが全部消え、取得元の選択に戻る
- [ ] ログアウトした後の接続画面（Jellyfin・SMB とも）に「取得元を変える」がある。押して確認のダイアログで進むと、手元の番組・各回・再生位置・音声ファイルが消えて取得元の選択画面に戻る（取得元の選択から来た接続画面には、戻るがあるので置いていない）
- [ ] ゲストで接続してログアウトし、再接続画面でゲストのスイッチが入ったまま（ホスト・共有名・フォルダも入力済み）出る。そのまま「接続」で接続し直せる
- [ ] 各回の詳細の「取得元 ID」が、共有フォルダの中の相対パスになっている
- [ ] **更新の後も Jellyfin の接続が残る**: main の head から作った debug 版（`dev.tseki.kikidame.debug`。release 版は別のアプリなので、これで上書きの更新は確かめられない）で Jellyfin にログインして同期してから、アンインストールせずに今回のビルドを入れる:
  ```bash
  $ADB install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  起動して、ログイン画面や取得元の選択を挟まずに番組一覧が出て、同期とダウンロードが通る（DataStore の Jellyfin のキーを変えていないことの実機での確認。単体テストでは `DataStoreSessionRepositoryTest` が、前のバージョンのキーだけの DataStore を読めることを見ている）
- [ ] **release ビルド（R8）で SMB が動く**: `./gradlew :app:assembleRelease`（署名なし）は CI の `release-build` で通る。ただし R8 が smbj と BouncyCastle の何を消したかは実機で確かめていない。release 版（署名したもの）で、接続・全走査・ダウンロードが通る（`core/data/consumer-rules.pro` は R8 の「Missing class」の警告を抑えるだけで、R8 が smbj の何を消したかは確かめていない。smbj は mbassador の `@Handler` 付きメソッドをリフレクションで呼ぶので、消されていると接続の後始末の通知が働かない可能性がある）。通らなければ `NoClassDefFoundError`・`NoSuchAlgorithmException`（NTLM の MD4 や HMAC-MD5）が logcat に出る。**BouncyCastle まわり（Android 標準の BouncyCastle との衝突）で落ちたら、直し方を決めずに報告する**（Material Files は BouncyCastle を `bcprov-jdk15to18` に差し替えている。差し替えが要る理由は未確認）

### 実機チェックリスト（#209: 全走査の作り直し）

未確認（2026-10-11 時点。#209 の PR のマージ前の検証で、Supervisor と人が行う）。

- [ ] 人の NAS（各回のファイルが約 6,000 本）で、最初の全走査が終わる（途中で切れても、次の同期で続きから読んで終わる）。かかった時間を PR に記録する
- [ ] 番組が、タグを読み終えた順に番組一覧に出る。タグを読んでいる間、番組一覧のタブの下に「タグを読み込み中 1,234 / 6,090」と目盛りのあるバーが出て、終われば消える。2 回目以降で変化の無い同期では出ない
- [ ] 接続が切れたとき（走査の途中で NAS の電源を切る、Wi-Fi を切るなど）、続けて 3 回失敗したら（やり直しは 2 回）「取得元に接続できません」で終わる。取り込み済みの番組は残り、消失（判断保留）にならない。次の同期で、残りの番組だけタグを読む
- [ ] まとめ読み（`BlockReader`）を通しても、タグが今までどおり読める。末尾の近くまで読む m4a（#209 の計測の「パンサー向井 2021-10-11」）と mp3 で、タイトル・日付・尺が入る（計測に使った APK はまとめ読みを入れる前のもので、まとめ読みを通した `MediaMetadataRetriever` は実機で確かめていない）
- [ ] まとめ読み（256 KB）で、1 本あたりのタグの読み取りが #209 のまとめ読みの前の計測（m4a で 1 本 0.9〜1.3 秒。#209 のコメント 6099069211 の表）より短くなるか（未確認。logcat には時間を出していないので、全走査の時間で見る）

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
