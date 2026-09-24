package dev.tseki.kikidame.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 定期・起動時の同期。中身は [LibraryRefresher.refresh] そのもの（手動と同じ入口。silent なのでクルクルを出さず、結果の文言は手元に変化があったときだけ出す（#142）。
 * 手動の操作が合流したら、変化が無くても結果を出す（#134））。文言が出るのは画面が開いているときだけで、閉じている間の結果は後から出さない。
 * 走っている間は番組一覧・各回一覧のトップバーの下に細いバーと「バックグラウンドで同期中…」が出て、手動の操作が合流したらバーは消えてクルクルに切り替わる（#138）。
 * 失敗しても `retry` にはせず次の周期を待つ。401 は Refresher がログアウトする。
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresher: LibraryRefresher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = refresher.refresh(silent = true)
        if (result == null) {
            Log.i(TAG, "sync skipped or failed")
        } else {
            // 画面の文言は変化だけなので、ログには取得した件数も出す
            Log.i(
                TAG,
                "sync: programs=${result.programs} episodes=${result.episodes} new=${result.newEpisodes} " +
                    "enqueued=${result.enqueued} deleted=${result.deleted + result.removed} onHold=${result.onHold}",
            )
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "sync-periodic"
        const val ONCE_NAME = "sync-once"
        private const val TAG = "SyncWorker"
    }
}
