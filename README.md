# Jellyfin Radio

Jellyfin サーバに音楽ライブラリとして取り込まれたラジオ録音を、番組単位のルールで自動ダウンロード／削除し、サーバに到達できなくても聴けるようにする Android アプリ。

## なぜ作ったか

既存の Jellyfin クライアント（公式アプリ・Findroid）は映像中心で、「番組単位で自動的に手元に置く／消す」と「サーバが落ちていても完全に成立する」が弱い。この 2 点だけを目的に、ラジオ録音の聴取に絞って作っている。

- **オフライン優先** — 聴くのは常に手元のファイル。再生位置・再生済みも手元だけで持ち、サーバへは送らない
- **フォルダ同期** — 番組ごとに「最新 N 回まで保持」「再生済みなら削除」を決めると、あとは同期が不足分を落とし余剰分を消す

利用形態は 1 サーバ・1 ユーザー・1 ライブラリ。聴くのはこのアプリだけで、他端末や Web との併用は想定しない。

## できること

- **番組一覧** — 「よく聴く」の印で節を分け、番組名・放送局名の検索と放送局での絞り込み。同期対象か、サーバから消えたか（消失）を行に出す
- **各回一覧** — ダウンロード／固定／再生済みの操作。手元にあるか、ダウンロード中か、失敗したかを行に出す
- **同期** — 番組ごとに同期対象と保持ルールを決める。6 時間ごとに WorkManager が同期し、「Wi-Fi のみ」を選べる。手動でダウンロードした各回は「固定」になり、保持ルールでは消えない
- **再生** — バックグラウンド再生と通知・ロック画面の操作、±30 秒、倍速（1.0〜2.0）、スリープタイマー（時間指定／この回の終わりまで）。再生位置を保存して次回は続きから、末尾近くまで聴くと自動で再生済み、聴き終えると同じ番組の次の回へ
- **ミニプレイヤー**・テーマの 3 択（システム／ダーク／ライト）

用語（番組・各回・放送局・出演者・固定・よく聴く・判断保留 など）は [CONTEXT.md](./CONTEXT.md) で定義している。

## サーバ側の前提

- Jellyfin **12.0** を対象（レガシー認証は使わない）。10.11 系でも動作を確認している
- ラジオ録音は**音楽ライブラリ**として取り込まれていること。アプリはこう読み替える:

  | Jellyfin | アプリ内 |
  | --- | --- |
  | MusicAlbum | 番組 |
  | Audio | 各回 |
  | AlbumArtist | 放送局 |
  | Audio の Artists | 出演者（表示のみ） |
  | PremiereDate（無ければ DateCreated） | 放送日（並び順・「最新 N 回」の基準） |

- 録音ファイルのタグが `album` = 番組、`albumartist` = 放送局、`©day` = 放送日、`©ART` = 出演者 になっていること（Jellyfin がこれで上の構造を組む）。[radirec-tool](https://github.com/t-seki/radirec-tool) の出力がこの形で、同じタグが付いていれば他のツールでもよい
- 端末は Android 12（API 31）以上

## 仕組みの要点

- **サーバが各回の存在の正、手元はキャッシュ。** 同期の削除は、サーバの完全な一覧が取れたときにだけ行う（[ADR 0004](./docs/adr/0004-sync-deletes-only-from-full-listing.md)）。取れなければその番組は判断保留で、何も落とさず何も消さない
- **再生位置と再生済みは手元が正。** サーバへ送らず、サーバの値も使わない（[ADR 0002](./docs/adr/0002-playback-position-local-authority.md)、[ADR 0007](./docs/adr/0007-playback-state-stays-local.md)）
- **アプリ内の同一性はサーバ ID に依存しない。** サーバでライブラリを作り直して ID が変わっても、放送局・番組名・タイトルで突合して結び直し、ファイルも再生位置も残す（[ADR 0001](./docs/adr/0001-local-surrogate-key.md)、[ADR 0005](./docs/adr/0005-rematch-unlinked-rows-before-sync-deletes.md)）
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

全体は [docs/claude-code-handoff.md](./docs/claude-code-handoff.md) の「やらないこと」。

## ドキュメント

- 用語集: [CONTEXT.md](./CONTEXT.md)
- 設計判断の記録: [docs/adr/](./docs/adr/)
- 実装引き継ぎ（設計の全体像・マイルストーン）: [docs/claude-code-handoff.md](./docs/claude-code-handoff.md)
- 開発ガイド（ツールチェイン・テスト・実機確認・並行作業）: [docs/development.md](./docs/development.md)
- UI の方針（大事にすること・色・画面ごとの役割）: [docs/ui.md](./docs/ui.md)
