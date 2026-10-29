---
status: accepted
date: 2026-09-15
---
Media3 の `DownloadManager` は DASH/HLS/プログレッシブを `SimpleCache` に断片化して保存する仕組みで、独自の `DownloadIndex` を状態の正として持つ。このアプリが落とすのは 1 回につき 1 ファイルの生の音声ファイル（Jellyfin では `/Items/{id}/Download` から得る原本、共有フォルダではフォルダの中のファイル）で、断片化したキャッシュは過剰であり、`LocalFile` のテーブルと `DownloadIndex` の二重管理はプロセスの kill やストレージの不足でずれる。そのためダウンロードは WorkManager の Worker の中で取得元のストリームを `getExternalFilesDir("episodes")` の下の生のファイルへ直接書き、`LocalFile` を唯一の正とする。Media3 は再生だけに使う。

- ダウンロードは取得元の境界（`SourceGateway.openDownload`。ADR 0007）を通す。Jellyfin の `/Items/{id}/Download` と `Range` の扱いは、Jellyfin の実装の側に閉じている
- 「Wi-Fi のみ」などの条件は、`DownloadManager.Requirements` ではなく WorkManager の `Constraints` で表す
- 中断からの再開は、`.part` のファイルと続きからの読み出し（Jellyfin では `Range` ヘッダ）で自前で行う
- `Authorization` ヘッダを付けた Media3 の `HttpDataSource.Factory` は使わない。要るのは、サーバから直接ストリーミングで再生するときだけで、このアプリは手元のファイルだけを再生する

## 退けた案

- **`DownloadManager` + `SimpleCache` を採り、`LocalFile` をやめて `DownloadIndex` を正にする** — 正は 1 つになるが、再生が `CacheDataSource` を通ることになり、生のファイルを `adb push` / `adb pull` で扱える利点を失う
- **`DownloadManager` と `LocalFile` の両方を持って同期する** — 正が 2 つになる
