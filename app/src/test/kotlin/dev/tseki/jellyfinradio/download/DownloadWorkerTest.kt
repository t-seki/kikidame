package dev.tseki.jellyfinradio.download

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {
    @Test
    fun skeletonWorkerSucceeds() = runTest {
        val worker = TestListenableWorkerBuilder<DownloadWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters,
                ): ListenableWorker = DownloadWorker(appContext, workerParameters)
            })
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
