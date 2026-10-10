---
status: accepted
date: 2026-10-10
---
Bluetooth の機器がつながったときに、前回の回の続きから**自動で再生しない**。接続をきっかけにした再生も、接続時に一時停止で準備しておくことも作らない（#203 の結論、人の了承 2026-10-10）。朝の操作は「イヤホンをつなぐ → ロック画面の Kikidame のカードの ▶」の 1 回で済む。

事実の出典は #203 のコメント（調査、試作 PR #205 の試験、Poweramp との比較）。試験は人の実機（Pixel 7a、Android 17 = SDK 37）、イヤホン ATH-CKS330XBT。Kikidame は minSdk 31・targetSdk 37。【推測】は確かめていない。

## 理由

1. **Android 17 では、接続をきっかけにしたバックグラウンドの再生がオーディオフォーカスを拒否される**。マニフェストの BroadcastReceiver で `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` を受け、フォアグラウンドサービスを始めるところまでは通る（`Background started FGS: Allowed [... code:BLUETOOTH_BROADCAST ...]`）。しかしその後、`AS.HardeningEnforcer: AudioHardening focus request ... ignored ... level: partial` でフォーカスの要求が拒否される。Media3 の既定（`handleAudioFocus=true`）ではプレイヤーが一時停止のまま鳴らない（試作の 2・3 回目）。1 回目は直前まで画面で使っていたため `level: full`・「would be ignored」（記録だけ）で鳴った。2 回目以降との差が生じる条件は確かめていない【推測: 直前に画面で使っていたか】。根拠: Android 17 の background audio hardening（https://developer.android.com/about/versions/17/changes/bg-audio ）
2. **Poweramp 型（フォーカス無しで鳴らす）は採らない**。Poweramp（targetSdk 36）は同じ受信の仕組みで同じくフォーカスを拒否されるが、フォーカス無しで鳴らし続ける。システムは `background playback would be muted` と記録するだけで、今は消音しない。ただしフォーカス無しだと、ほかのアプリの音（動画、着信音など）と重なっても自動で一時停止・音量下げをしない。将来の更新で消音が実施される恐れもある【推測。いつ・どの条件かは未確認】
3. **イヤホンの ▶ は最後に鳴らしたアプリに届く**。人が Twitch を見た後は `dumpsys media_session` の `Last MediaButtonReceiver` が Twitch に変わり、イヤホンの ▶ で Twitch の続きが流れた。接続時に Kikidame が一時停止で準備しても（試作の「準備だけ」のモード、約 0.2 秒で完了）、▶ の届け先は Kikidame に戻らなかった。原因は最後に実際に鳴らしたアプリで決まるためと読んでいる【推測。`MediaSessionService` のソースでは確かめていない】
4. **既存の #108（`onPlaybackResumption`）で、足りる**。Kikidame のプロセスが無く再生の通知も無い状態でイヤホンをつなぐと、ロック画面のメディアのコントロールに Kikidame の再開のカードが残っていて、その ▶ で保存した位置から流れた（人の報告）。システムはその ▶ を利用者の操作として扱い（`Background started FGS: Allowed [... code:TEMP_ALLOWED_WHILE_IN ...]`）、フォーカスも拒否されなかった（`AudioHardening` の記録なし）。夜にほかのアプリを使っても Kikidame のカードは別に残った

## 代わりの案と、採らなかった理由

- **Poweramp 型（接続を受けてフォーカス無しで再生）** — 理由 2。ほかの音と重なる、将来の消音の恐れ
- **コンパニオンデバイス（CompanionDeviceManager）** — 試していない。イヤホンの Bluetooth アドレスが分からず、Poweramp も使っていない。【推測】AOSP の while-in-use の判定（`ActiveServices.java`）に CDM の条項は無く、音は出ない見込みが高い（未確認）
- **接続時の準備（前回の回を一時停止で用意する）** — 理由 3。イヤホンの ▶ の届け先が戻らず、ロック画面のカードは準備なしでも出る。準備の試作は保存位置ではなく回の冒頭から流れた（試作の不具合の見込み、未確認）
- **専用の通知（つないだら通知を出し、タップで再生）** — 作らない。ロック画面の再開のカード（理由 4）が同じ役目を果たす。タップでフォーカスを取れるか、ロック画面から押せるかは未確認のまま
- **targetSdk を 36 に下げる** — 検討のみ。試験していない

## 利用者への案内

アプリが閉じていても、ロック画面（通知の上）のメディアのコントロールに Kikidame の再開のカードが残り、その ▶ で続きから聴ける。イヤホンや車の ▶ は、ほかのアプリが最後に鳴っていればそちらに届く（Kikidame が最後のときは Kikidame が続きから再開する。#119）。README（英日）の「再生」の項に書く（#207）。

## 結果

- コードは変えない。試作 #204 と draft の PR #205 は閉じる
- 接続時の自動再生・準備に使う受信の仕組み（`BLUETOOTH_CONNECT` の許可など）は足さない
