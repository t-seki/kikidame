---
date: 2026-09-15
---
ダウンロードに Media3 の `DownloadManager` は使わない。WorkManager の Worker の中で取得元のストリームをアプリの領域の生のファイルへ直接書き、手元のファイルの表（`LocalFile`）を唯一の正とする。Media3 は再生だけに使う。

## 理由

- Media3 の `DownloadManager` は DASH/HLS/プログレッシブを `SimpleCache` に断片化して保存する仕組みで、独自の `DownloadIndex` を状態の正として持つ。このアプリが落とすのは 1 回につき 1 ファイルの生の音声ファイル（Jellyfin では原本、共有フォルダではフォルダの中のファイル）で、断片化したキャッシュは過剰である
- `LocalFile` と `DownloadIndex` の二重管理は、プロセスの kill やストレージの不足でずれる

## 決定に伴うこと

- ダウンロードは取得元の境界（ADR 0007）を通す
- 「Wi-Fi のみ」などの条件は、`DownloadManager.Requirements` ではなく WorkManager の条件で表す
- 中断からの再開は、書きかけのファイルと続きからの読み出しで自前で行う
- 認証のヘッダを付けた Media3 の `HttpDataSource.Factory` は使わない。要るのは、サーバから直接ストリーミングで再生するときだけで、このアプリは手元のファイルだけを再生する

形と流れは `docs/architecture/download.md` の「ダウンロード」節。

## 退けた案

- **`DownloadManager` + `SimpleCache` を採り、`LocalFile` をやめて `DownloadIndex` を正にする** — 正は 1 つになるが、再生が `CacheDataSource` を通ることになり、生のファイルを `adb push` / `adb pull` で扱える利点を失う
- **`DownloadManager` と `LocalFile` の両方を持って同期する** — 正が 2 つになる
