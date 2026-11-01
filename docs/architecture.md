# Kikidame アーキテクチャ

Kikidame の今の設計を述べる文書。用語は `CONTEXT.md` の定義に従う。覆しにくい判断の理由は `docs/adr/` にある。画面の仕様（文言・シートの中身・行の操作）は [ui.md](ui.md) にあり、この文書は仕組み（型・口・規則）を述べる。
この文書と食い違う点があれば、`CONTEXT.md` と ADR の方を正とし、この文書を直す。

## 目次

構成の話（プロダクト概要・技術スタック・モジュール構成）をこの文書に置き、データの流れ（取得 → 突合 → 同期 → ダウンロード・差し替え → 再生）の順に並べたテーマのファイルに分ける。テーマのファイルを足したら、この目次に足す。

1. [Room スキーマ](architecture/schema.md) — 手元の DB の形
2. [取得](architecture/fetching.md) — 取得元の境界とセッション、Jellyfin、共有フォルダの走査、番組の画像
3. [突合と取り込み](architecture/matching.md)
4. [同期](architecture/sync.md)
5. [ダウンロードと差し替え](architecture/download.md)
6. [再生](architecture/playback.md)
7. [UI の文言（i18n）](architecture/i18n.md)

この文書の下の節: [プロダクト概要](#プロダクト概要)・[技術スタック](#技術スタック)・[モジュール構成](#モジュール構成)・[やらないこと](#やらないこと)・[参照](#参照)

## プロダクト概要

- 録り溜めたラジオ番組（ポッドキャストのファイルも使える。AAC/M4A・MP3）を Jellyfin サーバ・NAS の共有フォルダ・端末のフォルダから取得し、**オフラインで聴く**ための Android アプリ
- コンセプトは 2 つ
  1. **オフライン優先**: サーバに到達できなくてもアプリが完全に成立する
  2. **フォルダ同期**: 番組単位でルールを決めて自動的にダウンロード／削除する
- 既存クライアント（Findroid・公式アプリ）は映像中心で、番組単位の自動同期とオフライン自立が弱い。そこが差別化点なので、この 2 点の品質を最優先する
- 利用形態は **取得元は同時に 1 つ（Jellyfin は 1 サーバ・1 ユーザー・1 ライブラリ、SMB は 1 つの共有、端末のフォルダは 1 つのフォルダ）**。聴くのはこのアプリだけを想定する（他端末・Web との併用は、Jellyfin のブックのライブラリで再生の共有をするときだけ）

## 技術スタック

| 領域 | 採用 |
| --- | --- |
| 言語 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| 再生 | Media3 (ExoPlayer) + MediaSessionService — **再生専用**。DownloadManager は使わない（ADR 0002） |
| API | jellyfin-sdk-kotlin（公式）**v1.9.0 以上**（Jellyfin 12.0 対応） |
| 永続化 | Room |
| バックグラウンド | WorkManager（同期・ダウンロードの Worker） |
| DI | Hilt |
| 日時 | `kotlin.time.Instant`（stdlib）+ kotlinx-datetime（`LocalDate` / `TimeZone`）。時間長は `kotlin.time.Duration` |
| テスト | `:core:domain` は JUnit5、Android モジュールは JUnit4 + Robolectric。Media3 は `SimpleBasePlayer` 継承のフェイク `Player` |

## モジュール構成

```
:core:domain   純粋 Kotlin (JVM)。番組／各回／保持ルール／再生済み等のドメイン型、
               再生済み判定、同期の純粋関数。Android・Room・Media3 に依存しない
:core:data     Android ライブラリ。Room Entity / DAO / TypeConverter / Repository 実装。
               Entity ↔ ドメイン型のマッパー、ticks(100ns) ↔ Duration の変換はここに閉じ込める
:app           Compose UI、MediaSessionService、WorkManager Worker、Hilt エントリ
```

`applicationId` は `dev.tseki.kikidame`、`minSdk` は 31。Room は `exportSchema = true` で、`core/data/schemas/` を git 管理する。
境界を先に置くこと自体が目的で、後からモジュールを割るコストが最も高い。

## やらないこと

- 映像の再生
- トランスコード
- サーバ管理機能
- キャスト（Chromecast）
- 複数サーバ・複数ユーザー・複数ライブラリ
- ブックのライブラリのほか（音楽のライブラリ・共有フォルダ・端末のフォルダ）での、他端末・Web との再生位置の共有（ADR 0010 の決定 1。取得元には書き込まないため。例外はブックのライブラリの UserData だけ。ADR 0007 の決定 6）
- Media3 DownloadManager / SimpleCache によるダウンロード
- `/Sessions/Playing/*` による「再生中」のサーバ通知
- オーディオブック向けの並び（古い章から順に聴くので「最新 N 回」が合わない。番組に「向き」を足す設計が要る。`CONTEXT.md`「番組型の音声」）
- Google Play での配布と課金（ADR 0005）

## 参照

- `CONTEXT.md` — 用語集（番組／各回／公開日／同期対象／保持ルール／固定／判断保留／再生済み）
- [docs/adr/README.md](adr/README.md) — ADR の一覧
- Jellyfin 12 認証仕様: https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f
- jellyfin-sdk-kotlin Releases: https://github.com/jellyfin/jellyfin-sdk-kotlin/releases
- Jellyfin OpenAPI (stable): https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json
