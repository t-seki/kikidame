package dev.tseki.kikidame.ui.settings

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.SyncAttempt
import dev.tseki.kikidame.domain.SyncOutcome
import dev.tseki.kikidame.testing.jellyfinReady
import dev.tseki.kikidame.text.UiText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 設定の「最終同期」の補足の行。最後の試みが最終同期より新しく、成功でないときだけ出す。 */
class LastSyncNoteTest {
    private val fetchedAt = Instant.parse("2026-10-25T05:00:00Z")
    private val later = fetchedAt + 3.hours

    private fun note(outcome: SyncOutcome, at: Instant = later, lastFetchedAt: Instant? = fetchedAt) =
        jellyfinReady(lastFetchedAt = lastFetchedAt).copy(lastSyncAttempt = SyncAttempt(at, outcome)).lastSyncNote()

    @Test
    fun aLaterFailureShowsTheTimeAndTheReason() {
        assertEquals(LastSyncNote.Failed(later, UiText.Res(R.string.sync_error_unreachable)), note(SyncOutcome.Unreachable))
        assertEquals(
            LastSyncNote.Failed(later, UiText.Res(R.string.sync_error_fetch_failed, "HTTP 500")),
            note(SyncOutcome.FetchFailed("HTTP 500")),
        )
        assertEquals(
            LastSyncNote.Failed(later, UiText.Res(R.string.sync_error_import_failed, "SQLiteException")),
            note(SyncOutcome.ImportFailed("SQLiteException")),
        )
    }

    @Test
    fun aLaterSkipShowsTheTime() {
        assertEquals(LastSyncNote.Skipped(later), note(SyncOutcome.SkippedNotOnWifi))
    }

    @Test
    fun aSuccessShowsNothing() {
        assertNull(note(SyncOutcome.Succeeded))
    }

    @Test
    fun anAttemptNotNewerThanTheLastSuccessShowsNothing() {
        assertNull(note(SyncOutcome.Unreachable, at = fetchedAt))
        assertNull(note(SyncOutcome.Unreachable, at = fetchedAt - 1.hours))
    }

    @Test
    fun aFailureBeforeTheFirstSuccessIsShown() {
        assertEquals(LastSyncNote.Failed(later, UiText.Res(R.string.sync_error_unreachable)), note(SyncOutcome.Unreachable, lastFetchedAt = null))
    }

    @Test
    fun noAttemptShowsNothing() {
        assertNull(jellyfinReady(lastFetchedAt = fetchedAt).lastSyncNote())
    }
}
