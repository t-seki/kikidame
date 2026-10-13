# 開発ガイド

## 前提

| 項目 | 版 | 備考 |
| --- | --- | --- |
| JDK | **25** | AGP 9.4 の要件は 17+ だが、Robolectric 4.17 が SDK 37 のサンドボックスに Java 21 以上を要求する。そのうえで CI・release と揃えて 25 を使う（#179） |
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

素の Linux（devbox）でも同じ手順で入れられる（#156）。

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

ADR（`docs/adr/`）を足したら、`docs/claude-code-handoff.md` の「参照」の ADR の一覧にも足す。CI の `test` job の先頭で `scripts/check-adr-index.sh`（#211）が、`docs/adr/*.md` のファイル名が handoff に出ているかを確かめ、欠けていれば名前を出して落とす（手元でも repo 直下で流せる）。一覧の文面が正しいかは確かめない。

既存の ADR を改めるときは、本文を書き換えず、末尾に注記を足す。改名で本文の名前が古くなるときも同じで、本文は当時の記述のまま残す（#196・#268）。すでに本文を直した ADR（0008・0010）は書き直さない（#268）。
- 改名の注記の形: 「改名の注記（#239）: 上の本文の `X` は、その後 `Y` に改めた。本文は当時の記述のまま残している。」の段落で書き、末尾に句点を付ける（例: ADR 0010 の末尾）
- 末尾の注記の形: `- <日付> <出どころ>: …` の箇条書きの 1 項目にし、項目の末尾に句点（。）を付けない。項目の途中の文の区切りには句点を使ってよい（例: ADR 0004 の末尾）（#294）
- 決定そのものや事実が変わるときも、同じ ADR の末尾に日付と出どころ（issue か ADR の番号）を添えた 1 行を足す
- 新しい ADR を足すか、元の ADR の末尾に注記を足すかの境目（#274）: 決定の核（ADR の題が言っていること）が取り消される・別のものに置き換わるときは、新しい ADR を足す。核は残り、細部・前提の事実・名前・結果だけが変わるときは、元の ADR の末尾に注記を足す
- 新しい ADR で改めたとき（例: ADR 0010 が ADR 0001 を改めた）:
  - 新しい ADR の本文に、どの ADR の何を改めるかを書く
  - 元の ADR の本文は書き換えず、末尾に「日付 ADR NNNN: …」の注記を足す
  - 核をまるごと置き換えたときは、元の ADR の frontmatter の `status` を `superseded by ADR-NNNN` に変える。一部だけ改めたとき（ADR 0010 の型）は `accepted` のまま

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

- `up` は ffmpeg で無音の m4a を作った合成ライブラリ（番組 2 × 各回 3。同じ公開日の 2 本、タイトルが日付でない回、出演者の無い回を含む。番組 A のフォルダにだけ `folder.jpg` を置く（#282））を
  `/media` にマウントし、初期セットアップと音楽ライブラリ `radio` の作成を REST で済ませ、スキャンが終わるまで待つ。
  置き場は `$KIKIDAME_JF_DIR`（既定 `/tmp/kikidame-jf`）。ユーザーは `kikidame` / `kikidame-test`
- `KIKIDAME_JF_LIBRARY=showcase` を付けて `media` / `up` すると、ストア向けスクリーンショット用の英語の架空ライブラリ
  （Example FM / Example Public Radio の 5 番組、45 回。#95）になる。統合テストの期待値は日本語ライブラリ前提なので、
  そのときは統合テストを回さない
- 統合テストは `KIKIDAME_JELLYFIN_URL` が無ければ `Assume` でスキップするので、CI と普段の `./gradlew test` には出てこない。
  付いているときはキャッシュを使わず毎回走る（`core/data/build.gradle.kts`）
- `JellyfinGateway` の全メソッド（ログイン・ライブラリ一覧・番組と各回の取得・1 番組の各回・番組の存在確認・ダウンロードと `Range`・
  認証切れ・到達不能）を 1 回ずつ呼ぶ。`fetchProgramImage` も呼び、番組 A は `ImageTags.Primary` と JPEG の中身、番組 B は画像なし（null）を見る。新しい版が出たら `resolve()` にタグを足して同じテストを当てる
