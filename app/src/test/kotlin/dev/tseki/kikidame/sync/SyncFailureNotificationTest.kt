package dev.tseki.kikidame.sync

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SyncOutcome
import dev.tseki.kikidame.testing.FakeSessionRepository
import dev.tseki.kikidame.testing.RecordingSyncFailureNotifier
import dev.tseki.kikidame.testing.jellyfinReady
import dev.tseki.kikidame.text.UiText
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 裏の同期が失敗で終わったときに、同期の失敗の通知を出すかの判断。 */
class SyncFailureNotificationTest {
    private val now = Instant.parse("2026-10-26T05:00:00Z")

    @Test
    fun theThresholdIs24Hours() {
        assertEquals(24.hours, SyncWorker.NOTIFY_AFTER)
    }

    @Test
    fun notifiesWhenTheLastSuccessIs24HoursOrMoreAgo() {
        assertTrue(SyncWorker.shouldNotifyFailure(now - 24.hours, notified = false, now = now))
        assertTrue(SyncWorker.shouldNotifyFailure(now - 72.hours, notified = false, now = now))
    }

    @Test
    fun doesNotNotifyWithinTheFirst24Hours() {
        assertFalse(SyncWorker.shouldNotifyFailure(now - 24.hours + 1.minutes, notified = false, now = now))
    }

    /** 一度出したら、成功するまで（利用者が払っても）出し直さない。 */
    @Test
    fun doesNotNotifyAgainUntilASuccess() {
        assertFalse(SyncWorker.shouldNotifyFailure(now - 72.hours, notified = true, now = now))
    }

    /** 一度も成功していない（接続した直後の取り込み）ときは出さない。 */
    @Test
    fun doesNotNotifyBeforeTheFirstSuccess() {
        assertFalse(SyncWorker.shouldNotifyFailure(null, notified = false, now = now))
    }

    /** 出せたら通知を出し、出したことを記録する。2 回目は出し直さない。 */
    @Test
    fun aLongFailureIsNotifiedOnceAndRecorded() = runTest {
        val session = FakeSessionRepository(jellyfinReady(lastFetchedAt = now - 25.hours))
        val notifier = RecordingSyncFailureNotifier()

        assertTrue(SyncWorker.notifyFailureIfLong(SyncOutcome.Unreachable, session, notifier, now))
        assertEquals(listOf<UiText>(UiText.Res(R.string.sync_error_unreachable)), notifier.shown)
        assertTrue((session.state.value as SessionState.Ready).syncFailureNotified)

        assertFalse(SyncWorker.notifyFailureIfLong(SyncOutcome.Unreachable, session, notifier, now))
        assertEquals(1, notifier.shown.size)
    }

    /** 通知の許可が無くて出せなかったときは「出した」と記録しない。後で許可されたら次の失敗で出る。 */
    @Test
    fun aNotificationThatCouldNotBeShownIsNotRecorded() = runTest {
        val session = FakeSessionRepository(jellyfinReady(lastFetchedAt = now - 25.hours))
        val notifier = RecordingSyncFailureNotifier(allowed = false)

        assertFalse(SyncWorker.notifyFailureIfLong(SyncOutcome.Unreachable, session, notifier, now))
        assertFalse((session.state.value as SessionState.Ready).syncFailureNotified)

        notifier.allowed = true
        assertTrue(SyncWorker.notifyFailureIfLong(SyncOutcome.Unreachable, session, notifier, now))
        assertTrue((session.state.value as SessionState.Ready).syncFailureNotified)
    }
}
