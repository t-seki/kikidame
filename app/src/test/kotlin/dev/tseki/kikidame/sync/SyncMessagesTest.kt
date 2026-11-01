package dev.tseki.kikidame.sync

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.text.UiText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** 更新・同期の結果の文言（`SyncMessages.kt`）。 */
class SyncMessagesTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    @Test
    fun syncMessageListsOnlyTheChanges() {
        // 「新しい回 4 件。5 回をダウンロード予約、3 回を削除。1 番組はサーバ上で見つからず、そのままにしました」
        assertEquals(
            sentences(
                UiText.Plural(R.plurals.sync_new_episodes, 4),
                actions(UiText.Plural(R.plurals.sync_enqueued, 5), UiText.Plural(R.plurals.sync_deleted, 3)),
                UiText.Plural(R.plurals.sync_on_hold, 1),
            ),
            RefreshResult(3, 40, 0, 0, now, enqueued = 5, deleted = 2, removed = 1, onHold = 1, newEpisodes = 4).toSyncMessage(),
        )
        // 「2 回を削除」
        assertEquals(
            sentences(actions(UiText.Plural(R.plurals.sync_deleted, 2))),
            RefreshResult(3, 40, 0, 0, now, deleted = 2).toSyncMessage(),
        )
        // 「新しい回 5 件」
        assertEquals(
            sentences(UiText.Plural(R.plurals.sync_new_episodes, 5)),
            RefreshResult(3, 40, 0, 0, now, newEpisodes = 5).toSyncMessage(),
        )
        // 判断保留は変化ではない: 「最新の状態です。1 番組はサーバ上で見つからず、そのままにしました」
        assertEquals(
            sentences(UiText.Res(R.string.sync_up_to_date), UiText.Plural(R.plurals.sync_on_hold, 1)),
            RefreshResult(3, 40, 0, 0, now, onHold = 1).toSyncMessage(),
        )
    }

    @Test
    fun programSyncMessages() {
        assertEquals(upToDate, RefreshResult(1, 6, 0, 0, now).toProgramSyncMessage())
        assertEquals(
            sentences(UiText.Plural(R.plurals.sync_new_episodes, 2), actions(UiText.Plural(R.plurals.sync_enqueued, 2))),
            RefreshResult(1, 6, 0, 0, now, enqueued = 2, newEpisodes = 2).toProgramSyncMessage(),
        )
        assertEquals(UiText.Res(R.string.sync_program_gone), RefreshResult(0, 0, 0, 0, now, onHold = 1).toProgramSyncMessage())
    }
}

/** 「最新の状態です」だけの文言。 */
internal val upToDate: UiText = sentences(UiText.Res(R.string.sync_up_to_date))

/** 文を「。」でつないだ形（[LibraryRefresher] が作る [UiText.Joined] と同じ構造）。 */
internal fun sentences(vararg parts: UiText): UiText = UiText.Joined(parts.toList(), UiText.Res(R.string.sync_sentence_separator))

/** 「N 回をダウンロード予約、M 回を削除」の部分。 */
internal fun actions(vararg parts: UiText): UiText = UiText.Joined(parts.toList(), UiText.Res(R.string.common_list_separator))
