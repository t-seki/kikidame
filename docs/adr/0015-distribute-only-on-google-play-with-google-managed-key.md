---
date: 2026-11-09
---
配布は Google Play だけにし、Play アプリ署名は Google が作る鍵に替える。タグを push すると CI が AAB を Play に上げる。ADR 0014 の決定 3・6・10 と、ADR 0005 の GitHub Releases・Obtainium での配布を改める。

## 決定

1. **動機は 3 つ。** 鍵を失う・漏らす心配を Google に預ける。配布の経路を Play の 1 本にする。タグを打てば Play まで届くようにする。GitHub Releases の APK のダウンロード数は v0.9.0・v0.10.0 とも 1（GitHub の API の `download_count`。インストール数ではない）で、Releases を続ける理由が薄い。クローズド テストのテスターは作者だけ（2026-11-07 に確かめた）なので、署名を替えて更新が切れる Play の利用者はいない
2. **アプリ署名鍵は、Play Console の「Play アプリ署名」の画面の「鍵を変更」で「Google が鍵を生成する」を選んで替える（人が行う）。** 鍵のアップグレード（ローテーション）ではなく丸ごとの入れ替えなので、Android の版によらず新しい鍵になる
   - 順番の制約: [Play アプリ署名のヘルプ](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en)（ページに更新日は無い。2026-11-09 に原文を読んだ）に、"Advanced developers who want to manage their own key can change this default before a release is rolled out in the open testing or production track." とある。オープンテストか製品版にリリースを出した後は、変更ではなく年 1 回の鍵のアップグレードしか無い。なので、オープンテストか製品版にリリースを出す前に替える。この PR のマージの前に人が替え、その後で最初のタグを打つ
   - 鍵を替えた後、手元の端末に入っているクローズド テストの版を Play の新しい版で上書きできるかは未確認（できなければアンインストールして入れ直す）
3. **アップロード鍵は、今の Release の鍵（alias `kikidame`）をそのまま使う。** CI の secrets（`KEYSTORE_BASE64`・`KEYSTORE_PASSWORD`・`KEY_ALIAS`・`KEY_PASSWORD`）と 2 箇所の控えはそのまま。アプリ署名鍵が Google の鍵になれば、アップロード鍵と別になる（上のヘルプに "Note: For maximum security, your upload key and app signing key should be different."）。アップロード鍵は失っても、"If you lose your upload key or suspect that it was compromised, you are not locked out of your app." とあり、"Request upload key reset" で戻れる
4. **CI（`release.yml`）は、署名した AAB を作って `r0adkll/upload-google-play`（v1.1.5、コミットの SHA で固定）でクローズド テストのトラックに上げる。** GitHub Release は作らず（タグだけ）、release APK のビルドも消す。changelog（`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`）を `whatsnew-<locale>` に写してリリースノートにし、R8 の `mapping.txt` も一緒に上げる。権限は `contents: read`。トラックの API での名前は未確認（クローズド トラックなら `alpha` の見込み）
5. **サービス アカウントは人が作り、JSON 鍵を secret `PLAY_SERVICE_ACCOUNT_JSON` に置く。** Play Console で付ける権限は、このアプリのテスト トラックへのリリースに要るものだけに絞る。Workload Identity 連携は使わない（人の作業が少ないため）
6. **既存の Releases（v0.9.0・v0.10.0）は消さずに残し、本文に「配布は Google Play に移った」と書き足す（人が行う）。** 取り消せない削除を避けるため。Obtainium の利用者は v0.10.0 で止まり、README で案内する。Releases の APK は Play の版と署名が違うので、署名の仕組みからは、Play の版で上書きして更新できない（試していない）。一度アンインストールしてから Play で入れる
7. **開発者の認証は、Play アプリ署名の鍵が自動で登録の対象になる。** [FAQ](https://developer.android.com/developer-verification/guides/faq)（2026-11-09 に原文を読んだ）に、"I use Play App Signing, are my apps claimed automatically? Yes. If you use Play App Signing, we have the necessary information to securely identify your ownership. Your eligible apps will be part of the automatic registration process." とある。鍵を替えた後の登録の状態は未確認（人が Play Console で見る）

## 退けた案

- **鍵のアップグレード** — Android 12 以下では OS が新しい鍵を強制せず、年 1 回の枠も使う。丸ごと替えられる間に替えるほうが単純
- **Google が作る鍵のまま、universal APK を落として Releases を続ける** — 配布の経路を 1 本にする動機に合わない
- **fastlane supply・Gradle Play Publisher** — CI に Ruby が要る（fastlane）。掲載文まで上書きしうる。今の metadata の形を読まない
- **Workload Identity 連携** — GCP の設定が増える

## 出典

事実の出典は Google の資料（上のリンク）と、`r0adkll/upload-google-play` の README・`action.yml`（v1.1.5）。Google の資料は 2026-11-09 に原文を読んだ。
