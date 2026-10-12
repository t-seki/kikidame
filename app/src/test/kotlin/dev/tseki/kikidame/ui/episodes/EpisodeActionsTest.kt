package dev.tseki.kikidame.ui.episodes

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.text.UiText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 各回一覧と「続きから」が共通で使う部品（#230）のうち純粋な関数。どの文言が選ばれたかを [UiText] で比べる（ADR 0009）。 */
class EpisodeActionsTest {
    @Test
    fun fileOnlyOfSyncedProgramSaysItComesBackOnNextSync() {
        assertEquals(UiText.Res(R.string.episode_list_msg_file_deleted_synced), LocalDeletionScope.FILE_ONLY.toMessage(syncEnabled = true))
    }

    @Test
    fun fileOnlyOfUnsyncedProgramSaysFileDeleted() {
        assertEquals(UiText.Res(R.string.episode_list_msg_file_deleted), LocalDeletionScope.FILE_ONLY.toMessage(syncEnabled = false))
    }

    @Test
    fun episodeScopeSaysEpisodeRemovedRegardlessOfSync() {
        val expected = UiText.Res(R.string.episode_list_msg_episode_removed)
        assertEquals(expected, LocalDeletionScope.EPISODE.toMessage(syncEnabled = true))
        assertEquals(expected, LocalDeletionScope.EPISODE.toMessage(syncEnabled = false))
    }

    @Test
    fun forRowsDropsPositionAndKeepsTheRest() {
        val playing = NowPlayingState(
            episodeId = EpisodeId(1), title = "回 1", programName = "番組", isPlaying = true, isEnded = false,
            positionMs = 12_000, durationMs = 3_600_000,
        )
        assertEquals(playing.copy(positionMs = 0, durationMs = 0), playing.forRows())
    }

    @Test
    fun forRowsOfNothingPlayingIsNull() {
        assertNull((null as NowPlayingState?).forRows())
    }
}
