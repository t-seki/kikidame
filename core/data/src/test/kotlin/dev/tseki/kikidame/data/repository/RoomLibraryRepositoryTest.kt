package dev.tseki.kikidame.data.repository
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.db.PlaybackStateEntity
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.LocalStorageUsage
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
            scanned(title = "X 2026-06-12 (1)", publishedAt = "2026-06-11T15:00:00Z"),
            scanned(title = "X 2026-06-05", publishedAt = "2026-06-04T15:00:00Z"),
            scanned(title = "X 2026-06-12", publishedAt = "2026-06-11T15:00:00Z"),
            scanned(title = "X 2026-06-19", publishedAt = "2026-06-18T15:00:00Z"),
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
    fun programsAreOrderedByLatestPublishedAt() = runTest {
        seed(
            listOf(
                scanned(program = "Old", title = "Old 2026-01-01", publishedAt = "2025-12-31T15:00:00Z"),
                scanned(program = "New", title = "New 2026-06-01", publishedAt = "2026-05-31T15:00:00Z"),
                scanned(program = "New", title = "New 2026-01-15", publishedAt = "2026-01-14T15:00:00Z"),
            ),
        )
        val summaries = repo.observePrograms().first()
        assertEquals(listOf("New", "Old"), summaries.map { it.program.name })
        assertEquals(Instant.parse("2026-05-31T15:00:00Z"), summaries[0].latestPublishedAt)
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
    /** 手元のファイルの合計（#42）: DONE でパスがある回の sizeBytes を足す。ダウンロード途中・パス無しは数えない。 */
    @Test
    fun localStorageSumsDoneFilesOnly() = runTest {
        val programId = seedProgram()
        // seed は 4 回とも DONE・1024 B
        assertEquals(LocalStorageUsage(4 * 1024L, 4), repo.observeLocalStorage().first())
        val episodes = repo.observeEpisodes(programId).first()
        val id = { title: String -> episodes.first { it.episode.title == title }.episode.id.value }
        db.episodeDao().updateSize(id("X 2026-06-19"), 30_000_000)
        db.localFileDao().upsert(LocalFileEntity(id("X 2026-06-05"), DownloadState.PENDING, path = null, pinned = false))
        assertEquals(LocalStorageUsage(30_000_000 + 2 * 1024L, 3), repo.observeLocalStorage().first())
        db.localFileDao().delete(id("X 2026-06-12"))
        assertEquals(LocalStorageUsage(30_000_000 + 1024L, 2), repo.observeLocalStorage().first())
    }
    /** 出演者（#70）は 1 列に畳んで保存し、複数名も空も元の一覧のまま読める。 */
    @Test
    fun performersRoundTripThroughTheColumn() = runTest {
        val programId = seedProgram()
        val episodes = repo.observeEpisodes(programId).first()
        val id = { title: String -> episodes.first { it.episode.title == title }.episode.id.value }
        val entity = db.episodeDao().findById(id("X 2026-06-19"))!!.episode
        db.episodeDao().update(entity.copy(performers = listOf("岩井勇気", "澤部佑")))
        val reloaded = repo.observeEpisodes(programId).first()
        assertEquals(listOf("岩井勇気", "澤部佑"), reloaded.first { it.episode.title == "X 2026-06-19" }.episode.performers)
        assertEquals(emptyList(), reloaded.first { it.episode.title == "X 2026-06-05" }.episode.performers)
    }

    @Test
    fun getEpisodeReturnsNullForUnknownId() = runTest {
        assertNull(repo.getEpisode(dev.tseki.kikidame.domain.EpisodeId(999)))
    }
}
