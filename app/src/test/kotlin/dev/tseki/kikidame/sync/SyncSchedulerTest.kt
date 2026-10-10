package dev.tseki.kikidame.sync

import androidx.work.NetworkType
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 起動時同期を積むかの判定（#135）。前回同期と前回の試みの新しい方から 1 時間。「Wi-Fi のみ」が効くか（#199）。 */
class SyncSchedulerTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun ready(lastFetchedAt: Instant?, lastAttemptedAt: Instant?) = SessionState.Ready(
        source = ConnectedSource.Jellyfin(
            Session("https://jellyfin.lab.example/", "alice", "user-alice", "token"),
            SelectedLibrary(SourceItemId("lib-1"), "Radio"),
        ),
        lastFetchedAt = lastFetchedAt,
        lastAttemptedAt = lastAttemptedAt,
    )

    @Test
    fun neverSyncedIsStale() {
        assertTrue(SyncScheduler.isStale(ready(null, null), now))
    }

    @Test
    fun aRecentSuccessIsNotStale() {
        assertFalse(SyncScheduler.isStale(ready(now - 30.minutes, now - 30.minutes), now))
        assertTrue(SyncScheduler.isStale(ready(now - 60.minutes, now - 60.minutes), now))
    }

    /** 到達できずに失敗した直後は積まない。1 時間たてば積む。 */
    @Test
    fun aRecentFailedAttemptIsNotStale() {
        val lastSuccess = now - 600.minutes
        assertFalse(SyncScheduler.isStale(ready(lastSuccess, now - 1.minutes), now))
        assertFalse(SyncScheduler.isStale(ready(null, now - 59.minutes), now))
        assertTrue(SyncScheduler.isStale(ready(lastSuccess, now - 60.minutes), now))
    }

    /** 端末のフォルダにはネットワークの条件をかけない。Jellyfin と SMB は設定どおり（#199 の決定 4 と「決定の追記」）。 */
    @Test
    fun wifiOnlyDoesNotApplyToADeviceFolder() {
        val device = SessionState.Ready(
            ConnectedSource.Device(DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio", "Radio")),
            null,
        )
        val smb = SessionState.Ready(ConnectedSource.Smb(SmbConnection("nas", "share", "", "u", "p")), null)
        val jellyfin = ready(null, null)

        assertEquals(NetworkType.NOT_REQUIRED, SyncScheduler.requiredNetworkType(wifiOnly = true, device))
        assertEquals(NetworkType.UNMETERED, SyncScheduler.requiredNetworkType(wifiOnly = true, smb))
        assertEquals(NetworkType.UNMETERED, SyncScheduler.requiredNetworkType(wifiOnly = true, jellyfin))
        assertEquals(NetworkType.CONNECTED, SyncScheduler.requiredNetworkType(wifiOnly = false, smb))
        assertEquals(NetworkType.NOT_REQUIRED, SyncScheduler.requiredNetworkType(wifiOnly = false, device))
        assertFalse(SyncScheduler.wifiOnlyApplies(wifiOnly = true, device))
        assertTrue(SyncScheduler.wifiOnlyApplies(wifiOnly = true, jellyfin))
    }
}
