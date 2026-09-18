---
status: accepted
date: 2026-09-15
---
Program / Episode / LocalFile / PlaybackState の同一性を Jellyfin の `itemId` に直結させると、サーバ側のライブラリ再取り込み（フォルダ移動・DB 再構築）で `itemId` が変わった瞬間に手元のファイルと再生位置が全て孤児になり、「サーバに到達できなくてもアプリが完全に成立する」というオフライン優先の前提が崩れる。また M1（Jellyfin 未接続）ではそもそも `itemId` を持つ行を作れない。そのためローカルの主キーは代理キー（autoincrement Long）とし、サーバ ID は `serverItemId: String?` の nullable unique index として持つ。
- `itemId` を主キーに維持し、再取り込みは「起きないもの」として運用で回避する — スキーマは単純だが、起きたときの被害（全削除 + 再生位置消失）が大きすぎる
- 代理キー + `serverItemId` nullable unique — 採用
- サーバ ID を持たないローカル行（M1 のシード、再取り込み後に孤児化した行）が正規の状態として存在できる
- 孤児化した行をサーバ側の新しい `itemId` へ突合するルールは M3 で別途決める（本 ADR の範囲外）→ ADR 0005 で決定（突合の対象を「未結合」に広げ、同期の削除判断の前に走らせる）
- 代理キーで行を守っても、同期エンジンが「サーバの一覧に無い = 削除」を無条件に適用すれば同じ結果になる。そのため同期の入力は番組ごとに `Known / Unavailable / Gone` の 3 状態を持ち、`Gone`（番組のサーバ ID がサーバの番組一覧に無い）は判断保留とし、「一覧に無い各回を削除」は `Known` の番組に属する `serverItemId != null` の各回にだけ適用する
