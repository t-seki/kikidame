package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.db.ProgramEntity
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.RemovedUnmatchedPrograms
import dev.tseki.kikidame.domain.UnmatchedPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 「取得元に見つからない番組」（消失・取得元 ID 無し）の数え方と、まとめて消す処理（#317）。 */
@RunWith(AndroidJUnit4::class)
class RoomUnmatchedProgramsTest : RefreshTestBase() {
    private val published = Instant.parse("2026-09-15T15:00:00Z")

    private suspend fun program(name: String, sourceId: String?, gone: Boolean = false): Long =
        db.programDao().insert(
            ProgramEntity(sourceItemId = sourceId, name = name, publisherName = "局", goneSince = if (gone) now else null),
        )

    /** 回を 1 本入れ、[state] で `local_files` の行も入れる（null なら行なし）。ファイルは DONE のときだけ実在させる。 */
    private suspend fun episode(programId: Long, title: String, state: DownloadState?, pinned: Boolean = false): Pair<Long, File?> {
        val id = db.episodeDao().insert(
            EpisodeEntity(
                sourceItemId = "e-$programId-$title", programId = programId, title = title, publishedAt = published,
                addedAt = null, runtimeTicks = 0, sizeBytes = 1024, container = "m4a",
            ),
        )
        if (state == null) return id to null
        val file = if (state == DownloadState.DONE) File(tmp.root, "$programId-$title.m4a").apply { writeText("x") } else null
        db.localFileDao().upsert(LocalFileEntity(id, state, file?.absolutePath, pinned = pinned, downloadedAt = now))
        return id to file
    }

    @Test
    fun countsOnlyGoneAndSourceIdLessProgramsWithLocalAndPinnedEpisodes() = runTest {
        val gone = program("gone", "s1", gone = true)
        val noId = program("noId", null)
        val live = program("live", "s2")
        episode(gone, "g1", DownloadState.DONE, pinned = true)
        episode(gone, "g2", DownloadState.DONE)
        episode(gone, "g3", DownloadState.PENDING, pinned = true) // 手元にまだ無い回は数えない
        episode(gone, "g4", null)
        episode(noId, "n1", DownloadState.DONE)
        episode(live, "l1", DownloadState.DONE, pinned = true)

        val all = UnmatchedPrograms(programCount = 2, localEpisodeCount = 3, pinnedEpisodeCount = 1)
        assertEquals(all, downloads.unmatchedPrograms(null))
        assertEquals(all, downloads.observeUnmatchedPrograms().first())
    }

    @Test
    fun zeroWhenEveryProgramIsOnTheSource() = runTest {
        val live = program("live", "s2")
        episode(live, "l1", DownloadState.DONE)
        assertEquals(UnmatchedPrograms(0, 0, 0), downloads.unmatchedPrograms(null))
    }

    @Test
    fun emptyProgramStillCountsAsAProgram() = runTest {
        program("gone", "s1", gone = true)
        assertEquals(UnmatchedPrograms(1, 0, 0), downloads.unmatchedPrograms(null))
    }

    @Test
    fun removeDeletesTargetsWithFilesAndLeavesOthers() = runTest {
        val gone = program("gone", "s1", gone = true)
        val noId = program("noId", null)
        val live = program("live", "s2")
        val (_, goneFile) = episode(gone, "g1", DownloadState.DONE, pinned = true)
        val (_, noIdFile) = episode(noId, "n1", DownloadState.DONE)
        val (_, liveFile) = episode(live, "l1", DownloadState.DONE)

        assertEquals(RemovedUnmatchedPrograms(removed = 2, kept = 0), downloads.removeUnmatchedPrograms(null))

        assertEquals(listOf(live), db.programDao().listAll().map { it.id })
        assertFalse(goneFile!!.exists())
        assertFalse(noIdFile!!.exists())
        assertTrue(liveFile!!.exists())
        assertEquals(UnmatchedPrograms(0, 0, 0), downloads.unmatchedPrograms(null))
    }

    @Test
    fun nowPlayingProgramIsKeptAndNotCounted() = runTest {
        val gone = program("gone", "s1", gone = true)
        val other = program("other", null)
        val (playing, playingFile) = episode(gone, "g1", DownloadState.DONE, pinned = true)
        val (_, otherFile) = episode(other, "o1", DownloadState.DONE)
        val (_, otherFile2) = episode(other, "o2", DownloadState.DONE, pinned = true)

        assertEquals(UnmatchedPrograms(1, 2, 1), downloads.unmatchedPrograms(EpisodeId(playing)))
        assertEquals(RemovedUnmatchedPrograms(removed = 1, kept = 1), downloads.removeUnmatchedPrograms(EpisodeId(playing)))

        assertEquals(listOf(gone), db.programDao().listAll().map { it.id })
        assertNotNull(db.episodeDao().findById(playing))
        assertTrue(playingFile!!.exists())
        assertFalse(otherFile!!.exists())
        assertFalse(otherFile2!!.exists())
    }

    @Test
    fun nowPlayingOnAProgramOnTheSourceKeepsNothingExtra() = runTest {
        val gone = program("gone", "s1", gone = true)
        val live = program("live", "s2")
        episode(gone, "g1", DownloadState.DONE)
        val (playing, _) = episode(live, "l1", DownloadState.DONE)
        assertEquals(RemovedUnmatchedPrograms(removed = 1, kept = 0), downloads.removeUnmatchedPrograms(EpisodeId(playing)))
    }
}
