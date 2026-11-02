# Privacy Policy / プライバシー ポリシー

English first, 日本語は後半にあります（[日本語](#プライバシー-ポリシー日本語)）。

## Privacy Policy (English)

Kikidame is an open-source, unofficial client for listening to program-style audio (recorded radio and similar) offline. The source code is at <https://github.com/t-seki/kikidame>. This policy describes what the app does with data, based on the app's code and its dependencies.

### Summary

- The developer does not collect, receive, or store any data about you. The app has no server of the developer's, no account system, no advertising, no analytics, and no crash reporting.
- The app talks only to the audio source you choose: your Jellyfin server, your NAS file share (SMB), or a folder on your device.

### What the app stores on your device

- Your connection settings: for Jellyfin, the server URL, user name, and an access token; for an SMB share, the host, share name, folder, user name, and password (or a guest setting); for a device folder, the folder you picked.
- The access token and the SMB password are encrypted with a key held in the Android Keystore. Your Jellyfin password is not stored.
- A local database of programs, episodes, and your playback positions, the downloaded audio files, and program images.
- App settings (for example, Wi-Fi only and theme).
- Signing out removes the credentials. "Change source" removes all of the above local data. Uninstalling the app removes the app's data from the device.
- The app's manifest does not turn off Android's app data backup, so the system's default backup behavior applies to the app's data.

### Network connections

- The app connects only to the Jellyfin server or SMB share you entered. It sends your credentials to that server or share to sign in, and requests the program list, audio files, and images.
- To your Jellyfin server, the app also sends its name and version and the device's model name and an identifier derived from the device, as part of the Jellyfin client sign-in (this is the Jellyfin SDK's default behavior). If your Jellyfin library is a book library, the app writes your playback position and played state back to that server (the "playback sharing" feature). It never writes to an SMB share or a device folder.
- The Jellyfin connection requires `https://`. Whoever runs your server or NAS controls what happens to the data you send there.
- The app has no other network destinations: no analytics, advertising, or crash-reporting services are included.

### Permissions

- Local network, internet: to reach your server or NAS.
- Notifications, foreground service, wake lock: to play audio and to run sync and download in the background.
- Device folder: if you choose a folder on your device, the app keeps read access to that folder only. It never writes to it.

### Google Play

If you install the app through Google Play, Google may process data about the installation under its own terms. That is outside this app and this policy.

### Changes and contact

If this policy changes, the change is recorded in this file's history in the repository. For questions, open an issue at <https://github.com/t-seki/kikidame/issues>.

---

## プライバシー ポリシー（日本語）

Kikidame は、録り溜めたラジオなど番組型の音声をオフラインで聴くための、オープンソースの非公式クライアントです。ソースコードは <https://github.com/t-seki/kikidame> にあります。このポリシーは、アプリのコードと依存しているライブラリを確かめて、アプリがデータをどう扱うかを書いたものです。

### 要点

- 作者は、あなたに関するデータを集めず、受け取らず、保存しません。作者のサーバ、アカウントの仕組み、広告、解析、クラッシュの報告はアプリにありません。
- アプリが通信するのは、あなたが選んだ音声の取得元（あなたの Jellyfin サーバ、NAS の共有フォルダ（SMB））だけです。端末のフォルダを選んだときは、端末の中を読むだけです。

### 端末に保存するもの

- 接続の設定。Jellyfin ではサーバの URL・ユーザー名・アクセス トークン、SMB ではホスト・共有名・フォルダ・ユーザー名・パスワード（またはゲストの設定）、端末のフォルダでは選んだフォルダ。
- アクセス トークンと SMB のパスワードは、Android Keystore の鍵で暗号化して保存します。Jellyfin のパスワードは保存しません。
- 番組・各回・再生位置の手元のデータベース、ダウンロードした音声ファイル、番組の画像。
- アプリの設定（Wi-Fi のみ、テーマなど）。
- ログアウトすると認証の情報が消えます。「取得元を変える」は、上のデータを全部消します。アンインストールすると、端末からアプリのデータが消えます。
- アプリのマニフェストは Android のアプリデータのバックアップを止めていないので、システムの既定のバックアップの扱いがアプリのデータに当てはまります。

### 通信

- アプリは、あなたが入力した Jellyfin サーバまたは SMB の共有にだけ接続します。サインインのために認証の情報をそこへ送り、番組の一覧・音声ファイル・画像を取得します。
- Jellyfin サーバには、Jellyfin のクライアントのサインインの一部として、アプリの名前とバージョン、端末の機種名、端末から作られる識別子も送ります（Jellyfin SDK の既定の動作です）。Jellyfin のライブラリがブックのライブラリのときは、再生位置と再生済みの状態をそのサーバへ書き戻します（「再生の共有」）。SMB の共有や端末のフォルダには書き込みません。
- Jellyfin への接続は `https://` が必須です。サーバや NAS に送ったデータの扱いは、それを運用する人が決めます。
- ほかの通信先はありません。解析・広告・クラッシュ報告のサービスは入っていません。

### 権限

- インターネット・ローカルネットワーク: サーバや NAS に届くため。
- 通知・フォアグラウンド サービス・スリープ防止: 音声の再生と、バックグラウンドの同期・ダウンロードのため。
- 端末のフォルダ: 選んだフォルダだけの読み取りを保持します。書き込みはしません。

### Google Play

Google Play からインストールしたときは、Google がインストールに関するデータを Google の規約で扱うことがあります。これはアプリの外のことで、このポリシーの対象ではありません。

### 変更と連絡先

このポリシーを変えたときは、repo のこのファイルの履歴に残ります。質問は <https://github.com/t-seki/kikidame/issues> の issue へ。
