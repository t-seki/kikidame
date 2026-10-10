package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 端末のフォルダ（#199）として受け付けるツリーの URI と、取得元が端末のフォルダかの判定。 */
class DeviceFolderTest {
    @Test
    fun internalStorageAndSdCardTreesAreDeviceStorage() {
        assertTrue(DeviceFolder.isDeviceStorage("content://com.android.externalstorage.documents/tree/primary%3ARadio"))
        // SD カードはボリュームの ID が付く（この形は推測で、実機では未確認）
        assertTrue(DeviceFolder.isDeviceStorage("content://com.android.externalstorage.documents/tree/1234-5678%3ARadio"))
    }

    @Test
    fun otherProvidersAreNotDeviceStorage() {
        assertFalse(DeviceFolder.isDeviceStorage("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc"))
        assertFalse(DeviceFolder.isDeviceStorage("content://com.android.providers.downloads.documents/tree/downloads"))
        assertFalse(DeviceFolder.isDeviceStorage("file:///sdcard/Radio"))
        assertFalse(DeviceFolder.isDeviceStorage(""))
        assertFalse(DeviceFolder.isDeviceStorage("not a uri at all %%"))
    }

    @Test
    fun isDeviceFolderLooksAtTheSourceAndTheSignedOutFolder() {
        val folder = DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio", "Radio")
        assertTrue(SessionState.Ready(ConnectedSource.Device(folder), null).isDeviceFolder)
        assertTrue(SessionState.SignedOut(null, null, lastDeviceFolder = folder).isDeviceFolder)
        assertFalse(SessionState.Ready(ConnectedSource.Smb(SmbConnection("nas", "s", "", "u", "p")), null).isDeviceFolder)
        assertFalse(SessionState.SignedOut(null, null).isDeviceFolder)
    }
}
