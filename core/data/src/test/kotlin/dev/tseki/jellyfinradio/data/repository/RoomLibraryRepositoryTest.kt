package dev.tseki.jellyfinradio.data.repository
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.data.db.LocalFileEntity
import dev.tseki.jellyfinradio.data.db.PlaybackStateEntity
import dev.tseki.jellyfinradio.domain.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
@RunWith(AndroidJUnit4::class)
class RoomLibraryRepositoryTest : RoomTestBase() {
    private val repo by lazy { RoomLibraryRepository(db.programDao(), db.episodeDao(), db.localFileDao()) }
    private suspend fun seedProgram() = seed(
        listOf(
            scanned(title = "X 2026-06-12 (1)", airedAt = "2026-06-11T15:00:00Z"),
            scanned(title = "X 2026-06-05", airedAt = "2026-06-04T15:00:00Z"),
            scanned(title = "X 2026-06-12", airedAt = "2026-06-11T15:00:00Z"),
            scanned(title = "X 2026-06-19", airedAt = "2026-06-18T15:00:00Z"),
        ),
    ).let { repo.observePrograms().first().single().program.id }
    @Test
    fun episodeListIsNewestFirstWithTitleTieBreak() = runTest {
        val programId = seedProgram()
        val titles = repo.observeEpisodes(programId).first().map { it.episode.title }
        assertEquals(listOf("X 2026-06-19", "X 2026-06-12", "X 2026-06-12 (1)", "X 2026-06-05"), titles)
    }
    @Test
    fun playableEpisodesAreOldestFirstAndExcludeMissingFiles() = runTest {
        val programId = seedProgram()
        val all = repo.observeEpisodes(programId).first()
        val missing = all.first { it.episode.title == "X 2026-06-12 (1)" }
        db.localFileDao().upsert(
            LocalFileEntity(missing.episode.id.value, DownloadState.PENDING, path = null, pinned = false),
        )
        val playable = repo.getPlayableEpisodes(programId).map { it.episode.title }
        assertEquals(listOf("X 2026-06-05", "X 2026-06-12", "X 2026-06-19"), playable)
    }
    @Test
    fun programsAreOrderedByLatestAiredAt() = runTest {
        seed(
            listOf(
                scanned(program = "Old", title = "Old 2026-01-01", airedAt = "2025-12-31T15:00:00Z"),
                scanned(program = "New", title = "New 2026-06-01", airedAt = "2026-05-31T15:00:00Z"),
                scanned(program = "New", title = "New 2026-01-15", airedAt = "2026-01-14T15:00:00Z"),
            ),
        )
        val summaries = repo.observePrograms().first()
        assertEquals(listOf("New", "Old"), summaries.map { it.program.name })
        assertEquals(Instant.parse("2026-05-31T15:00:00Z"), summaries[0].latestAiredAt)
        assertEquals(2, summaries[0].episodeCount)
    }
    @Test
    fun setStarredRoundTrips() = runTest {
        val programId = seedProgram()
        assertEquals(false, repo.observeProgram(programId).first()?.starred)
        repo.setStarred(programId, true)
        assertEquals(true, repo.observeProgram(programId).first()?.starred)
        assertEquals(true, repo.observePrograms().first().single().program.starred)
        repo.setStarred(programId, false)
        assertEquals(false, repo.observeProgram(programId).first()?.starred)
    }

    /** 未再生の数（#41）: 手元にあって再生済みでない回。再生済みの切替と、ファイルが手元に無くなる（DONE でなくなる）ことに追従する。 */
    @Test
    fun unplayedLocalCountFollowsPlayedAndLocalFiles() = runTest {
        val programId = seedProgram()
        suspend fun summary() = repo.observePrograms().first().single()
        // seed は 4 回とも DONE のファイル付きで、再生記録は無い
        assertEquals(4, summary().unplayedLocalCount)
        val episodes = repo.observeEpisodes(programId).first()
        val id = { title: String -> episodes.first { it.episode.title == title }.episode.id.value }
        // 1 回を再生済みにすると減る。途中まで聴いただけ（played = false）の回は減らない
        db.playbackStateDao().upsert(PlaybackStateEntity(id("X 2026-06-19"), positionTicks = 0, played = true, updatedAt = now))
        db.playbackStateDao().upsert(PlaybackStateEntity(id("X 2026-06-12"), positionTicks = 1_000, played = false, updatedAt = now))
        assertEquals(3, summary().unplayedLocalCount)
        // ファイルが手元に無い（PENDING）回は数えない
        db.localFileDao().upsert(LocalFileEntity(id("X 2026-06-05"), DownloadState.PENDING, path = null, pinned = false))
        assertEquals(2, summary().unplayedLocalCount)
        assertEquals(3, summary().localEpisodeCount)
        // 未再生に戻すと増える
        db.playbackStateDao().upsert(PlaybackStateEntity(id("X 2026-06-19"), positionTicks = 0, played = false, updatedAt = now))
        assertEquals(3, summary().unplayedLocalCount)
    }
    @Test
    fun getEpisodeReturnsNullForUnknownId() = runTest {
        assertNull(repo.getEpisode(dev.tseki.jellyfinradio.domain.EpisodeId(999)))
    }
}
