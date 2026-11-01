package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 同じサーバ・同じユーザーのまま、音楽のライブラリからブックのライブラリへ選び直す（ADR 0010 の決定 7）。
 * 手元は消さず、次の同期で未結合になった番組・各回を突合で結び直す（ADR 0001）。番組の取得元 ID は MusicAlbum の id から
 * `<AlbumArtist>/<Album>` に、各回の取得元 ID は Audio の id から AudioBook の id に変わる。
 */
@RunWith(AndroidJUnit4::class)
class RoomLibrarySwitchTest : JellyfinRefreshTestBase() {
    private val publisher = "TBSラジオ"
    private val programName = "パンサー向井の#ふらっと"
    private val bookProgramId = "$publisher/$programName"

    private fun snapshot(program: String, prefix: String) = SourceSnapshot(
        listOf(SourceProgram(SourceItemId(program), programName, publisher)),
        listOf("2026-09-01", "2026-09-02", "2026-09-03").mapIndexed { i, day -> sourceEpisode("$prefix-${i + 1}", day, day, program = program) },
    )

    private val music = snapshot("5f0c6d2e-music-album", "audio")
    private val books = snapshot(bookProgramId, "audiobook")

    private suspend fun episode(title: String): EpisodeWithState =
        library.observePrograms().first().flatMap { library.observeEpisodes(it.program.id).first() }.first { it.episode.title == title }

    @Test
    fun switchingFromMusicToBooksRelinksRowsFilesPositionsAndPlayed() = runTest {
        signInAndSelect()
        gateway.snapshot = music
        repo.refresh()
        val program = library.observePrograms().first().single().program
        library.updateSync(program.id, true, RetentionRule(keepLatest = 3))
        // 09-01 は再生済み、09-02 は 12 分まで聴いて手元にファイルがある
        val e1 = episode("2026-09-01").episode.id
        val e2 = episode("2026-09-02").episode.id
        playback.update(e1) { PlaybackRules.setPlayed(it, true, now) }
        playback.update(e2) { PlaybackRules.advance(it, 12.minutes, 90.minutes, now) }
        val file = markDoneAt(e2.value, File(directory.root, "${e2.value}.m4a"), pinned = false)

        session.selectLibrary(LibraryView(SourceItemId("lib-books"), "Radio (books)", "books", LibraryKind.BOOKS))
        gateway.snapshot = books
        val result = repo.refresh()

        assertEquals(SourceItemId("lib-books"), gateway.fetchedLibraries.last())
        assertEquals(LibraryKind.BOOKS, gateway.usedLibraries.last().kind)
        val programs = library.observePrograms().first()
        assertEquals(1, programs.size, "no duplicate program row")
        assertEquals(program.id, programs.single().program.id, "same local program row")
        assertEquals(bookProgramId, programs.single().program.sourceItemId?.value)
        assertEquals(0, result.removed)
        assertEquals(0, result.onHold)
        assertEquals(0, result.newEpisodes, "relinked episodes are not new")
        assertEquals(3, result.linkedEpisodes)

        val relinked1 = episode("2026-09-01")
        assertEquals(e1, relinked1.episode.id, "same local row")
        assertEquals("audiobook-1", relinked1.episode.sourceItemId?.value)
        assertEquals(true, relinked1.playback?.played)
        val relinked2 = episode("2026-09-02")
        assertEquals(e2, relinked2.episode.id, "same local row")
        assertEquals("audiobook-2", relinked2.episode.sourceItemId?.value)
        assertEquals(12.minutes, relinked2.playback?.position)
        assertEquals(false, relinked2.playback?.played)
        assertEquals(DownloadState.DONE, relinked2.localFile?.state)
        assertTrue(file.exists())
        assertEquals("audiobook-3", episode("2026-09-03").episode.sourceItemId?.value)
    }

    /** 1 番組の同期も、ブックのライブラリの番組 ID とライブラリの種類で取得元に問い合わせる。 */
    @Test
    fun syncProgramAfterTheSwitchUsesTheBookLibrary() = runTest {
        signInAndSelect()
        gateway.snapshot = music
        repo.refresh()
        session.selectLibrary(LibraryView(SourceItemId("lib-books"), "Radio (books)", "books", LibraryKind.BOOKS))
        gateway.snapshot = books
        repo.refresh()
        val program = library.observePrograms().first().single().program

        val result = repo.syncProgram(program.id)

        assertEquals(0, result?.removed)
        assertEquals(listOf(SourceItemId(bookProgramId)), gateway.fetchedPrograms)
        assertEquals(LibraryKind.BOOKS, gateway.usedLibraries.last().kind)
        assertEquals(3, library.observeEpisodes(program.id).first().size)
    }
}
