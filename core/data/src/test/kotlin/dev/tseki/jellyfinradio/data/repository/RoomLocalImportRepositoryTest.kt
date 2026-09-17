package dev.tseki.jellyfinradio.data.repository
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.ImportResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
@RunWith(AndroidJUnit4::class)
class RoomLocalImportRepositoryTest : RoomTestBase() {
    private val repo by lazy { RoomLocalImportRepository(db, clock) }
    private val library by lazy { RoomLibraryRepository(db.programDao(), db.episodeDao(), db.localFileDao()) }
    @Test
    fun importsProgramsAndEpisodesAsPinnedLocalFiles() = runTest {
        val result = repo.import(
            listOf(
                scanned(title = "LOGISTEED RADIONOMICS 2026-06-12", airedAt = "2026-06-11T15:00:00Z"),
                scanned(title = "LOGISTEED RADIONOMICS 2026-06-19", airedAt = "2026-06-18T15:00:00Z"),
                scanned(station = "LFR", program = "ANN", title = "ANN 2026-06-13", airedAt = "2026-06-12T15:00:00Z"),
            ),
        )
        assertEquals(ImportResult(addedPrograms = 2, addedEpisodes = 3, skippedEpisodes = 0), result)
        val programs = library.observePrograms().first()
        assertEquals(listOf("LOGISTEED RADIONOMICS", "ANN"), programs.map { it.program.name })
        assertEquals(listOf("J-WAVE", "LFR"), programs.map { it.program.stationName })
        assertEquals(listOf(2, 1), programs.map { it.episodeCount })
        assertTrue(programs.all { it.program.serverItemId == null })
        val episodes = library.observeEpisodes(programs[0].program.id).first()
        val local = episodes.map { it.localFile!! }
        assertTrue(local.all { it.state == DownloadState.DONE && it.pinned && it.downloadedAt == now })
        assertTrue(episodes.all { it.episode.serverItemId == null && it.episode.addedAt == null })
        assertTrue(episodes.all { it.playback == null })
    }
    @Test
    fun secondImportIsIdempotentAndOnlyAddsNewFiles() = runTest {
        val first = scanned(title = "A 2026-06-12", airedAt = "2026-06-11T15:00:00Z")
        repo.import(listOf(first))
        val second = repo.import(
            listOf(
                first.copy(airedAt = first.airedAt, title = "renamed title but same path"),
                scanned(title = "A 2026-06-19", airedAt = "2026-06-18T15:00:00Z"),
            ),
        )
        assertEquals(ImportResult(addedPrograms = 0, addedEpisodes = 1, skippedEpisodes = 1), second)
        val programs = library.observePrograms().first()
        assertEquals(1, programs.size)
        val titles = library.observeEpisodes(programs[0].program.id).first().map { it.episode.title }
        assertEquals(listOf("A 2026-06-19", "A 2026-06-12"), titles, "existing row must not be rewritten")
    }
    @Test
    fun emptyScanImportsNothing() = runTest {
        assertEquals(ImportResult(0, 0, 0), repo.import(emptyList()))
        assertNull(library.observePrograms().first().firstOrNull())
    }
}
