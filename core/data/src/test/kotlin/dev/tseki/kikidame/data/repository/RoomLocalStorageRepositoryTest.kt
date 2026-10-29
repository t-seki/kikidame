package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.db.PlaybackStateEntity
import dev.tseki.kikidame.data.db.ProgramEntity
import dev.tseki.kikidame.domain.DeletedLocalEpisodes
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalEpisode
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 手元のファイルの内訳（番組ごと・回ごとの容量と、固定・再生済み・未再生の内訳）と、選んだ回の削除。 */
@RunWith(AndroidJUnit4::class)
class RoomLocalStorageRepositoryTest : RefreshTestBase() {
    private val storage by lazy { RoomLocalStorageRepository(db.localFileDao(), downloads) }

    private suspend fun program(name: String, sourceId: String? = "p-$name", syncEnabled: Boolean = false): Long =
        db.programDao().insert(ProgramEntity(sourceItemId = sourceId, name = name, publisherName = "局", syncEnabled = syncEnabled))

    /**
     * 回を 1 本入れる。[state] が null なら `local_files` の行なし。ファイルは DONE でパスのある回だけ実在させる（[withPath] が false ならパスも無し）。
     * [played] が null なら `playback_states` の行なし（未再生）。
     */
    private suspend fun episode(
        programId: Long,
        title: String,
        size: Long,
        state: DownloadState? = DownloadState.DONE,
        pinned: Boolean = false,
        played: Boolean? = null,
        published: String = "2026-09-15T15:00:00Z",
        sourceId: String? = "e-$programId-$title",
        withPath: Boolean = true,
    ): Pair<Long, File?> {
        val id = db.episodeDao().insert(
            EpisodeEntity(
                sourceItemId = sourceId, programId = programId, title = title, publishedAt = Instant.parse(published),
                addedAt = null, runtimeTicks = 0, sizeBytes = size, container = "m4a",
            ),
        )
        played?.let { db.playbackStateDao().upsert(PlaybackStateEntity(id, positionTicks = 0, played = it, updatedAt = now)) }
        if (state == null) return id to null
        val file = if (state == DownloadState.DONE && withPath) File(tmp.root, "$programId-$title.m4a").apply { writeText("x") } else null
        db.localFileDao().upsert(LocalFileEntity(id, state, file?.absolutePath, pinned = pinned, downloadedAt = now))
        return id to file
    }

    @Test
    fun programBreakdownSplitsIntoPinnedPlayedAndUnplayedThatSumToTheCount() = runTest {
        val radio = program("radio")
        episode(radio, "pinned-played", 100, pinned = true, played = true) // 固定は再生済みかを問わず固定に数える
        episode(radio, "pinned-unplayed", 100, pinned = true, played = false)
        episode(radio, "played", 100, played = true)
        episode(radio, "unplayed-no-row", 100) // 再生の行が無い回は未再生
        episode(radio, "unplayed", 100, played = false)

        val row = storage.observePrograms().first().single()
        assertEquals(ProgramStorage(ProgramId(radio), "radio", 500, 5, pinnedCount = 2, playedUnpinnedCount = 1, unplayedUnpinnedCount = 2), row)
        assertEquals(row.episodeCount, row.pinnedCount + row.playedUnpinnedCount + row.unplayedUnpinnedCount)
    }

    @Test
    fun countsOnlyEpisodesOnTheDeviceAndMatchesTheTotal() = runTest {
        val radio = program("radio")
        episode(radio, "done", 100)
        episode(radio, "pending", 1000, state = DownloadState.PENDING)
        episode(radio, "running", 1000, state = DownloadState.RUNNING)
        episode(radio, "failed", 1000, state = DownloadState.FAILED)
        episode(radio, "no-path", 1000, withPath = false)
        episode(radio, "source-only", 1000, state = null)

        assertEquals(listOf(100L to 1), storage.observePrograms().first().map { it.totalBytes to it.episodeCount })
        assertEquals(listOf("done"), storage.observeEpisodes().first().map { it.title })
        // 設定の行の合計と同じ定義
        val usage = library.observeLocalStorage().first()
        assertEquals(usage.totalBytes to usage.episodeCount, 100L to 1)
    }

    @Test
    fun programsWithoutEpisodesOnTheDeviceAreLeftOutAndTheRestAreLargestFirst() = runTest {
        val small = program("small")
        val large = program("large")
        program("empty")
        val sourceOnly = program("source-only")
        episode(small, "s1", 100)
        episode(large, "l1", 300)
        episode(large, "l2", 300)
        episode(sourceOnly, "o1", 5000, state = null)

        assertEquals(listOf("large" to 600L, "small" to 100L), storage.observePrograms().first().map { it.programName to it.totalBytes })
    }

