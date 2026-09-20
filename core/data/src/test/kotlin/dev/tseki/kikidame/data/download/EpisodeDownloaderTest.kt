package dev.tseki.kikidame.data.download

import dev.tseki.kikidame.data.jellyfin.ServerCredentials
import dev.tseki.kikidame.data.repository.FakeJellyfinGateway
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EpisodeDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 状態だけを持つキュー。キャンセルは [wanted] を false にして表す。 */
    private class FakeQueue : DownloadQueue {
        var state = DownloadState.PENDING
        var wanted = true
        var done: Pair<String, Long>? = null
        var failures = 0
        override suspend fun nextPending(): EpisodeWithState? = null
        override suspend fun requeueFailed(maxAttempts: Int) = Unit
        override suspend fun resetRunning() = Unit
        override suspend fun resetToPending(episodeId: EpisodeId) { state = DownloadState.PENDING }
        override suspend fun markRunning(episodeId: EpisodeId) { state = DownloadState.RUNNING }
        override suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long) { state = DownloadState.DONE; done = path to sizeBytes }
        override suspend fun markFailed(episodeId: EpisodeId) { state = DownloadState.FAILED; failures++ }
        override suspend fun isStillWanted(episodeId: EpisodeId): Boolean = wanted
    }

    private val gateway = FakeJellyfinGateway()
    private val queue = FakeQueue()
    private val downloader = EpisodeDownloader(gateway, queue)
    private val credentials = ServerCredentials("https://x/", "t", "u")
    private val serverId = ServerItemId("a1")
    private val payload = ByteArray(3 * 1024 * 1024 + 123) { (it % 251).toByte() }

    private fun item(): EpisodeWithState {
        val target = File(tmp.root, "TBSラジオ/番組/2026-09-16.m4a")
        return EpisodeWithState(
            episode = Episode(EpisodeId(1), serverId, ProgramId(1), "2026-09-16", Instant.parse("2026-09-15T15:00:00Z"), null, 90.minutes, 0, "m4a"),
            localFile = LocalFile(EpisodeId(1), DownloadState.PENDING, target.absolutePath, pinned = true),
            playback = null,
        )
    }

    @Test
    fun downloadsIntoPartThenRenames() = runTest {
        gateway.files[serverId] = payload
        val progress = ArrayList<Pair<Long, Long?>>()

        val outcome = downloader.download(item(), credentials) { d, t -> progress += d to t }

        val done = assertIs<DownloadOutcome.Done>(outcome)
        assertEquals(payload.size.toLong(), done.sizeBytes)
        assertContentEquals(payload, File(done.path).readBytes())
        assertFalse(File(done.path + ".part").exists())
        assertEquals(DownloadState.DONE, queue.state)
        assertEquals(done.path to payload.size.toLong(), queue.done)
        assertTrue(progress.isNotEmpty() && progress.last().first == payload.size.toLong())
        assertEquals(listOf(0L), gateway.openedRanges)
    }

    @Test
    fun resumesFromExistingPartWhenServerHonoursRange() = runTest {
        gateway.files[serverId] = payload
        val it = item()
        val part = File(it.localFile!!.path!! + ".part").apply { parentFile!!.mkdirs(); writeBytes(payload.copyOf(1000)) }

        val outcome = downloader.download(it, credentials)

        assertIs<DownloadOutcome.Done>(outcome)
        assertEquals(listOf(1000L), gateway.openedRanges)
        assertContentEquals(payload, File(it.localFile!!.path!!).readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun rewritesFromScratchWhenServerIgnoresRange() = runTest {
        gateway.files[serverId] = payload
        gateway.honorRange = false
        val it = item()
        File(it.localFile!!.path!! + ".part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1000) { 9 }) }

        val outcome = downloader.download(it, credentials)

        assertIs<DownloadOutcome.Done>(outcome)
        assertEquals(listOf(1000L), gateway.openedRanges, "we asked for a range")
        assertContentEquals(payload, File(it.localFile!!.path!!).readBytes(), "stale part bytes must not leak in")
    }

    @Test
    fun cancellationMidwayDeletesPartAndDoesNotMarkFailed() = runTest {
        gateway.files[serverId] = payload
        val it = item()
        var calls = 0
        val outcome = downloader.download(it, credentials) { _, _ -> if (++calls == 1) queue.wanted = false }

        assertIs<DownloadOutcome.Cancelled>(outcome)
        assertFalse(File(it.localFile!!.path!! + ".part").exists())
        assertFalse(File(it.localFile!!.path!!).exists())
        assertEquals(0, queue.failures)
    }

    @Test
    fun missingFileOnServerIsFailed() = runTest {
        val outcome = downloader.download(item(), credentials)
        assertIs<DownloadOutcome.Failed>(outcome)
        assertEquals(DownloadState.FAILED, queue.state)
        assertEquals(1, queue.failures)
    }

    @Test
    fun unauthorizedPropagatesAndRowGoesBackToPending() = runTest {
        gateway.failWith = ServerException.Unauthorized()
        assertFailsWith<ServerException.Unauthorized> { downloader.download(item(), credentials) }
        assertEquals(DownloadState.PENDING, queue.state)
        assertEquals(0, queue.failures)
    }
}
