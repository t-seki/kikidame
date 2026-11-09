---
date: 2026-09-20
---
アプリはオープンソースとして公開し（ライセンスは ADR 0012）、無償で配る（配布の経路は ADR 0015）。IzzyOnDroid と F-Droid 本家には申請しない。

## IzzyOnDroid と F-Droid 本家に申請しない理由

理由は 2 つ。

1. どちらも、生成 AI で書かれたコードを含むアプリを受け付けない方針である（IzzyOnDroid の App Inclusion Policy）。Kikidame のコードは大半を Claude Code で書いており、正直に申告すれば却下の対象になる。虚偽の申告はしない
2. F-Droid 本家はソースからビルドし直して F-Droid の鍵で署名するため、Play の版とは署名が違い、片方からもう片方へ更新できない

## そのほかの決定

- 名前とアプリ ID は Jellyfin のブランディングの方針（第三者のアプリは名前に Jellyfin を使えず、Jelly〜 / 〜fin も勧められない、ロゴは使えない）に従う。これは配布の経路によらず要る
- iOS のアプリを作るときは、`jellyfin-sdk-kotlin` が JVM/Android 専用なので、そこで初めて KMP に対応した HTTP クライアントに置き換える。Android だけのうちは置き換えない

## 退けた案

- **Limited Distribution（無料・身分証不要・20 台まで）で身内に配る** — 公に配らないなら足りる。公開を選んだので採らない
- **IzzyOnDroid と F-Droid 本家で配る** — 上の 2 つの理由で申請しない
