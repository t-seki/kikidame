package dev.tseki.kikidame.domain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
/** 手元のファイルの合計（#42）を一覧から数える。 */
class LocalStorageUsageTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private fun item(id: Long, sizeBytes: Long, state: DownloadState?, path: String? = "/x/$id.m4a") = EpisodeWithState(
        episode = Episode(EpisodeId(id), null, ProgramId(1), "X $id", now, null, 30.minutes, sizeBytes, "m4a"),
        localFile = state?.let { LocalFile(EpisodeId(id), it, path, pinned = false) },
        playback = null,
    )
    @Test
    fun `counts only files that are done with a path`() {
        val list = listOf(
            item(1, 30_000_000, DownloadState.DONE),
            item(2, 40_000_000, DownloadState.DONE),
            item(3, 50_000_000, DownloadState.PENDING, path = null),
            item(4, 60_000_000, DownloadState.DONE, path = null),
            item(5, 70_000_000, null),
        )
        assertEquals(LocalStorageUsage(70_000_000, 2), LocalStorageUsage.of(list))
        assertEquals(LocalStorageUsage(0, 0), LocalStorageUsage.of(emptyList()))
    }
}
