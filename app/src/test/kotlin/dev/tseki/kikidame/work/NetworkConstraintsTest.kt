package dev.tseki.kikidame.work

import androidx.work.NetworkType
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceItemId
import org.junit.Test
import kotlin.test.assertEquals

/** 同期とダウンロードの Worker のネットワークの制約。端末のフォルダにはネットワークの条件をかけない。 */
class NetworkConstraintsTest {
    private val device = SessionState.Ready(
        ConnectedSource.Device(DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio", "Radio")),
        null,
    )
    private val smb = SessionState.Ready(ConnectedSource.Smb(SmbConnection("nas", "share", "", "u", "p")), null)
    private val jellyfin = SessionState.Ready(
        ConnectedSource.Jellyfin(
            Session("https://jellyfin.lab.example/", "alice", "user-alice", "token"),
            SelectedLibrary(SourceItemId("lib-1"), "Radio"),
        ),
        null,
    )

    @Test
    fun requiredNetworkTypeFollowsWifiOnlyExceptForADeviceFolder() {
        assertEquals(NetworkType.NOT_REQUIRED, requiredNetworkType(wifiOnly = true, device))
        assertEquals(NetworkType.UNMETERED, requiredNetworkType(wifiOnly = true, smb))
        assertEquals(NetworkType.UNMETERED, requiredNetworkType(wifiOnly = true, jellyfin))
        assertEquals(NetworkType.CONNECTED, requiredNetworkType(wifiOnly = false, smb))
        assertEquals(NetworkType.NOT_REQUIRED, requiredNetworkType(wifiOnly = false, device))
    }

    @Test
    fun networkConstraintsCarryTheRequiredType() {
        assertEquals(NetworkType.UNMETERED, networkConstraints(wifiOnly = true, jellyfin).requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, networkConstraints(wifiOnly = true, device).requiredNetworkType)
    }
}
