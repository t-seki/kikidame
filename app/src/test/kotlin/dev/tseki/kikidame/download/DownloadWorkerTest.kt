package dev.tseki.kikidame.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.tseki.kikidame.data.download.EpisodeDownloader
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.testing.FakeSessionRepository
import dev.tseki.kikidame.testing.RecordingSyncFailureNotifier
import dev.tseki.kikidame.R
import dev.tseki.kikidame.text.UiText
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** PENDING の一覧をメモリで持つキュー。 */
    private class MemoryQueue(items: List<EpisodeWithState>) : DownloadQueue {
        val states = items.associate { it.episode.id to it }.toMutableMap()
        val state = items.associate { it.episode.id to DownloadState.PENDING }.toMutableMap()
        val done = ArrayList<EpisodeId>()

        override suspend fun nextPending(): EpisodeWithState? =
            state.entries.firstOrNull { it.value == DownloadState.PENDING }?.let { states.getValue(it.key) }

        override suspend fun requeueFailed(maxAttempts: Int) = Unit
        override suspend fun resetRunning() = Unit
        override suspend fun resetToPending(episodeId: EpisodeId) { state[episodeId] = DownloadState.PENDING }
        override suspend fun markRunning(episodeId: EpisodeId) { state[episodeId] = DownloadState.RUNNING }
        override suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long) { state[episodeId] = DownloadState.DONE; done += episodeId }
        override suspend fun markFailed(episodeId: EpisodeId) { state[episodeId] = DownloadState.FAILED }
        override suspend fun isStillWanted(episodeId: EpisodeId): Boolean = state[episodeId] != DownloadState.DONE
        override suspend fun markReplaceRunning(episodeId: EpisodeId) = Unit
        override suspend fun markReplaced(episodeId: EpisodeId, sizeBytes: Long) = Unit
        override suspend fun abandonReplace(episodeId: EpisodeId) = Unit
        override suspend fun isReplaceStillWanted(episodeId: EpisodeId): Boolean = false
        override suspend fun resetRunningReplacements() = Unit
    }

    private class Source(val files: Map<SourceItemId, ByteArray>, val unauthorized: Boolean = false) : SourceGateway {
        override suspend fun fetchAll(listener: ScanListener): SourceSnapshot = error("unused")
        override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> = error("unused")
        override suspend fun fetchProgram(programId: SourceItemId): SourceProgram? = error("unused")
        override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream {
            if (unauthorized) throw SourceException.Unauthorized()
            val bytes = files[episodeId] ?: throw SourceException.Failed("HTTP 404")
            return DownloadStream(null, bytes.size.toLong(), bytes.inputStream())
        }
        override suspend fun fetchFileSize(episodeId: SourceItemId): Long? = error("unused")
    }

    private fun item(id: Long, server: String?) = EpisodeWithState(
        episode = Episode(EpisodeId(id), server?.let(::SourceItemId), ProgramId(1), "t$id", Instant.parse("2026-09-15T15:00:00Z"), null, 30.minutes, 0, "m4a"),
        localFile = LocalFile(EpisodeId(id), DownloadState.PENDING, File(tmp.root, "s/p/t$id.m4a").absolutePath, pinned = true),
        playback = null,
    )

    private fun build(
        queue: DownloadQueue,
        source: SourceGateway,
        sessions: SessionRepository,
        notifier: RecordingSyncFailureNotifier = RecordingSyncFailureNotifier(),
    ): DownloadWorker =
        TestListenableWorkerBuilder<DownloadWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    DownloadWorker(appContext, workerParameters, queue, EpisodeDownloader(source, queue), sessions, notifier, NowPlaying())
            })
            .build()

    @Test
    fun drainsTheQueueAndContinuesPastFailures() = runTest {
        val queue = MemoryQueue(listOf(item(1, "a1"), item(2, "missing"), item(3, "a3")))
        val source = Source(mapOf(SourceItemId("a1") to ByteArray(10) { 1 }, SourceItemId("a3") to ByteArray(20) { 3 }))

        val result = build(queue, source, FakeSessionRepository()).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(EpisodeId(1), EpisodeId(3)), queue.done)
        assertEquals(DownloadState.FAILED, queue.state[EpisodeId(2)])
        assertTrue(File(tmp.root, "s/p/t1.m4a").isFile && File(tmp.root, "s/p/t3.m4a").isFile)
    }

    @Test
    fun signedOutSessionDoesNothing() = runTest {
        val queue = MemoryQueue(listOf(item(1, "a1")))
        build(queue, Source(emptyMap()), FakeSessionRepository(SessionState.SignedOut(null, null))).doWork()
        assertEquals(DownloadState.PENDING, queue.state[EpisodeId(1)])
    }

    @Test
    fun unauthorizedSignsOutAndStops() = runTest {
        val queue = MemoryQueue(listOf(item(1, "a1"), item(2, "a2")))
        val sessions = FakeSessionRepository()
        val notifier = RecordingSyncFailureNotifier().apply { show(UiText.Res(R.string.sync_error_unreachable)) }
        val result = build(queue, Source(emptyMap(), unauthorized = true), sessions, notifier).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(sessions.signedOut)
        assertNull(notifier.showing, "signing out clears the sync failure notification")
        assertEquals(DownloadState.PENDING, queue.state[EpisodeId(1)], "row goes back to PENDING for next time")
        assertEquals(DownloadState.PENDING, queue.state[EpisodeId(2)])
    }
}
