package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 取得元（[SessionState]）だけで決まるネットワークの規則。「Wi-Fi のみ」が効くか（#199 の決定 4）、ローカルネットワークを使うか（決定 7）。 */
class NetworkRulesTest {
    private val folder = DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio", "Radio")
    private val device = SessionState.Ready(ConnectedSource.Device(folder), null)
    private val smb = SessionState.Ready(ConnectedSource.Smb(SmbConnection("nas", "share", "", "u", "p")), null)
    private val jellyfinSession = Session("https://jellyfin.lab.example/", "alice", "user-alice", "token")
    private val jellyfin = SessionState.Ready(
        ConnectedSource.Jellyfin(jellyfinSession, SelectedLibrary(SourceItemId("lib-1"), "Radio")),
        null,
    )

    @Test
    fun wifiOnlyDoesNotApplyToADeviceFolder() {
        assertFalse(wifiOnlyApplies(wifiOnly = true, device))
        assertFalse(wifiOnlyApplies(wifiOnly = true, SessionState.SignedOut(null, null, lastDeviceFolder = folder)))
        assertTrue(wifiOnlyApplies(wifiOnly = true, jellyfin))
        assertTrue(wifiOnlyApplies(wifiOnly = true, smb))
        assertFalse(wifiOnlyApplies(wifiOnly = false, jellyfin))
    }

    @Test
    fun localNetworkIsUsedByJellyfinAndSmbButNotByADeviceFolder() {
        assertTrue(jellyfin.usesLocalNetwork)
        assertTrue(smb.usesLocalNetwork)
        assertFalse(device.usesLocalNetwork)
        assertTrue(SessionState.NeedsLibrary(jellyfinSession).usesLocalNetwork)
    }

    @Test
    fun signedOutUsesLocalNetworkOnlyIfTheLastSourceDid() {
        assertTrue(SessionState.SignedOut("https://jellyfin.lab.example/", "alice").usesLocalNetwork)
        assertTrue(SessionState.SignedOut(null, null, lastSmb = SmbConnection("nas", "share", "", "u", "")).usesLocalNetwork)
        assertFalse(SessionState.SignedOut(null, null, lastDeviceFolder = folder).usesLocalNetwork)
        assertFalse(SessionState.SignedOut(null, null).usesLocalNetwork)
    }
}
