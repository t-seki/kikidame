---
status: accepted
date: 2026-10-10
---
番組と各回を取ってくる先を「**取得元 (Source)**」と呼び、Jellyfin の音楽ライブラリに加えて、利用者が選んだ「**共有フォルダ (Shared Folder)**」（NAS の SMB 共有、端末のフォルダ）を取得元にできるようにする（epic #195）。これまでは Jellyfin からしか取れず、録音を NAS（SMB の共有）に溜めているが Jellyfin は立てていない人には入口が無かった。宣伝の主軸（ラジオ録音、日本語圏）で取りこぼしているのがこの層である。決定は 2026-10-10 の grill による（epic #195 の「成果物に入れる決定」）。

1. **用語**: 取得元は Jellyfin の音楽ライブラリか共有フォルダのどちらか。ADR 0001 の「サーバ ID」は「**取得元 ID (Source Item ID)**」に改める（CONTEXT.md）
2. **取得元は同時に 1 つ**。別の取得元に変えるときは、今の「別のサーバに接続」（#50）と同じく、手元の番組・各回・再生位置・ファイルをすべて消してやり直す。文言は「取得元を変える」に改める。取得元をまたいで再生位置を引き継ぐ仕組み（Jellyfin と共有フォルダの間の突合）は作らない
3. **初回の画面で取得元を選ぶ**: 「Jellyfin に接続」「NAS の共有フォルダ（SMB）」「端末のフォルダ」から 1 つ
4. **つなぎ方**: SMB はアプリに組み込む（smbj）。端末のフォルダは Storage Access Framework（SAF）で選ぶ。NFS と WebDAV は対象外
5. **共有フォルダの構成**: 中はちょうど `<配信元>/<番組>/<各回のファイル>` の 3 段。3 段目にある音声ファイル（拡張子で判定: m4a、mp3、aac、ogg、opus、flac など）だけを各回とし、それ以外（2 段目以下・4 段目以上のファイル、音声でないファイル）は黙って無視する。配信元と番組はフォルダの名前で決め、タグは使わない。名前が `.`・`@`・`#` で始まるフォルダとファイル（NAS や OS が作るもの）は、どの段でも無視する（#197 の追加の決定、PR #202 で人が決めた。決まった名前だけを除く一覧方式は、NAS の種類ごとに漏れが出るので採らない）
6. **取得元には書き込まない**: アプリは取得元を読むだけで、書き込みも削除もしない。保持ルールで消すのは、アプリの領域にコピーしたファイルだけ。SMB でも、Jellyfin と同じくアプリの領域にコピーしてから再生する（端末のフォルダでコピーするかどうかは #199 の grill で決める）
7. **取得元 ID**: Jellyfin では Jellyfin の id。共有フォルダでは共有フォルダの中の相対パス（番組は `<配信元>/<番組>`、各回は `<配信元>/<番組>/<ファイル名>`）。ファイル名を変えると未結合になり、今の突合（ADR 0005。同じ番組内のタイトル → 公開日と尺）で結び直す。番組のフォルダ名を変えると、（配信元, 番組名）で結べないので消失（判断保留）になる
8. **回の情報はタグから読む**: 新しいファイル、またはパス・サイズ・更新日時のどれかが変わったファイルのときだけタグを読み、結果を回の行に持つ。2 回目以降の同期は一覧（名前・サイズ・更新日時）を取るだけにする
9. **タグが無いときの補い方**: タイトルはタグの title、無ければ拡張子を除いたファイル名。公開日はタグの日付（年月日まであるとき）、無ければ取り込み日時で代用する。取り込み日時は、共有フォルダではファイルの更新日時。尺は音声から取り、取れなければ不明（突合の第 2 段の対象外。今と同じ）。出演者はタグの artist、無ければ空
10. **「Wi-Fi のみ」**: 取得元によらず今と同じ条件（Wi-Fi のみなら UNMETERED、そうでなければ CONNECTED。`SyncScheduler`）