- 認証は SDK が付ける `Authorization: MediaBrowser Client="Kikidame", Version="…", DeviceId="…", Device="…"` の 1 形式だけ
  （レガシーの `X-Emby-Authorization` は送らない。2026-09-21 に記録用サーバで確認）
- 実機（Android）からコンテナに繋ぐには、Tailscale の HTTPS（`tailscale serve`）で出す。アプリは `http://` の URL を受け付けない（`normalizeServerUrl` が `plain http is not supported` で弾く）ので、`adb reverse` で `http://localhost` につなぐ形は使えない。実機は tailnet 経由でつなぐ。
  下の例は Jellyfin 12（`up 12`、`http://localhost:8098`）に向けたもの（#168）。`<host>.<tailnet>.ts.net` は自分のマシンと tailnet の名前に読み替える
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
| 10.10.7 | 2026-09-21 | 通る（#97） |
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
   - 版を上げる PR では、リリースした版に何が入っているかを述べた記述（例:「まだリリースした版には入っていない」「debug 版だけ」「not in the released builds」「debug build only」）を README（英日）と `docs/` で探し、出す版に合わせて直す。当たった行は人が読み、出す版と食い違う記述だけを直す（この項自身、経緯の記録、直った後の文にも当たる）（#223）
     ```bash
     grep -rnE "リリースした版|リリース版|released build|release build|debug 版だけ|debug 版のみ|debug build only|debug builds? only" README*.md docs
     ```
2. `git tag vX.Y.Z && git push origin vX.Y.Z`。タグと `versionName` が食い違うと workflow が止まる
3. Release が作られたら、Obtainium で更新が見えることと、実機で `adb install -r` して既存データが残ることを確認する

release ビルドは R8 と resource shrinking を有効にしている（APK は約 8 MB、debug は約 85 MB）。
ライブラリの consumer rules で足りていて `app/proguard-rules.pro` は最小限。縮小で壊れたら、`mapping-<version>.txt` でスタックトレースを戻せる。
`res/raw/aboutlibraries.json` はコードから参照しているので shrinker に消されない（消えたら設定のライセンス一覧が空になる）。

## 並行作業（複数の Claude Code セッション）

方針は `~/.claude/CLAUDE.md` の「Parallel Work」（作業セッションは全部 worktree、main のチェックアウトは統合専用、作業セッションの閉じ方もそこ）。
main のチェックアウトから fork subagent に並列で実装させて回す手順は、共通 skill `/supervise`（dotfiles）にある。skill はこの repo 固有の手順として下の「Supervisor」節を読む。人が立ち上げる別セッションで作業するときも、worktree の準備は同じ節に従う。

実機は 1 台。触るのは Supervisor（main のチェックアウトにいるセッション）だけで、作業セッションは実機を触らない（触るならひと言告げる）。

## Supervisor

`/supervise`（dotfiles の共通 skill）が読む repo 固有の手順（どの repo でも同じ部分は #128 で skill に移した）。

### worktree の準備

- fork の worktree は repo 直下の `.claude/worktrees/`（`.gitignore` 済み）に切られる
- worktree には `local.properties`（git 管理外）を置かない。コピーしない（auto mode の分類器に止められる。#163）。`local.properties` の中身は `sdk.dir` の 1 行だけなので、代わりに環境変数 `ANDROID_HOME` で SDK を渡す
- Gradle は `ANDROID_HOME=~/Android/Sdk JAVA_HOME=~/.local/jdk/current ./gradlew <タスク>` の形で回す（例: `./gradlew :app:assembleDebug`。#163）。`build/` は worktree ごとに別なので初回ビルドが重い。`~/.gradle` のキャッシュは共有されるので依存の再ダウンロードは無い
- Gradle のデーモンも `~/.gradle` 単位で、main と全 worktree が共有している。ほかの worktree でビルドが走っている間は `./gradlew --stop` を打たない（走っているビルドが `Gradle build daemon has been stopped: stop command received` で落ちる。#188）。検証用の worktree は `--stop` なしで `git worktree remove` してよい
- issue の「触るファイル」と完了条件に、次を入れる（入れ忘れると PR のレビューで指摘されて直すことになる）
  - 画面（UI）を変える issue: `docs/ui.md` と `docs/claude-code-handoff.md` の該当箇所（#156）
  - CI（`.github/workflows/ci.yml` の job・ステップ）を変える issue: 「マージ前の検証」の 1 の CI の列挙（#219）
  - 挙動を変える issue: `docs/device-checklists.md` の実機チェックリストの項目（未確認の `- [ ]`）（#294）
  - 用語の意味や利用者向けの説明が変わる issue: `CONTEXT.md` と README（`README.md` と `README.ja.md` の両方）（#294）
