---
status: accepted
date: 2026-09-15
---
Media3 の `DownloadManager` は DASH/HLS/プログレッシブを `SimpleCache` に断片化して保存する仕組みで、独自の `DownloadIndex` を状態の正として持つ。本アプリが落とすのは `/Items/{id}/Download` から得る 1 ファイル 1 URL の生 AAC/M4A で、断片化キャッシュは過剰であり、`LocalFile` テーブルと `DownloadIndex` の二重管理はプロセス kill やストレージ不足で必ずずれる。そのためダウンロードは WorkManager の Worker 内で HTTP ストリームを `getExternalFilesDir("episodes")` 配下の生ファイルへ直接書き、`LocalFile` を唯一の正とする。Media3 は再生専用に使う。
- `DownloadManager` + `SimpleCache` を採用し `LocalFile` を廃止、`DownloadIndex` を正にする — 正は一つになるが、再生が `CacheDataSource` 経由になり、生ファイルを `adb push/pull` できる利点と M1 のファイル配置との整合を失う
- 両方持って同期する（引き継ぎ文書の原案）— 正が二つになるため不採用
- Wi-Fi のみ等の条件は `DownloadManager.Requirements` ではなく WorkManager の `Constraints` で表現する
- 中断からの再開は `.part` ファイル + `Range` ヘッダで自前実装する
- 引き継ぎ文書の「`DownloadManager` に渡す `DataSource.Factory` も同一のものを使う」という注意は不要になる。`Authorization` ヘッダの設定が必要な Media3 の `HttpDataSource.Factory` は、サーバから直接ストリーミング再生する場合（M4 以降で検討）に限られる
