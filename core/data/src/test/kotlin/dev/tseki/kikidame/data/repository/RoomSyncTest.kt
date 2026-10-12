package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 全走査 = 同期（ADR 0004）の適用と、1 番組の同期（#279 で各回一覧の引っ張って更新も同じ）がその番組の中で削除・予約すること（削除の権限を持つこと）。 */
@RunWith(AndroidJUnit4::class)
class RoomSyncTest : JellyfinRefreshTestBase() {
    /** 公開日をタイトルにした 60 分の回。 */
    private fun se(id: String, published: String, program: String = "album-1") =
        sourceEpisode(id, title = published, published = published, program = program, runtime = 60.minutes)

    private val albumA = SourceProgram(SourceItemId("album-1"), "番組 A", "TBSラジオ")
    private val albumB = SourceProgram(SourceItemId("album-2"), "番組 B", "TBSラジオ")

    private val threeEpisodes = SourceSnapshot(
        programs = listOf(albumA),
        episodes = listOf(se("a1", "2026-09-01"), se("a2", "2026-09-02"), se("a3", "2026-09-03")),
    )

    private suspend fun programId(): ProgramId = library.observePrograms().first().single().program.id

    private suspend fun episodes(): List<EpisodeWithState> = library.observeEpisodes(programId()).first()

    private suspend fun episode(sourceId: String): EpisodeWithState =
        library.observePrograms().first().flatMap { library.observeEpisodes(it.program.id).first() }
            .first { it.episode.sourceItemId?.value == sourceId }

    /** ファイルを作って DONE にする（ダウンロード完了の代わり）。 */
    private suspend fun markDone(sourceId: String, pinned: Boolean): File {
        val e = episode(sourceId)
        return markDoneAt(e.episode.id.value, File(directory.root, "$sourceId-${e.episode.title}.m4a"), pinned)
    }

