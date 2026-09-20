# Kikidame

Jellyfin サーバに音楽ライブラリとして取り込まれた**番組型の音声**（ラジオ録音・ポッドキャスト）を、番組単位のルールで自動ダウンロード／削除し、サーバに到達できなくても聴けるようにする Android アプリ。Jellyfin プロジェクトとは無関係の非公式クライアントです。

名前は「聴き溜め」から。録り溜めた回を、時間のあるときに手元で聴く、というアプリの用途そのものです。

## なぜ作ったか

既存の Jellyfin クライアント（公式アプリ・Findroid・Finamp）は映像か音楽が中心で、「番組単位で自動的に手元に置く／消す」と「サーバが落ちていても完全に成立する」が弱い。この 2 点だけを目的に、回が積み上がる音声の聴取に絞って作っている。

- **オフライン優先** — 聴くのは常に手元のファイル。再生位置・再生済みも手元だけで持ち、サーバへは送らない
- **保持ルール** — 番組ごとに「最新 N 回まで保持」「再生済みなら削除」を決めると、あとは同期が不足分を落とし余剰分を消す

利用形態は 1 サーバ・1 ユーザー・1 ライブラリ。聴くのはこのアプリだけで、他端末や Web との併用は想定しない。

## できること

- **番組一覧** — 「よく聴く」の印で節を分け、番組名・配信元名の検索と配信元での絞り込み。同期対象か、サーバから消えたか（消失）を行に出す
- **各回一覧** — ダウンロード／固定／再生済みの操作。手元にあるか、ダウンロード中か、失敗したかを行に出す
- **同期** — 番組ごとに同期対象と保持ルールを決める。6 時間ごとに WorkManager が同期し、「Wi-Fi のみ」を選べる。手動でダウンロードした各回は「固定」になり、保持ルールでは消えない
- **再生** — バックグラウンド再生と通知・ロック画面の操作、±10 秒、倍速（1.0〜2.0）、スリープタイマー（時間指定／この回の終わりまで）。再生位置を保存して次回は続きから、末尾近くまで聴くと自動で再生済み、聴き終えると同じ番組の次の回へ
- **ミニプレイヤー**・テーマの 3 択（システム／ダーク／ライト）
- **英語と日本語の UI** — OS の「アプリの言語」（Android 13 以上）で切り替え。既定は英語。他の言語を足すには `app/src/main/res/values-<lang>/strings.xml` を PR で（[docs/ui.md](./docs/ui.md) の「文言の足し方」）

用語（番組型の音声・番組・各回・配信元・公開日・出演者・固定・よく聴く・判断保留 など）は [CONTEXT.md](./CONTEXT.md) で定義している。

## 入れ方

Google Play では配布しない（[ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md)）。配布経路は 2 つ:

