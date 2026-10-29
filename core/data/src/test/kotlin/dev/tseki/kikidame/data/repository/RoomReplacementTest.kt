package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.download.DownloadOutcome
import dev.tseki.kikidame.data.download.EpisodeDownloader
import dev.tseki.kikidame.data.jellyfin.JellyfinSource
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 差し替え（CONTEXT.md、ADR 0011、#371）: 同期が、手元にある回のファイルが取得元で書き換わったかを見分けて予約し、
 * ダウンロードの Worker と同じ待ち行列から落として今のファイルに替えること。取得元は Jellyfin（[FakeJellyfinGateway]）と、
 * 一覧が大きさを返す共有フォルダの形の取得元（[FolderLike]）。
 */
@RunWith(AndroidJUnit4::class)
class RoomReplacementTest : JellyfinRefreshTestBase() {
    private val album = SourceProgram(SourceItemId("album-1"), "番組 A", "TBSラジオ")
    private val oldBytes = ByteArray(1000) { 1 }
    private val newBytes = ByteArray(1200) { 2 }

    private fun se(id: String, version: String?, published: String = "2026-09-01") =
        sourceEpisode(id, title = published, published = published, runtime = 60.minutes).copy(version = version)

    private fun listing(vararg episodes: SourceEpisode) = SourceSnapshot(listOf(album), episodes.toList())

    private suspend fun episode(sourceId: String): EpisodeWithState =
        library.observePrograms().first().flatMap { library.observeEpisodes(it.program.id).first() }
            .first { it.episode.sourceItemId?.value == sourceId }

    private suspend fun row(sourceId: String) = db.localFileDao().findByEpisode(episode(sourceId).episode.id.value)!!

    /** [sourceId] の回を [bytes] の中身で落とし終えた（DONE）ことにする。[version] は落としたときの版（null なら版を持たない回）。 */
    private suspend fun held(sourceId: String, bytes: ByteArray, version: String?, pinned: Boolean = true): File {
        val e = episode(sourceId)
        val file = markDoneAt(e.episode.id.value, File(directory.root, "$sourceId.m4a"), pinned, bytes)
        db.localFileDao().upsert(row(sourceId).copy(sourceVersion = version))
        db.episodeDao().updateSize(e.episode.id.value, bytes.size.toLong())
        return file
    }

    private val downloader by lazy { EpisodeDownloader(JellyfinSource(gateway, store), downloads) }

    /** Worker の代わりに、待ち行列の先頭を 1 本落とす。 */
    private suspend fun downloadNext(isNowPlaying: (EpisodeId) -> Boolean = { false }): DownloadOutcome =
        downloader.download(downloads.nextPending()!!, isNowPlaying)

    @Test
    fun aChangedEtagWithADifferentSizeIsReplacedKeepingPlaybackState() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        val file = held("a1", oldBytes, "e1")
        val id = episode("a1").episode.id
        playback.update(id) { PlaybackRules.advance(it, 10.minutes, 60.minutes, now) }
        val before = db.playbackStateDao().findByEpisode(id.value)

        gateway.snapshot = listing(se("a1", "e2"))
        gateway.files[SourceItemId("a1")] = newBytes
        val result = repo.refresh()

        assertEquals(1, result.enqueued, "a replacement is counted as a reservation (#371)")
        assertEquals(listOf(SourceItemId("a1")), gateway.fetchedFileSizes)
        val reserved = episode("a1")
        assertTrue(reserved.isPlayable, "the current file stays playable while replacing")
        assertTrue(reserved.localFile!!.replacing)
        assertEquals(DownloadState.PENDING, row("a1").replaceState)

        now = Instant.parse("2026-09-17T00:00:00Z")
        assertIs<DownloadOutcome.Done>(downloadNext())