理由（epic #195 の背景と「確かめた事実」から。事実はどれも 2026-10-10 の Supervisor の調査）:

- **共有フォルダを足す（全体）**: Jellyfin を立てずに録音を NAS に溜めている人に入口を作るため（上の段落）
- **SMB をアプリに組み込む（4）**: SMB を SAF に出す現役のアプリは、CIFS Documents Provider（最終リリース 2025-05、SMB3 の暗号化に未対応）と RSAF（rclone 経由）くらいしか見つからなかった。smbj（`com.hierynomus:smbj`）は Apache-2.0、0.15.0（2026-08-21）、SMB2/3 対応、BouncyCastle に依存し、Material Files・CIFS Documents Provider・SambaLite が Android で使っている
- **NFS を対象外にする（4）**: NFS を SAF に出すアプリは見つからず、Android に組み込める現役の NFS クライアントのライブラリも見つからなかった（nfs4j はサーバの実装、EMC nfs-client-java は 2022 年で停止）
- **端末のフォルダを SAF で選ぶ（4）**: SAF の永続権限は再起動の後も残り、1 アプリあたり 512 件（上限を超えると古いものから外れる）。対象の移動・削除、アプリのデータ消去で失効する（AOSP `UriGrantsManagerService.java` の `MAX_PERSISTED_URI_GRANTS`、Android Developers の documents-files のページ）
- **ローカルネットワークへの接続**: LAN 内のサーバへの接続に要る `ACCESS_LOCAL_NETWORK`（Android 17+）は、Jellyfin のために宣言済み（`app/src/main/AndroidManifest.xml`）で、実行時の要求も実装済み（`MainActivity.kt`）。SMB の接続がこの権限だけで足りるかは未確認（#198 で確かめる）
- 決定 2・3・5〜10 の理由は、epic #195 に書かれていない（決定だけが記されている）。この ADR でも理由を補わない

この決定の最初の一歩（#196）では、Jellyfin の挙動を変えない方針（#196 のスコープ）で次を行った:

- 同期とダウンロードは、取得元の種類に依存しない境界（`core/data` の `data.source.SourceGateway`）だけを通して取得元に触る。Jellyfin はその実装の 1 つ（`JellyfinSource`）で、認証の情報は実装の側で持つ。接続の手順（サインイン、ライブラリの一覧）は Jellyfin 側（`JellyfinGateway`）に残す
- 名前は破壊的に改め、データは移行で残す（epic #195 の決定 12、人の決定 2026-10-10）。`ServerItemId` / `serverItemId` を `SourceItemId` / `sourceItemId`、`ServerProgram` / `ServerEpisode` / `ServerSnapshot` を `SourceProgram` / `SourceEpisode` / `SourceSnapshot` に改めた。Room は版 7 の自動移行（`@RenameColumn` / `@DeleteColumn`）で、列 `serverItemId` を `sourceItemId`（programs・episodes）、`stationName` を `publisherName`、`airedAt` を `publishedAt` に改め、`@ColumnInfo` で旧い列名を残す形をやめた。ADR 0007 で使わないまま残していた `playback_states.syncedAt` は、この版で削除した（domain の `PlaybackState.syncedAt` も消した）。DB は作り直さない

代替（epic #195 の非目標から）:

- **NFS・WebDAV も取得元にする** — 対象外（上の理由のとおり NFS は組み込める現役のライブラリが見つからなかった。WebDAV を外した理由は epic #195 に書かれていない）
- **取得元を同時に複数持つ、取得元をまたいで再生位置を引き継ぐ** — 作らない（決定 2）
- **取得元への書き込み・削除** — しない（決定 6）

結果:

- ADR 0001・0003・0004 は Jellyfin を前提に書いている（0001 のサーバ ID、0003 の `/Items/{id}/Download` と `Range`、0004 の全走査 `fetchLibrary`）。それぞれの末尾に、この ADR を指す注記を足した。本文は書き換えていない
- 共有フォルダの走査・SMB・SAF は #197・#198・#199 で行う