- 挙動を変える issue で、既存のチェック済みの項目（`[x]`）が今の挙動と合わなくなるときは、記録の文は書き換えず、項目の末尾に当時の確認と分かる印（例: 「（当時の確認。#281 でボタンをなくした）」）を足す。これも「触るファイル」と完了条件に入れる（#297）
- チェックリストの最後の未確認の項目（`- [ ]`）を `[x]` にした（または見送りの印を足した）PR は、同じ PR でそのチェックリストを `docs/device-checklists.md` から `docs/device-checklists-archive.md` へ、文を変えずに移す（前置きの、確認した日・端末・サーバの版も一緒に移す）。実機では確かめないと決めた項目は、箱は `[ ]` のまま変えず、末尾に「（実機では見送り: <理由・担保しているテスト>）」の印を足す。この印の付いた `[ ]` は済んだと数える（#305）
- 担当は「同じファイルを触らない」単位で分ける（#81）

### マージ前の検証

docs だけの PR は 1（CI）だけ。2〜5 はアプリに変更がある PR で行う。Renovate の PR をどちらに数えるかは「マージの承認」に従う。Renovate の PR には担当の worker がいないので、2 のビルドは Supervisor が検証用の worktree で行う（`/supervise` の手順 7）。

1. CI（`test` job の ADR の一覧の検査（`scripts/check-adr-index.sh`）と `./gradlew lintKotlin`（ktlint。未使用 import と連続した空行）と `./gradlew test` と `./gradlew :app:lintDebug` と `./gradlew :core:domain:animalsnifferMain`、`release-build` job の署名なしの `./gradlew :app:assembleRelease` と `aboutlibraries.json` の鮮度の検査）が通っている
   - release ビルド（R8 の minify・resource shrink を含む）は CI の `release-build` が PR ごとに試すので、PR のたびに手元で `assembleRelease` を流して確かめなくてよい。署名と APK の配布はリリース手順（「リリース」節）で行う
   - `release-build` は `exportLibraryDefinitions` を流し、`app/src/main/res/raw/aboutlibraries.json` に差分が出ると落ちる。ログに差分と直し方（`./gradlew :app:exportLibraryDefinitions` を流して `app/src/main/res/raw/aboutlibraries.json` をコミットする）が出る。自動ではコミットしない
   - Renovate の PR でこの検査が落ちたら、Supervisor が worker を起動し、Renovate のブランチに再生成のコミットを積ませる。`/supervise` の「bot の版更新 PR のブランチには積まない」の例外（#187・t-seki/dotfiles#349）
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
Renovate の PR をどちらに数えるかは「マージの承認」に従う。

- main で `git pull --ff-only` した後、`./gradlew :app:installDebug` で debug 版を main のビルドに入れ直す（接続は [device-testing.md](device-testing.md) の「実機で試す（M1）」）
- Room のスキーマを上げた PR をマージした後は、それより古い debug ビルドを入れない（入れるなら先にアンインストールする）
- release 版は、Supervisor がマージのたびに触るものではない。更新は「リリース」節の手順（タグ → Releases → Obtainium / `adb install -r`）で行う

### 大きさの基準

`257 行・25347 バイト（d8886a9）`

この development.md の大きさの基準（測り方と値の形は [REPO-SECTION.md の「大きさの基準」](https://github.com/t-seki/dotfiles/blob/main/claude/.claude/skills/supervise/REPO-SECTION.md#大きさの基準)）。

## 実機の確認

実機の手順とチェックリストは、#305 で別のファイルに分けた。

- 手順（WSL2 の adb の接続、裏の同期をすぐ起こす、Android Auto（DHU）、共有フォルダ・端末のフォルダの準備、手元のファイルと DB の突き合わせなど）: [device-testing.md](device-testing.md)
- 未確認の項目が残るチェックリスト: [device-checklists.md](device-checklists.md)
- 済んだチェックリスト（M1・M2・M3-a・M3-b・M3-c）: [device-checklists-archive.md](device-checklists-archive.md)
