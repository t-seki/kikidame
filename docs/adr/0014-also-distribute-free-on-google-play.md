---
date: 2026-11-02
---
Google Play でも無料で配る。GitHub Releases・Obtainium での配布（ADR 0005）は続け、課金（有料のアプリ、アプリ内課金）はしない。

## 決定

1. **Google Play でも無料で配る。** 動機は 3 つ。届く範囲（Obtainium や APK の手動インストールを知らない人にも入れてもらう）、信頼（Play にあることで安心して入れてもらう）、開発者の認証の一本化。課金はしない。理由は退けた案の「Play で課金して支援を受ける」
2. **Play Console に個人のアカウントを作る。** 組織のアカウント（D-U-N-S 番号が要る）にする理由が無いため。課金しない個人のアカウントで Play に出るのは、法的な氏名・国・開発者のメール
3. **Play アプリ署名には、今の Release の鍵を持ち込む。** Releases・Obtainium の APK と Play のアプリの署名を揃え、互いに上書きで更新できるようにするため。Google に新しい鍵を作らせると署名が分かれる。上書きで更新できるかは未確認（Play で公開するときに確かめる）
4. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を外す。** 「電池の最適化を外す」の行は、権限の要らない電池の最適化の一覧の画面（`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）を開く形にする。Releases 版も同じにする（flavor は分けない）。Play の方針では、中心の機能が壊れる場合を除いて直接の除外を求められず、認められる例にメディアの再生と同期は入っていないため。flavor を分けても、得られるのは 1 タップだけで、保守が増える
5. **プライバシー ポリシーは、repo の直下の `PRIVACY.md`（英日を 1 つのファイルに並べる）に置き、GitHub 上のその URL を Play に書く。** アプリの設定の画面にも、それを開く行を 1 つ足す。ページを作るほどの中身が無く、履歴が残るため。紹介のページが要るようになったら、GitHub Pages へ移してよい（Play Console の URL は後から差し替えられる）。アプリの中のリンクは、Play が求めるかは未確認だが、足すのは 1 行で、審査で差し戻される余地が減るので足す。GitHub の blob の URL がプライバシー ポリシーとして認められるかも未確認
6. **Play へは、当面は手でアップロードする。** CI（`release.yml`）は AAB も作って Release に添付し、それを Play Console に上げる。リリースの頻度が上がったら、CI から直接上げる形に改めて決める。最初は審査と申告の手作業が多く、自動にする恩恵が薄いため。新しいアプリに AAB が必須かは未確認。Release に `.aab` が並んでも Obtainium が APK を選べるかも未確認
7. **審査は、まず端末の中のフォルダのソースで受ける。** 審査の手順の欄に、音声ファイルを端末のフォルダに置いて取得元に選ぶ手順を書く。落ちたら、インターネットから届くデモの Jellyfin サーバを用意するかを改めて決める。この形で審査が通るかは未確認
8. **テスター（12 人以上、14 日以上続けて）は、Google グループで、今の利用者や宣伝の先から募る。** 足りなければ知り合いで補う。実際に使う人のほうが途中で抜けにくく、グループなら 1 つのリンクで募れて、作者がメール アドレスを預からずに済むため
9. **掲載は日本語（既定）と英語。** 文は `fastlane/metadata/android/ja-JP/` と `en-US/` を元にする。説明文では、対応するサーバとして Jellyfin の名前を出す（検索で見つけてもらうため）。Jellyfin のロゴは使わない（ADR 0005 の「そのほかの決定」）。掲載の文に Jellyfin の名前を出してよいかは未確認
10. **GitHub Releases・Obtainium での配布は続ける。** Play が軌道に乗ったら、Releases をやめるかを改めて決める。続ける手間は今の CI でほとんどかからず、Play を使えない端末や Google のアカウントを持たない人にも届くため。開発者の認証を済ませれば、Releases の APK も今までどおり入れられる（[FAQ](https://developer.android.com/developer-verification/guides/faq) の "As long as your apps are registered, your users' install experience will stay the same"）
11. **開発者の認証は、Android Developer Console ではなく Play Console で行う。** Play Console で Play の外で配るアプリも登録できるため（[Play Console のガイド](https://developer.android.com/developer-verification/guides/google-play-console)）。Android Developer Console と二重に払わずに済むかは、資料に明記が無く未確認

## 退けた案

- **Play で課金して支援を受ける**（Play でクローズドソースの買い切り + 機能を制限した無料のアプリ、またはオープンソース + Play で有料のバイナリ） — 対象となる利用者（Jellyfin を自宅で運用し、番組型の音声を聴く人）の規模では年数万円程度の見込みで、個人アカウントで課金するなら住所の全部が Play に出て、確定申告も要る。クローズドにするなら、LGPL-3.0 の `jellyfin-sdk-kotlin` を自前の HTTP クライアントに差し替える手間も要る。オープンソースで有料のバイナリは、対象の層がビルドできる人で「ビルドすれば無料」と相性が悪い
- **Play に一本化して Releases をやめる** — 今の利用者の更新の経路を切ることになる。開発者の認証を済ませれば Releases の APK も今までどおり入れられるので、やめる理由にならない
- **Play 版だけ電池の権限を外す（flavor を分ける）** — 保守が増えるわりに、得られるのは 1 タップだけ
- **権限を残して審査に出す** — 認められる例にメディアの再生も同期も入っておらず、落ちる見込みが高い
- **プライバシー ポリシーを GitHub Pages に置く** — 今の中身の量には手間が多い。紹介のページが要るようになったら移す
- **最初からデモの Jellyfin サーバを用意する** — 審査のたびにインターネットから届くサーバを用意するのは重荷になる。端末の中のフォルダで通るなら要らない

## 出典

事実の出典は Google の資料（上のリンク）。2026-11-02 に WebFetch の要約を通して読んだもので、ページの最終更新日は確かめていない。
