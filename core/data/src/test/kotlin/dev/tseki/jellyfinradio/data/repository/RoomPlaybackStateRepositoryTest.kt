package dev.tseki.jellyfinradio.data.repository
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.PlaybackRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
@RunWith(AndroidJUnit4::class)
class RoomPlaybackStateRepositoryTest : RoomTestBase() {
    private val importer by lazy { RoomLocalImportRepository(db, clock) }
    private val library by lazy { RoomLibraryRepository(db.programDao(), db.episodeDao(), db.localFileDao()) }
    private val repo by lazy { RoomPlaybackStateRepository(db, clock) }
    private suspend fun seedEpisode(): EpisodeId {
        importer.import(listOf(scanned(title = "X 2026-06-12", airedAt = "2026-06-11T15:00:00Z")))
        val program = library.observePrograms().first().single().program.id
        return library.observeEpisodes(program).first().single().episode.id
    }
    @Test
    fun updateCreatesInitialRowThenAppliesTransform() = runTest {
        val id = seedEpisode()
        assertNull(repo.get(id))
        val saved = repo.update(id) { PlaybackRules.advance(it, 12.minutes + 345.milliseconds, 30.minutes, now) }
        assertEquals(12.minutes + 345.milliseconds, saved.position)
        assertFalse(saved.played)
        assertEquals(saved, repo.get(id), "position must round-trip through ticks exactly")
    }
    @Test
    fun observeEmitsNullThenUpdates() = runTest {
        val id = seedEpisode()
        repo.observe(id).test {
            assertNull(awaitItem())
            repo.update(id) { PlaybackRules.advance(it, 29.minutes, 30.minutes, now) }
            assertTrue(awaitItem()!!.played)
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test
    fun deletingEpisodeCascadesToPlaybackState() = runTest {
        val id = seedEpisode()
        repo.update(id) { PlaybackRules.setPlayed(it, true, now) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM episodes")
        assertNull(repo.get(id))
    }
}
