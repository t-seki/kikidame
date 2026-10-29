---
date: 2026-09-20
---
アプリのライセンスは **MPL-2.0** にする。GPL-3.0 は採らない。

## 理由

- GPL-3.0 は App Store の規約と相容れないとされ（VLC の前例）、外部のコントリビューションを受けた後は iOS のアプリを出せなくなる
- MPL-2.0 はファイル単位のコピーレフトで、アプリはオープンのまま、LGPL-3.0 の `jellyfin-sdk-kotlin`・App Store・有料の Play での配布のどれとも支障がない
- Jellyfin のクライアントでは、Findroid が GPL-3.0 で Android のみ、Finamp が MPL-2.0 で iOS にも出ている

## 退けた案

- **GPL-3.0** — 上のとおり、App Store の規約と相容れないとされ、iOS のアプリを出す道を閉ざす

## 結果

- 共有フォルダの SMB の依存と、画像の読み込みの Coil は、MPL-2.0 と両立するライセンスである（ADR 0007・ADR 0009）
