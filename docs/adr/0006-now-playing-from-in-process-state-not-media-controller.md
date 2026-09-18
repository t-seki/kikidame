---
status: accepted
date: 2026-09-18
---
# 聴いている回は MediaController ではなくプロセス内の NowPlaying から読む

一覧の再生中マークとミニプレイヤー（#10）は「聴いている回」を知る必要があるが、Media3 の正規ルートである `MediaController` の購読は採らない。`MediaController` を作ると `PlaybackService` に bind してサービスが起動するため、番組一覧を開くだけで再生サービスが立ち上がり、「何も載っていなければ出さない」判定が起動と競合する。代わりに、`PlaybackService` が `Player.Listener` で書き込むプロセス内シングルトン `NowPlaying`（同期が削除除外に使っている既存のもの）を拡張して UI が読む。再生／一時停止などの**操作**だけは `PlayerConnection` の `MediaController` を使う（ミニプレイヤーが見えている時点でサービスは生きているので、新たな起動にはならない）。

- 前提: アプリは単一プロセスで、Service・Worker・UI が同じ `NowPlaying` インスタンスを見る。マルチプロセス化する場合はこの決定を見直す
- `MediaController` 購読 — Media3 の想定する形で、別プロセスや他アプリのセッションにも使えるが、上記の起動副作用がある。見送り
- Room に「聴いている回」を書く — プロセス死後も残ってしまい「サービスが止まれば消える」という意味と合わない。見送り
