---
date: 2026-11-09
---
配布は Google Play だけにする。Play アプリ署名は Google が作る鍵にし、タグを push すると CI が AAB を Play に上げる。

## 決定

1. **動機は 3 つ。** 鍵を失う・漏らす心配を Google に預ける。配布の経路を Play の 1 本にする。タグを打てば Play まで届くようにする。GitHub Releases の APK のダウンロード数は v0.9.0・v0.10.0 とも 1（GitHub の API の `download_count`。インストール数ではない）で、Releases を続ける理由が薄い。クローズド テストのテスターは作者だけ（2026-11-07 に確かめた）なので、署名を替えて更新が切れる Play の利用者はいない
2. **アプリ署名鍵は、Play Console の「Play アプリ署名」の画面の「鍵を変更」で「Google が鍵を生成する」を選んで替える（人が行う）。** 鍵のアップグレード（ローテーション）ではなく丸ごとの入れ替えなので、Android の版によらず新しい鍵になる
   - 順番の制約: [Play アプリ署名のヘルプ](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en)（ページに更新日は無い。2026-11-09 に原文を読んだ）に、"Advanced developers who want to manage their own key can change this default before a release is rolled out in the open testing or production track." とある。オープンテストか製品版にリリースを出した後は、変更ではなく年 1 回の鍵のアップグレードしか無い。なので、オープンテストか製品版にリリースを出す前に替える
3. **アップロード鍵は、今の Release の鍵（alias `kikidame`）をそのまま使う。** CI の secret の値と控えは今のまま使える。アプリ署名鍵が Google の鍵になれば、アップロード鍵と別になる（上のヘルプに "Note: For maximum security, your upload key and app signing key should be different."）。アップロード鍵は失っても、"If you lose your upload key or suspect that it was compromised, you are not locked out of your app." とあり、"Request upload key reset" で戻れる
4. **CI は、署名した AAB を作り、`r0adkll/upload-google-play`（v1.1.5）でクローズド テストのトラックに上げる。** GitHub Release は作らない。Play への upload の入力は `action.yml` に揃っていて、`whatsNewDirectory` のリリースノートと R8 の `mapping.txt` も渡せる。手順の細部は `release.yml` と `docs/development.md` の「リリース」の節
5. **サービス アカウントは人が作り、Play への upload は Workload Identity 連携で認証する（JSON 鍵は置かない）。** 成り代われるのは、GitHub Actions の environment `play` の job だけ。署名の 4 つ（`KEYSTORE_BASE64`・`KEYSTORE_PASSWORD`・`KEY_ALIAS`・`KEY_PASSWORD`）は environment `play` の secret に置く。environment `play` はデプロイのルールで `v*` のタグだけを許すので、署名と upload は `v*` のタグの job だけができる。理由: repository secrets は、どのブランチの workflow でも読める。依存の更新のブランチなどで、タグを打たずにブランチへ push された workflow から、アップロード鍵と Play にリリースできる鍵を抜かれうる。デプロイのルールが見るのは ref だけなので、防げるのはそこまでで、タグを打てるトークンが漏れた場合（細工した workflow のコミットに `v*` のタグを付けて push できる）は防げない。JSON 鍵は漏れると失効させるまで誰でも使えるが、Workload Identity 連携なら長く使える鍵が repo の外にも残らない。GCP の設定は 1 回きりで、同じ provider をほかのアプリの repo でも使い回せる。付ける権限は、このアプリのテスト トラックへのリリースに要るものだけに絞る
6. **既存の Releases の Release は消さずに残す。** 取り消せない削除を避けるため。Obtainium の利用者は v0.10.0 で止まり、README で案内する。Releases の APK は Play の版と署名が違うので、署名の仕組みからは、Play の版で上書きして更新できない（試していない）。一度アンインストールしてから Play で入れる
7. **開発者の認証は、Play アプリ署名の鍵が自動で登録の対象になる。** [FAQ](https://developer.android.com/developer-verification/guides/faq)（2026-11-09 に原文を読んだ）に、"I use Play App Signing, are my apps claimed automatically? Yes. If you use Play App Signing, we have the necessary information to securely identify your ownership. Your eligible apps will be part of the automatic registration process." とある。鍵を替えた後の登録の状態は未確認

## 退けた案

- **鍵のアップグレード** — Android 12 以下では OS が新しい鍵を強制せず、年 1 回の枠も使う。丸ごと替えられる間に替えるほうが単純
- **Google が作る鍵のまま、universal APK を落として Releases を続ける** — 配布の経路を 1 本にする動機に合わない
- **fastlane supply・Gradle Play Publisher** — CI に Ruby が要る（fastlane）。掲載文まで上書きしうる。今の metadata の形を読まない
- **JSON 鍵を secret に置く** — 漏れると失効させるまで誰でも使える
- **署名と upload の secret を repository secrets のままにする** — どのブランチの workflow でも読めて、workflow の書き換えで抜かれうる
- **environment `play` に Required reviewers を付ける** — タグを打つこと自体が人の操作で、承認のクリックを毎回増やす得が薄い

## 出典

事実の出典は Google の資料（上のリンク）と、`r0adkll/upload-google-play` の README・`action.yml`（v1.1.5）と、`google-github-actions/auth` の README。Google の資料は 2026-11-09 に原文を読んだ。