    @Test
    fun allEpisodesAreLargestFirstAcrossProgramsWithTheirState() = runTest {
        val a = program("a", syncEnabled = true)
        val b = program("b", sourceId = null)
        val (mid, _) = episode(a, "mid", 200, pinned = true, played = true)
        val (big, _) = episode(b, "big", 900, sourceId = null)
        val (tiny, _) = episode(a, "tiny", 10)

        val published = Instant.parse("2026-09-15T15:00:00Z")
        assertEquals(
            listOf(
                LocalEpisode(EpisodeId(big), ProgramId(b), "b", "big", published, 900, pinned = false, played = false, hasSourceItemId = false, programSyncEnabled = false),
                LocalEpisode(EpisodeId(mid), ProgramId(a), "a", "mid", published, 200, pinned = true, played = true, hasSourceItemId = true, programSyncEnabled = true),
                LocalEpisode(EpisodeId(tiny), ProgramId(a), "a", "tiny", published, 10, pinned = false, played = false, hasSourceItemId = true, programSyncEnabled = true),
            ),
            storage.observeEpisodes().first(),
        )
    }

    @Test
    fun programEpisodesAreNewestFirstAndOnlyThatProgram() = runTest {
        val a = program("a")
        val other = program("other")
        episode(a, "old", 900, published = "2026-09-01T15:00:00Z")
        episode(a, "new-b", 10, published = "2026-09-10T15:00:00Z")
        episode(a, "new-a", 20, published = "2026-09-10T15:00:00Z") // 同じ公開日はタイトルの辞書順
        episode(other, "x", 500, published = "2026-09-20T15:00:00Z")

        assertEquals(listOf("new-a", "new-b", "old"), storage.observeEpisodes(ProgramId(a)).first().map { it.title })
    }

    @Test
    fun deleteRemovesOnlyTheChosenEpisodesWithTheSameScopeAsDeleteLocal() = runTest {
        val radio = program("radio")
        val noId = program("noId", sourceId = null)
        val (withSource, withSourceFile) = episode(radio, "with-source", 100, pinned = true, played = true)
        val (kept, keptFile) = episode(radio, "kept", 100)
        val (withoutSource, withoutSourceFile) = episode(noId, "without-source", 100, sourceId = null)

        assertEquals(DeletedLocalEpisodes(deleted = 2, kept = 0), storage.deleteLocalEpisodes(listOf(EpisodeId(withSource), EpisodeId(withoutSource)), null))

        // 取得元 ID のある回はファイルと local_files の行だけ（各回と再生位置は残る）
        assertNotNull(db.episodeDao().findById(withSource))
        assertNull(db.localFileDao().findByEpisode(withSource))
        assertNotNull(db.playbackStateDao().findByEpisode(withSource))
        assertFalse(withSourceFile!!.exists())
        // 取得元 ID の無い回は各回ごと（各回が 0 になった取得元 ID 無しの番組も消える）
        assertNull(db.episodeDao().findById(withoutSource))
        assertNull(db.programDao().findById(noId))
        assertFalse(withoutSourceFile!!.exists())
        // 選んでいない回は残る
        assertNotNull(db.localFileDao().findByEpisode(kept))
        assertTrue(keptFile!!.exists())
        assertEquals(listOf("kept"), storage.observeEpisodes().first().map { it.title })
    }

    @Test
    fun deleteKeepsTheEpisodeBeingListenedTo() = runTest {
        val radio = program("radio")
        val (playing, playingFile) = episode(radio, "playing", 100)
        val (other, otherFile) = episode(radio, "other", 100)

        assertEquals(
            DeletedLocalEpisodes(deleted = 1, kept = 1),
            storage.deleteLocalEpisodes(listOf(EpisodeId(playing), EpisodeId(other)), except = EpisodeId(playing)),
        )
        assertNotNull(db.localFileDao().findByEpisode(playing))
        assertTrue(playingFile!!.exists())
        assertNull(db.localFileDao().findByEpisode(other))
        assertFalse(otherFile!!.exists())
    }

    @Test
    fun deleteSkipsEpisodesThatAreNoLongerOnTheDevice() = runTest {
        val radio = program("radio")
        val (gone, _) = episode(radio, "gone", 100)
        val (pending, _) = episode(radio, "pending", 100, state = DownloadState.PENDING)
        downloads.deleteLocal(EpisodeId(gone)) // 選んだ後にほかの経路で消えた

        assertEquals(DeletedLocalEpisodes(deleted = 0, kept = 0), storage.deleteLocalEpisodes(listOf(EpisodeId(gone), EpisodeId(pending)), null))
        // ダウンロード待ちの回は取り消さない
        assertEquals(DownloadState.PENDING, db.localFileDao().findByEpisode(pending)?.state)
    }
}
