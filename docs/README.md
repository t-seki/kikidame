# 開発者向けの docs

開発者向けの docs は日本語だけです。利用者向けの説明は [README.md](../README.md) と [README.ja.md](../README.ja.md) にあります。

## 読む順

1. [CONTEXT.md](../CONTEXT.md) — 用語集。番組・各回・取得元などの言葉の意味。ほかの docs はこの言葉で書かれているので先に読む
2. [architecture.md](./architecture.md) — 全体の設計の入口（プロダクト概要・技術スタック・モジュール構成・やらないこと）。同期・ダウンロード・再生などの仕組みは、そこから辿る [architecture/](./architecture/) の下のテーマのファイルにある
3. 必要に応じて、次を読む
   - [adr/](./adr/) — 設計の決定とその理由（1 決定 1 ファイル）。一覧は [adr/README.md](./adr/README.md)
   - [development.md](./development.md) — ビルド・テスト・リリースの手順と、docs と ADR の決まり
   - [device-testing.md](./device-testing.md) — 実機（ワイヤレスデバッグ、Android Auto、共有フォルダ）での確かめ方
   - [maintainer.md](./maintainer.md) — メンテナ（作者）の手元の環境（署名鍵とテスト用の音声の置き場、複数の PC で作業するときの注意）。コントリビューターは読まなくてよい
   - [ui.md](./ui.md) — UI の方針（色・文字・画面ごとの役割）
   - [sources.md](./sources.md) — 取得元（Jellyfin・NAS の共有フォルダ・端末のフォルダ）ごとの使える条件と制約、どこまで確かめたかの記録

## 貢献したい人へ

受け付けるものは [CONTRIBUTING.md](../CONTRIBUTING.md) にあります。
