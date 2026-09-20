package dev.tseki.kikidame.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 定期・起動時の同期。中身は [LibraryRefresher.refresh] そのもの（手動と同じ入口、文言だけ出さない）。
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
            Log.i(TAG, "sync: ${result.toSyncMessage()}")
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "sync-periodic"
        const val ONCE_NAME = "sync-once"
        private const val TAG = "SyncWorker"
    }
}
