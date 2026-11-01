# 再生

[アーキテクチャの入口](../architecture.md)の一部。


## 再生の仕組み

- 連続再生は同じ番組内で **公開日の古い順** に次の回へ進む。再生済みの回は飛ばさない。手元に無い回は飛ばす（キューは手元にある回だけ）
- 各回を開いた時点で、その番組の手元にある全各回（古い順）を Media3 のプレイリストとして `MediaSessionService` に積み、選んだ回を開始インデックス・再開位置で `setMediaItems` する。
  `mediaId` = ローカル `episodeId`。Media3 のプレイリストは先頭の回にしか開始位置を持てないので、次／前・自動遷移で別の回に入ったときはサービス側で保存位置（再開位置の規則）へシークする
- 操作は再生／一時停止／シーク／±10 秒スキップ／前後の回へ移動。倍速とスリープタイマーがある。スリープタイマーは「一時停止中は減らず、再開すると続きから」。「この回の終わりまで」は ExoPlayer の `pauseAtEndOfMediaItems` で、回の終わりで止めたら解除する（次の回に持ち越さない）。途中の回で止めたときは `STATE_ENDED` にならないので、`NowPlaying.isEnded` は「回の終わりで止めた」も見る（削除から外さないため）
- **再生位置の保存**: 一時停止・停止・回の切替時（`onMediaItemTransition` の `oldPosition`）と再生中 10 秒ごと。ただし行が無く、位置 5 秒未満・未再生（`PlaybackRules.NOT_STARTED_THRESHOLD`、聴き始めていない）なら書かない。プロセスの強制 kill で最大 10 秒戻るのは許容する（定期保存は壁時計なので、倍速なら音源時間で最大 10 秒 × 速度）
- **MediaSessionService**: 通知・ロック画面操作。Android 13+ の `POST_NOTIFICATIONS` ランタイム権限を要求し、`AndroidManifest` に `foregroundServiceType="mediaPlayback"` と `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 権限を置く（Android 14+ の FGS 制約）

## 聴いている回の読み方

一覧の聴いている回の印とミニプレイヤーは「聴いている回」を知る必要があるが、`MediaController` の購読は採らず、プロセス内のシングルトン `NowPlaying` を読む（ADR 0004）。

- 聴いている回は `PlaybackService` が `Player.Listener` で `@Singleton` の `NowPlaying`（`StateFlow<NowPlayingState?>`、`:app`）に書き、同期の実行側もそれを読む（Worker と Service は同一プロセス。MediaController を Worker から結ばない）
- 再生・一時停止などの**操作**だけは `PlayerConnection` の `MediaController` を使う。`MediaController` はサービスに bind するので、持っている限り `stopSelf()` してもサービスは破棄されない。そのため `PlayerConnection` は利用者を数え、最後の利用者が離れたら controller を解放して bind を外す（再生画面は画面の間だけ、ミニプレイヤーは操作のあいだだけ持つ）。プロセスの寿命で持つと、一時停止してタスクを消しても `onTaskRemoved` → `stopSelf()` が効かず、聴いている回が消えない
- 聴いている回が利用者の操作なしに消えた理由（再生の失敗）もサービスから UI へ渡す（`NowPlaying.messages`）。状態ではなく 1 回きりの通知だが、プロセス内で受け渡す点は同じ
- 一覧とミニプレイヤーの**読み**だけがこの経路。再生画面（`PlayerViewModel`）は再生を始める画面なのでサービスが生きていて当然で、位置の追従も要るため `MediaController` を購読する
- Android Auto は車載機からアプリの外で `MediaLibraryService` に bind するので、UI を開かずにサービスが起動する。プレイヤーが空のあいだ `NowPlaying` は null のままなので、一覧とミニプレイヤーの「何も載っていなければ出さない」判定は変わらない。Auto が bind している間は `onTaskRemoved` → `stopSelf()` が効かないが、車が操作権を持っている状態なので意図どおり
- **再開**: プレイヤーが空のとき（プロセスが死んだ後の通知の ▶、Auto の「最近」、端末の再起動の後）だけ Media3 が `onPlaybackResumption` を呼び、`PlaybackService` は Room の続きから聴く回（`LibraryRepository.getContinueListening`）から番組のキューを復元する。「聴いている回」は、復元した回がプレイヤーに載った時点で立つ（Room に「聴いている回」を書くのではなく、再生位置の記録から組み立て直す）
- Media3 は `MediaButtonReceiver` の Manifest の宣言があるときだけ再開（SystemUI の `isRecent` と `onPlaybackResumption`）を有効にする（`MediaSessionLegacyStub.canResumePlaybackOnStart`）。SystemUI の `isRecent` はアプリの `onGetLibraryRoot` を通らず Media3 が自前のルートを返すので、コールバックの側では制御できない
- 前提: アプリは単一のプロセスで、Service・Worker・UI が同じ `NowPlaying` のインスタンスを見る。マルチプロセスにするときは ADR 0004 を見直す

## 再生位置と再生済み

- 取得元が Jellyfin の**ブックのライブラリ**のときは、**再生の共有**（ADR 0010）。それ以外の取得元では手元だけで持ち、送らない
  - 衝突の規則は ADR 0010 の決定 4。実装は `:core:domain` の `PlaybackSharingRules` で、「最後に見たサーバの値」（位置・再生済み・`LastPlayedDate`）を各回に持つ。
    手元が変わったかは未送信の印で見る。両方が変わっていて、サーバの時刻が無い・同じ時刻なら手元。最後に見た値が無い回は、誰も再生していない値（位置 0・未再生・時刻なし）を見たとみなす
  - 未送信の印は、手元で位置か再生済みが変わると立つ（`RoomPlaybackStateRepository.update`）。突合で取得元 ID が変わった回も立て、前の ID で見た値は忘れる。この列を足した移行の時点で手元にある行も立つ（列の既定が 1）
  - 送る: 一時停止・停止・回の終わり・再生済みの手動切替の後（`PlaybackSharePusher`。未送信の回のうち鳴っている回を除いたもの。再生サービスが動いている間だけ）。
    送る前に 1 回ずつサーバの値を読んで衝突の規則を通し（サーバの方が新しければ送らずに採る）、送信は同期の送信とも直列にする（`PlaybackSharing.sendPending`）。
    読めなかった・送れなかった分は次の同期で送る。
    再生していない間に再生済みを切り替えた分も、再生サービスが動いている間は未送信の印を見て送る。サービスが動いていない間に切り替えた分は、再生を始めてサービスが起動したとき（`PlaybackSharePusher` が動き出す）か次の同期で送る
  - 読む: 同期のたび（取り込みの後、`SyncPlanner` の前。ほかで再生済みになった回も保持ルールの対象）。聴いている回（`NowPlaying.excludedFromSync`）は手元のまま。
    ▶ の直前（`ReadBeforePlayPlayer`。一時停止からの再開を含む）と、次／前・自動遷移で別の回に入ったとき（`ResumeOnTransition`）にも 1 回だけ読み、サーバの値を採るならその位置へ移る。
    ▶ の直前の読み込みは 2 秒（`PlaybackSharingRules.READ_BEFORE_PLAY_TIMEOUT`）で打ち切り、読めなかった・打ち切られたときは手元の位置で鳴らす。待っている間に一時停止が来たら読み込みを捨てて鳴らさない
  - 共有の状態は、最後に共有できた時刻と、それより新しい送信の失敗を設定に出す。通知は出さない（画面は [ui.md](../ui.md)）
- **再生済み**の判定（`:core:domain` の純粋関数）:
  - 再生位置が **末尾から残り 2 分以内**に達したら自動で `played = true`。ただし位置 0 では判定しない
    （尺が 2 分以下の回は「再生が少しでも進んだら再生済み」。閾値を尺でスケールさせない）
  - 一度 true になったら、シークで戻しても自動では false に戻らない
  - 手動で再生済み／未再生を切り替えられる。**手動切替は再生位置を変えない**
- **再開位置**（`:core:domain` の純粋関数）: 再生開始時は「残りが 2 分以下なら先頭から、そうでなければ保存位置から」。再生済みフラグは見ない。最後まで再生し終えた回は位置 = 尺を保存する。
  「再生開始」は各回をタップしたとき（止まっている回を含む）と、次／前・自動遷移で別の回に入ったとき。再生中の回の画面に戻っただけのときは触らない
- 再生済みフラグと再生位置は互いに書き換えない。両方を読んで決めるのは上記の再開位置と「聴き始めていない」だけ