        assertContentEquals(newBytes, file.readBytes())
        val after = row("a1")
        assertEquals(DownloadState.DONE, after.state)
        assertEquals("e2", after.sourceVersion)
        assertNull(after.replaceState)
        assertEquals(now, after.downloadedAt)
        assertEquals(newBytes.size.toLong(), episode("a1").episode.sizeBytes)
        assertEquals(before, db.playbackStateDao().findByEpisode(id.value), "playback_states is untouched")
        assertNull(downloads.nextPending())
    }

    @Test
    fun aChangedEtagWithTheSameSizeOnlyRecordsTheVersion() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        val file = held("a1", oldBytes, "e1")
        val downloadedAt = row("a1").downloadedAt

        gateway.snapshot = listing(se("a1", "e2"))
        gateway.fileSizes[SourceItemId("a1")] = oldBytes.size.toLong()
        val result = repo.refresh()

        assertEquals(0, result.enqueued)
        assertEquals("e2", row("a1").sourceVersion)
        assertNull(row("a1").replaceState)
        assertEquals(downloadedAt, row("a1").downloadedAt)
        assertContentEquals(oldBytes, file.readBytes())
        assertNull(downloads.nextPending())
    }

    @Test
    fun anUnchangedVersionIsNotAskedOrReplaced() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        held("a1", oldBytes, "e1")
        gateway.files[SourceItemId("a1")] = newBytes

        val result = repo.refresh()

        assertEquals(0, result.enqueued)
        assertEquals(emptyList(), gateway.fetchedFileSizes)
        assertNull(row("a1").replaceState)
    }

    @Test
    fun aNullEtagIsNeverComparedEvenWithoutAHeldVersion() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", null))
        repo.refresh()
        held("a1", oldBytes, null)
        gateway.files[SourceItemId("a1")] = newBytes

        val result = repo.refresh()

        assertEquals(0, result.enqueued)
        assertEquals(emptyList(), gateway.fetchedFileSizes)
        assertNull(row("a1").sourceVersion)
    }

    /** 更新前に落とした回（版を持たない）: 最初の同期で 1 回だけ大きさで比べ、違えば差し替え、同じなら版を書く。 */
    @Test
    fun unversionedLocalFilesAreComparedBySizeOnceAfterTheUpdate() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1", "2026-09-01"), se("a2", "f1", "2026-09-02"))
        repo.refresh()
        held("a1", oldBytes, null)
        held("a2", oldBytes, null)
        gateway.files[SourceItemId("a1")] = newBytes
        gateway.files[SourceItemId("a2")] = oldBytes.copyOf()

        val result = repo.refresh()

        assertEquals(1, result.enqueued)
        assertEquals(DownloadState.PENDING, row("a1").replaceState)
        assertNull(row("a2").replaceState)
        assertEquals("f1", row("a2").sourceVersion)
        assertEquals(setOf(SourceItemId("a1"), SourceItemId("a2")), gateway.fetchedFileSizes.toSet())

        // 次の同期では、版を持った a2 は問い合わせない（a1 は予約中なので見ない）
        gateway.fetchedFileSizes.clear()
        repo.refresh()
        assertEquals(emptyList(), gateway.fetchedFileSizes)

        assertIs<DownloadOutcome.Done>(downloadNext())
        assertEquals("e1", row("a1").sourceVersion)
    }

    @Test
    fun theNowPlayingEpisodeIsNotReservedUntilItIsNoLongerPlaying() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        held("a1", oldBytes, "e1")
        val id = episode("a1").episode.id
        gateway.snapshot = listing(se("a1", "e2"))
        gateway.files[SourceItemId("a1")] = newBytes

        val playing = repo.refresh(excluded = { setOf(id) })

        assertEquals(0, playing.enqueued)
        assertNull(row("a1").replaceState)
        assertEquals("e1", row("a1").sourceVersion)

        val later = repo.refresh()

        assertEquals(1, later.enqueued)
        assertEquals(DownloadState.PENDING, row("a1").replaceState)
    }

    @Test
    fun aReplacementFinishedWhileNowPlayingIsDroppedAndReservedAgainNextSync() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        val file = held("a1", oldBytes, "e1")
        val id = episode("a1").episode.id
        gateway.snapshot = listing(se("a1", "e2"))
        gateway.files[SourceItemId("a1")] = newBytes
        repo.refresh()

        assertIs<DownloadOutcome.Postponed>(downloadNext(isNowPlaying = { it == id }))

        assertContentEquals(oldBytes, file.readBytes())
        assertFalse(File(file.path + ".part").exists())
        assertEquals("e1", row("a1").sourceVersion)
        assertNull(row("a1").replaceState)
        assertNull(downloads.nextPending())

        assertEquals(1, repo.refresh().enqueued, "the next sync reserves it again")
    }

    @Test
    fun aFailedReplacementKeepsTheCurrentFileAndVersion() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        val file = held("a1", oldBytes, "e1")
        gateway.snapshot = listing(se("a1", "e2"))
        gateway.fileSizes[SourceItemId("a1")] = newBytes.size.toLong()
        repo.refresh()

        // 取得元にファイルが無い（404）
        assertIs<DownloadOutcome.Failed>(downloadNext())

        assertContentEquals(oldBytes, file.readBytes())
        assertTrue(episode("a1").isPlayable)
        val after = row("a1")
        assertEquals("e1", after.sourceVersion)
        assertNull(after.replaceState)
        assertEquals(0, after.attemptCount)
        assertEquals(1, repo.refresh().enqueued, "the next sync reserves it again")
    }

    @Test
    fun deletingTheEpisodeDuringAReplacementCancelsIt() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        val file = held("a1", oldBytes, "e1")
        gateway.snapshot = listing(se("a1", "e2"))
        gateway.files[SourceItemId("a1")] = ByteArray(3 * 1024 * 1024) { 3 }
        repo.refresh()
        val item = downloads.nextPending()!!

        val outcome = downloader.download(item) { _, _ -> downloads.deleteLocal(item.episode.id) }

        assertIs<DownloadOutcome.Cancelled>(outcome)
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
        assertNull(db.localFileDao().findByEpisode(item.episode.id.value))
    }

    @Test
    fun aRetentionDeleteDuringAReplacementCancelsIt() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        val file = held("a1", oldBytes, "e1", pinned = false)
        val programId = library.observePrograms().first().single().program.id
        library.updateSync(programId, syncEnabled = true, RetentionRule(keepLatest = 1))
        gateway.snapshot = listing(se("a1", "e2"))
        gateway.fileSizes[SourceItemId("a1")] = newBytes.size.toLong()
        repo.refresh()
        assertEquals(DownloadState.PENDING, row("a1").replaceState)
        File(file.path + ".part").writeBytes(ByteArray(10))

        library.updateSync(programId, syncEnabled = false, RetentionRule(keepLatest = 1))
        repo.refresh()

        assertNull(db.localFileDao().findByEpisode(episode("a1").episode.id.value))
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
        assertNull(downloads.nextPending())
    }

    @Test
    fun aNormalDownloadRecordsTheVersionSeenWhenItWasReserved() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1"))
        repo.refresh()
        downloads.enqueue(episode("a1").episode.id)
        assertEquals("e1", row("a1").sourceVersion)

        // 落とす前に取得元が変わっても、記録するのは予約したときの版（次の同期で差し替えに回す）
        gateway.snapshot = listing(se("a1", "e2"))
        repo.refresh()
        gateway.files[SourceItemId("a1")] = newBytes
        assertIs<DownloadOutcome.Done>(downloadNext())

        assertEquals("e1", row("a1").sourceVersion)
        gateway.fileSizes[SourceItemId("a1")] = newBytes.size.toLong() + 1
        assertEquals(1, repo.refresh().enqueued)
    }

    @Test
    fun anUnreachableSizeQueryDoesNotFailTheSync() = runTest {
        signInAndSelect()
        gateway.snapshot = listing(se("a1", "e1", "2026-09-01"), se("a2", "f1", "2026-09-02"))
        repo.refresh()
        held("a1", oldBytes, "e1")
        held("a2", oldBytes, "f1")
        gateway.snapshot = listing(se("a1", "e2", "2026-09-01"), se("a2", "f2", "2026-09-02"))
        gateway.fileSizeFailure = SourceException.Unreachable()

        val result = repo.refresh()

        assertEquals(0, result.enqueued)
        assertEquals(1, gateway.fetchedFileSizes.size, "stops asking after the source is unreachable")
        assertEquals("e1", row("a1").sourceVersion)
        assertEquals("f1", row("a2").sourceVersion)
    }

    /** 一覧がファイルの大きさと版（大きさと更新日時の組）を返す、共有フォルダの形の取得元。 */
    private class FolderLike(var listing: SourceSnapshot) : SourceGateway {
        val askedSizes = ArrayList<SourceItemId>()
        override suspend fun fetchAll(listener: ScanListener) = listing
        override suspend fun fetchProgramEpisodes(programId: SourceItemId) = listing.episodes.filter { it.programSourceId == programId }
        override suspend fun fetchProgram(programId: SourceItemId) = listing.programs.firstOrNull { it.sourceId == programId }
        override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream = throw UnsupportedOperationException()
        override suspend fun fetchFileSize(episodeId: SourceItemId): Long? {
            askedSizes += episodeId
            return null
        }
    }

    private fun folderEpisode(id: String, size: Long, modifiedMillis: Long, published: String = "2026-09-01") =
        se(id, "$size:$modifiedMillis", published).copy(sizeBytes = size, sourceFileSize = size, sourceModifiedAt = Instant.fromEpochMilliseconds(modifiedMillis))

    @Test
    fun aSharedFolderReplacesOnAChangedModifiedTimeEvenWithTheSameSize() = runTest {
        store.saveSmb(SmbConnection("nas.local", "recordings", "", "alice", "pw"))
        val folder = FolderLike(listing(folderEpisode("P/番組/a.m4a", 1000, 1)))
        val folderRepo = refreshRepository(folder)
        folderRepo.refresh()
        held("P/番組/a.m4a", oldBytes, "1000:1")

        folder.listing = listing(folderEpisode("P/番組/a.m4a", 1000, 2))
        val result = folderRepo.refresh()

        assertEquals(1, result.enqueued)
        assertEquals(DownloadState.PENDING, row("P/番組/a.m4a").replaceState)
        assertEquals("1000:2", row("P/番組/a.m4a").replaceVersion)
        assertEquals(emptyList(), folder.askedSizes, "a listing with sizes is never asked again")
    }

    @Test
    fun aSharedFolderComparesUnversionedFilesWithTheListedSize() = runTest {
        store.saveSmb(SmbConnection("nas.local", "recordings", "", "alice", "pw"))
        val folder = FolderLike(
            listing(folderEpisode("P/番組/a.m4a", 1200, 5, "2026-09-01"), folderEpisode("P/番組/b.m4a", 1000, 5, "2026-09-02")),
        )
        val folderRepo = refreshRepository(folder)
        folderRepo.refresh()
        held("P/番組/a.m4a", oldBytes, null)
        held("P/番組/b.m4a", oldBytes, null)

        // 走査が episodes.sizeBytes を一覧の大きさで上書きしても、手元の大きさはディスク上のファイルで比べる
        val result = folderRepo.refresh()

        assertEquals(1, result.enqueued)
        assertEquals(DownloadState.PENDING, row("P/番組/a.m4a").replaceState)
        assertNull(row("P/番組/b.m4a").replaceState)
        assertEquals("1000:5", row("P/番組/b.m4a").sourceVersion)
        assertEquals(emptyList(), folder.askedSizes)
    }
}