1. **GitHub Releases** の APK — [Releases](https://github.com/t-seki/kikidame/releases) から `kikidame-<版>.apk` を入れる
2. **Obtainium** — 自動更新したい人は [Obtainium](https://github.com/ImranR98/Obtainium) に `https://github.com/t-seki/kikidame` を登録する。新しい Release が出ると通知され、同じ署名の APK なので上書きで更新できる（手元の各回と再生位置は残る）

**IzzyOnDroid と F-Droid 本家には申請しない。** 理由は 2 つ。(1) どちらも生成 AI で書かれたコードを含むアプリを受け付けない方針で（[IzzyOnDroid の App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/): "We are strongly opposed to apps which are fully or in part created by generative AI tools"）、Kikidame のコードは大半を Claude Code で書いている。申請テンプレには AI 使用の申告が必須なので、正直に申告すれば却下対象になる。虚偽の申告はしない。(2) F-Droid 本家はソースからビルドし直して F-Droid の鍵で署名するため、Releases 版とは署名が違い、片方から片方へ更新できない（入れ直すと手元の各回と再生位置が消える）。F-Droid クライアントから入れたい人向けには、審査を通さない自前のリポジトリを検討している（[#123](https://github.com/t-seki/kikidame/issues/123)）。

端末は Android 12（API 31）以上。ストア向けの説明文とスクリーンショットは [fastlane/metadata/android/](./fastlane/metadata/android/) にある（Obtainium は読まないが、自前の F-Droid リポジトリ（#123）の `fdroidserver` がそのまま読む形式なので残している）。

## サーバ側の前提

- Jellyfin **10.10 以上**を対象（10.10.7 / 12.0.0 はコンテナと統合テスト、10.11 は実機で確認。`scripts/jellyfin-testserver.sh` と `docs/development.md` の「テスト用 Jellyfin サーバ」を参照。レガシー認証は使わない）
- 音声は**音楽ライブラリ**として取り込まれていること。アプリはこう読み替える:

  | Jellyfin | アプリ内 |
  | --- | --- |
  | MusicAlbum | 番組 |
  | Audio | 各回 |
  | AlbumArtist | 配信元 |
  | Audio の Artists | 出演者（表示のみ） |
  | PremiereDate（無ければ DateCreated） | 公開日（並び順・「最新 N 回」の基準） |

- つまりファイルのタグが `album` = 番組、`albumartist` = 配信元、日付（M4A の `©day`）= 公開日、`artist` = 出演者 になっていればよい。Jellyfin がこれで上の構造を組む。MP3 の日付タグで公開日が取れるかは未確認
  - ラジオ録音なら [radirec-tool](https://github.com/t-seki/radirec-tool) の出力がこの形（`albumartist` = 放送局、`©day` = 放送日）
  - ポッドキャストなら、フィードから落としたファイルに `album` = 番組名、`albumartist` = 配信者やネットワーク、日付 = 配信日を付けて音楽ライブラリに置く。番組ごとにフォルダを分けると Jellyfin が MusicAlbum にまとめやすい

## 仕組みの要点

- **サーバが各回の存在の正、手元はキャッシュ。** 同期の削除は、サーバの完全な一覧が取れたときにだけ行う（[ADR 0004](./docs/adr/0004-sync-deletes-only-from-full-listing.md)）。取れなければその番組は判断保留で、何も落とさず何も消さない
- **再生位置と再生済みは手元が正。** サーバへ送らず、サーバの値も使わない（[ADR 0002](./docs/adr/0002-playback-position-local-authority.md)、[ADR 0007](./docs/adr/0007-playback-state-stays-local.md)）
- **アプリ内の同一性はサーバ ID に依存しない。** サーバでライブラリを作り直して ID が変わっても、配信元・番組名・タイトルで突合して結び直し、ファイルも再生位置も残す（[ADR 0001](./docs/adr/0001-local-surrogate-key.md)、[ADR 0005](./docs/adr/0005-rematch-unlinked-rows-before-sync-deletes.md)）
- **保持ルールが効くのは同期のときだけ。** 再生済みにした瞬間や設定を変えた瞬間には何も消えない

## ビルドと実行

JDK 21 と Android SDK（platform 37）が要る。リポジトリ直下に `local.properties`（git 管理外）を置く:

```
sdk.dir=/home/<you>/Android/Sdk
```

```bash
export JAVA_HOME=~/.local/jdk/current   # JDK 21
./gradlew test                          # ユニットテスト（エミュレータ不要、CI と同じ）
./gradlew :app:assembleDebug            # APK
./gradlew :app:installDebug             # 接続中の端末へ
```

ツールチェインの揃え方、WSL2 からのワイヤレスデバッグ、実機のチェックリスト、DB とファイルの突き合わせは [docs/development.md](./docs/development.md)。

## モジュール構成

```
:core:domain   純粋 Kotlin。番組／各回／保持ルール／再生済みの型と、同期・再生済み判定の純粋関数
:core:data     Room の Entity / DAO / Repository。Entity ↔ ドメイン型の変換はここに閉じる
:app           Compose UI、Media3 の MediaSessionService、WorkManager の Worker、Hilt
```

スタックは Kotlin / Jetpack Compose (Material 3) / Media3 / Room / WorkManager / Hilt / jellyfin-sdk-kotlin。ダウンロードは Media3 の DownloadManager を使わず、SDK が作った URL とヘッダで OkHttp から直接ファイルへ書く（[ADR 0003](./docs/adr/0003-download-without-media3-downloadmanager.md)）。

## やらないこと

- 映像・トランスコード・キャスト
- 複数サーバ・複数ユーザー・複数ライブラリ
- 再生位置や再生済みのサーバ同期、他端末との共有
- サーバ管理
- オーディオブック（古い章から順に聴くので「最新 N 回まで保持」が合わない。対応するなら番組に「向き」を足す設計が要る）
- Google Play での配布と課金（需要が見えたら Releases は無料のまま Play に有料版を併売する選択肢は残している。[ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md)）

全体は [docs/claude-code-handoff.md](./docs/claude-code-handoff.md) の「やらないこと」。

## ドキュメント

- 用語集: [CONTEXT.md](./CONTEXT.md)
- 設計判断の記録: [docs/adr/](./docs/adr/)
- 実装引き継ぎ（設計の全体像・マイルストーン）: [docs/claude-code-handoff.md](./docs/claude-code-handoff.md)
- 開発ガイド（ツールチェイン・テスト・実機確認・並行作業）: [docs/development.md](./docs/development.md)
- UI の方針（大事にすること・色・画面ごとの役割）: [docs/ui.md](./docs/ui.md)

## ライセンス

[MPL-2.0](./LICENSE)（[ADR 0008](./docs/adr/0008-open-source-distributed-outside-play.md)）。Kikidame は Jellyfin プロジェクトとは無関係の非公式クライアントで、Jellyfin の名前とロゴは Jellyfin プロジェクトのものです。

サーバとの通信には [jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin)（LGPL-3.0）を使っています。同梱している依存ライブラリとそのライセンスの一覧はアプリ内の「設定 > オープンソースライセンス」に出ます。一覧の元データは `app/src/main/res/raw/aboutlibraries.json` で、依存を変えたら `./gradlew :app:exportLibraryDefinitions` で再生成してコミットします（ビルド時には生成しないので、オフラインのビルドでも同じ一覧になります）。
