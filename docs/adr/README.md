# ADR の一覧

設計の決定とその理由（1 決定 1 ファイル）。ADR を足したら、ここに 1 行で足す。

- [0001-local-surrogate-key.md](0001-local-surrogate-key.md) — 主キーは取得元 ID ではなく代理キー。未結合の行は、同期の削除判断の前に突合で結び直す
- [0002-download-without-media3-downloadmanager.md](0002-download-without-media3-downloadmanager.md) — DownloadManager を使わない
- [0003-sync-deletes-only-from-full-listing.md](0003-sync-deletes-only-from-full-listing.md) — 同期の削除の権限は完全な一覧（全走査、1 番組の同期ではその番組の一覧）だけ。共有フォルダの全走査は番組ごとに取り込む
- [0004-now-playing-from-in-process-state-not-media-controller.md](0004-now-playing-from-in-process-state-not-media-controller.md) — 聴いている回はプロセス内の状態から取る
- [0005-open-source-distributed-outside-play.md](0005-open-source-distributed-outside-play.md) — オープンソースとして Play を通さず、GitHub Releases と Obtainium で配布する
- [0006-ui-text-resolved-only-in-compose.md](0006-ui-text-resolved-only-in-compose.md) — UI の文言は strings.xml、文字列に解決するのは Compose だけ（`UiText`）。既定は英語
- [0007-add-file-shares-as-sources.md](0007-add-file-shares-as-sources.md) — 共有フォルダ（NAS の共有フォルダ（SMB）・端末のフォルダ）を取得元に加える。取得元は同時に 1 つで、取得元には書き込まない（例外はブックのライブラリの再生の共有）
- [0008-no-autoplay-on-bluetooth-connect.md](0008-no-autoplay-on-bluetooth-connect.md) — Bluetooth の接続では自動で再生しない（ロック画面の再開のカードの ▶ で足りる）
- [0009-show-images-only-from-source-data.md](0009-show-images-only-from-source-data.md) — 画像はデータにあるときだけ出す。番組の画像は同期で取って手元に持ち、回の画像は再生のときに手元のファイルから読む
- [0010-share-playback-state-via-jellyfin-book-library.md](0010-share-playback-state-via-jellyfin-book-library.md) — ブックのライブラリでは、再生位置と再生済みを UserData を通してほかの端末や Jellyfin Web と分け合う（再生の共有）。ほかの取得元では手元だけで持ち、送らない
- [0011-replace-local-files-when-the-source-version-changes.md](0011-replace-local-files-when-the-source-version-changes.md) — 手元のファイルは、取得元のファイルの版が変わったら差し替える。Jellyfin では Etag を合図にして大きさで確かめる
- [0012-license-mpl-2.0.md](0012-license-mpl-2.0.md) — ライセンスは GPL-3.0 ではなく MPL-2.0
- [0013-shared-folder-three-level-layout.md](0013-shared-folder-three-level-layout.md) — 共有フォルダの中はちょうど 3 段で、番組と配信元はタグでなくフォルダの名前で決める