    @Test
    fun syncEnabledProgramEnqueuesTheLatestNUnpinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 2))

        val result = repo.refresh()

        assertEquals(2, result.enqueued)
        val rows = episodes()
        assertEquals(listOf("2026-09-03", "2026-09-02"), rows.filter { it.localFile != null }.map { it.episode.title })
        assertTrue(rows.filter { it.localFile != null }.all { it.localFile?.state == DownloadState.PENDING && it.localFile?.pinned == false })
        assertNull(rows.first { it.episode.title == "2026-09-01" }.localFile)
    }

    @Test
    fun aNewerEpisodePushesTheOldestOutAndKeepsItsPlaybackState() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 3))
        val file1 = markDone("a1", pinned = false)
        markDone("a2", pinned = false)
        markDone("a3", pinned = false)
        playback.update(episode("a1").episode.id) { PlaybackRules.advance(it, 10.minutes, 60.minutes, now) }

        gateway.snapshot = threeEpisodes.copy(episodes = threeEpisodes.episodes + se("a4", "2026-09-04"))
        val result = repo.refresh()

        assertEquals(1, result.enqueued)
        assertEquals(1, result.deleted)
        assertEquals(0, result.removed)
        assertFalse(file1.exists())
        val e1 = episode("a1")
        assertNull(e1.localFile, "the file row is gone")
        assertEquals(10.minutes, e1.playback?.position, "playback state survives a retention delete (ADR 0002)")
        assertEquals(DownloadState.PENDING, episode("a4").localFile?.state)
    }

    @Test
    fun pinnedEpisodesAreKeptAndNotCounted() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        val pinnedFile = markDone("a1", pinned = true)

        val result = repo.refresh()

        assertEquals(1, result.enqueued, "the latest one is downloaded in addition to the pinned one")
        assertEquals(0, result.deleted)
        assertTrue(pinnedFile.exists())
        assertEquals(DownloadState.PENDING, episode("a3").localFile?.state)
    }

    @Test
    fun deleteAfterPlayedRemovesPlayedFilesOnSync() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = null, deleteAfterPlayed = true))
        val file3 = markDone("a3", pinned = false)
        playback.update(episode("a3").episode.id) { PlaybackRules.setPlayed(it, true, now) }

        val result = repo.refresh()

        assertEquals(1, result.deleted)
        assertEquals(2, result.enqueued, "played episodes are not downloaded again")
        assertFalse(file3.exists())
        assertEquals(true, episode("a3").playback?.played)
    }

    @Test
    fun syncDisabledDeletesUnpinnedFilesButKeepsPinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val unpinned = markDone("a1", pinned = false)
        val pinned = markDone("a2", pinned = true)

        val result = repo.refresh()

        assertEquals(1, result.deleted)
        assertEquals(0, result.enqueued)
        assertFalse(unpinned.exists())
        assertTrue(pinned.exists())
    }

    @Test
    fun episodesGoneFromTheServerAreRemovedEvenWhenPinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val pinned = markDone("a1", pinned = true)
        playback.update(episode("a1").episode.id) { PlaybackRules.advance(it, 10.minutes, 60.minutes, now) }

        gateway.snapshot = threeEpisodes.copy(episodes = threeEpisodes.episodes.drop(1))
        val result = repo.refresh()

        assertEquals(1, result.removed)
        assertFalse(pinned.exists())
        val titles = episodes().map { it.episode.title }
        assertEquals(listOf("2026-09-03", "2026-09-02"), titles)
        assertEquals(2, library.observePrograms().first().single().episodeCount)
    }

    @Test
    fun aGoneProgramIsOnHoldAndUntouched() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        val file = markDone("a1", pinned = false)

        gateway.snapshot = SourceSnapshot(listOf(albumB), listOf(se("b1", "2026-09-05", program = "album-2")))
        val result = repo.refresh()

        assertEquals(1, result.onHold)
        assertEquals(0, result.deleted)
        assertEquals(0, result.removed)
        assertTrue(file.exists())
        assertEquals(3, library.observePrograms().first().first { it.program.name == "番組 A" }.episodeCount)
    }

    @Test
    fun theEpisodeBeingPlayedIsNotDeletedThisTime() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val playing = markDone("a1", pinned = false)
        val other = markDone("a2", pinned = false)

        val nowPlaying = setOf(episode("a1").episode.id)
        val result = repo.refresh(excluded = { nowPlaying })

        assertEquals(1, result.deleted)
        assertTrue(playing.exists())
        assertFalse(other.exists())
    }

    @Test
    fun aQueuedRowThatFallsOutOfTheRuleIsCancelled() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 3))
        repo.refresh()
        assertEquals(3, episodes().count { it.localFile?.state == DownloadState.PENDING })
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))

        val result = repo.refresh()

        assertEquals(2, result.deleted)
        assertEquals(listOf("2026-09-03"), episodes().filter { it.localFile != null }.map { it.episode.title })
    }

    @Test
    fun downloadsAreEnqueuedNewestFirstAcrossPrograms() = runTest {
        signInAndSelect()
        gateway.snapshot = SourceSnapshot(
            programs = listOf(albumA, albumB),
            episodes = listOf(se("a1", "2026-09-01"), se("b2", "2026-09-02", "album-2"), se("a3", "2026-09-03"), se("b4", "2026-09-04", "album-2")),
        )
        repo.refresh()
        for (p in library.observePrograms().first()) library.updateSync(p.program.id, true, RetentionRule())

        repo.refresh()

        val order = ArrayList<String>()
        while (true) {
            val next = downloads.nextPending() ?: break
            order += next.episode.title
            downloads.markRunning(next.episode.id)
        }
        assertEquals(listOf("2026-09-04", "2026-09-03", "2026-09-02", "2026-09-01"), order)
    }

    @Test
    fun manualDownloadsComeBeforeSyncedOnes() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 2))
        repo.refresh()
        now += 1.minutes
        downloads.enqueue(episode("a1").episode.id)

        assertEquals("2026-09-01", downloads.nextPending()?.episode?.title)
    }

    @Test
    fun manualDownloadPinsAnExistingSyncedRow() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        repo.refresh()
        val e3 = episode("a3")
        assertEquals(false, e3.localFile?.pinned)

        downloads.enqueue(e3.episode.id)

        assertEquals(true, episode("a3").localFile?.pinned)
    }

    /** 同期を OFF にした番組でも、1 番組の同期は固定していない手元のファイルを消す（#279。番組一覧の引っ張って更新と同じ）。 */
    @Test
    fun syncProgramOfAProgramWithSyncOffDeletesUnpinnedFilesAndKeepsPinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = false, RetentionRule(keepLatest = 1))
        val unpinned = markDone("a1", pinned = false)
        val pinned = markDone("a2", pinned = true)
        val fetchedAt = assertIs<SessionState.Ready>(session.state.first()).lastFetchedAt
        now += 5.minutes

        val result = assertNotNull(repo.syncProgram(programId()))

        assertEquals(1, result.deleted)
        assertEquals(0, result.enqueued)
        assertFalse(unpinned.exists())
        assertTrue(pinned.exists())
        assertEquals(fetchedAt, assertIs<SessionState.Ready>(session.state.first()).lastFetchedAt)
    }

    @Test
    fun duplicateTitlesInOneBatchGetDistinctPaths() = runTest {
        signInAndSelect()
        gateway.snapshot = SourceSnapshot(
            programs = listOf(albumA),
            episodes = listOf(se("a1", "2026-09-01"), se("a2", "2026-09-02").copy(title = "2026-09-01"), se("a3", "2026-09-03").copy(title = "2026-09:01")),
        )
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule())

        val result = repo.refresh()

        assertEquals(3, result.enqueued)
        val paths = episodes().mapNotNull { it.localFile?.path }
        assertEquals(3, paths.size)
        assertEquals(3, paths.toSet().size, "paths must be distinct: $paths")
    }

    @Test
    fun syncProgramAppliesTheRuleToThatProgramOnly() = runTest {
        signInAndSelect()
        gateway.snapshot = SourceSnapshot(
            programs = listOf(albumA, albumB),
            episodes = listOf(se("a1", "2026-09-01"), se("a2", "2026-09-02"), se("b1", "2026-09-01", "album-2"), se("b2", "2026-09-02", "album-2")),
        )
        repo.refresh()
        val (a, b) = library.observePrograms().first().sortedBy { it.program.name }.map { it.program.id }
        library.updateSync(a, true, RetentionRule(keepLatest = 1))
        library.updateSync(b, true, RetentionRule(keepLatest = 1))
        val fileA1 = markDone("a1", pinned = false)
        val fileB1 = markDone("b1", pinned = false)
        val fetchedAt = assertIs<SessionState.Ready>(session.state.first()).lastFetchedAt
        now += 5.minutes
        // サーバ側で a1 が消え、a3 が増えた
        gateway.snapshot = SourceSnapshot(
            programs = listOf(albumA, albumB),
            episodes = listOf(se("a2", "2026-09-02"), se("a3", "2026-09-03"), se("b1", "2026-09-01", "album-2"), se("b2", "2026-09-02", "album-2")),
        )

        val result = assertNotNull(repo.syncProgram(a))

        assertEquals(listOf(SourceItemId("album-1")), gateway.fetchedPrograms)
        assertEquals(1, result.removed, "a1 vanished from the server")
        assertEquals(1, result.enqueued, "a3 is the latest")
        assertEquals(0, result.onHold)
        assertFalse(fileA1.exists())
        assertTrue(fileB1.exists(), "the other program is untouched")
        assertEquals(DownloadState.PENDING, episode("a3").localFile?.state)
        assertEquals(DownloadState.DONE, episode("b1").localFile?.state)
        assertEquals(fetchedAt, assertIs<SessionState.Ready>(session.state.first()).lastFetchedAt, "last sync is only for the full sync")
    }

    @Test
    fun syncProgramOfAGoneProgramIsOnHold() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), true, RetentionRule(keepLatest = 1))
        val file = markDone("a1", pinned = false)
        gateway.snapshot = SourceSnapshot(emptyList(), emptyList())

        val result = assertNotNull(repo.syncProgram(programId()))

        assertEquals(1, result.onHold)
        assertEquals(0, result.deleted + result.removed + result.enqueued)
        assertTrue(file.exists())
        assertTrue(gateway.fetchedPrograms.isEmpty(), "episodes are not even requested")
        assertEquals(3, episodes().size)
    }

    @Test
    fun goneIsRecordedOnFullSyncAndClearedWhenTheProgramReturns() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val id = programId()
        assertNull(library.observeProgram(id).first()?.goneSince)
        gateway.snapshot = SourceSnapshot(listOf(albumB), listOf(se("b1", "2026-09-05", program = "album-2")))
        repo.refresh()
        assertEquals(now, library.observeProgram(id).first()?.goneSince)
        now += 5.minutes
        repo.refresh()
        assertEquals(now - 5.minutes, library.observeProgram(id).first()?.goneSince, "the first time it went missing is kept")
        gateway.snapshot = threeEpisodes.copy(programs = threeEpisodes.programs + albumB)
        repo.refresh()
        assertNull(library.observeProgram(id).first()?.goneSince, "found again")
    }
    @Test
    fun programSync404RecordsGoneAndSuccessClearsIt() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val id = programId()
        gateway.snapshot = SourceSnapshot(emptyList(), emptyList())
        repo.syncProgram(id)
        assertEquals(now, library.observeProgram(id).first()?.goneSince)
        gateway.snapshot = threeEpisodes
        repo.syncProgram(id)
        assertNull(library.observeProgram(id).first()?.goneSince)
    }
    @Test
    fun removeProgramDeletesRowsFilesAndPlayback() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val id = programId()
        val file = markDone("a1", pinned = true)
        val a1 = episode("a1").episode.id
        playback.update(a1) { PlaybackRules.advance(it, 10.minutes, 60.minutes, now) }
        assertNotNull(db.playbackStateDao().findByEpisode(a1.value))
        downloads.removeProgram(id)
        assertTrue(library.observePrograms().first().isEmpty())
        assertFalse(file.exists())
        assertEquals(0, db.episodeDao().listKeys().size)
        assertNull(db.playbackStateDao().findByEpisode(a1.value))
    }
    @Test
    fun syncProgramReturnsNullForAProgramWithoutServerId() = runTest {
        signInAndSelect()
        val id = ProgramId(db.programDao().insert(dev.tseki.kikidame.data.db.ProgramEntity(sourceItemId = null, name = "seed", publisherName = null)))

        assertNull(repo.syncProgram(id))
        assertTrue(gateway.fetchedPrograms.isEmpty())
    }
}
