package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 端末のフォルダとして受け付けるツリーの URI と、取得元が端末のフォルダかの判定。 */
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

    /** 画面に出す場所は、ツリーの文書 ID（`<ボリューム>:<パス>`）から作る。 */
    @Test
    fun locationComesFromTheTreeDocumentId() {
        assertEquals(
            DeviceFolderLocation("primary", listOf("KikidameTest", "Radio")),
            DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3AKikidameTest%2FRadio", "Radio").location,
        )
        assertTrue(DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio", "Radio").location!!.isPrimary)
        // SD カード（ボリュームの ID の形は実機では未確認）
        assertEquals(
            DeviceFolderLocation("1234-5678", listOf("Radio")),
            DeviceFolder("content://com.android.externalstorage.documents/tree/1234-5678%3ARadio", "Radio").location,
        )
        // ボリュームの根
        assertEquals(DeviceFolderLocation("1234-5678", emptyList()), DeviceFolder("content://com.android.externalstorage.documents/tree/1234-5678%3A", "SD").location)
        // 日本語と「+」も戻る
        assertEquals(
            DeviceFolderLocation("primary", listOf("録音", "a+b")),
            DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3A%E9%8C%B2%E9%9F%B3%2Fa%2Bb", "a+b").location,
        )
    }

    /** 形が崩れていたら null（呼び出し側は保存済みの表示名に戻す）。 */
    @Test
    fun malformedTreeUriHasNoLocation() {
        assertNull(DeviceFolder("content://com.android.externalstorage.documents/tree/Radio", "Radio").location) // 「:」が無い
        assertNull(DeviceFolder("content://com.android.externalstorage.documents/tree/%3ARadio", "Radio").location) // ボリュームが空
        assertNull(DeviceFolder("content://com.android.externalstorage.documents/document/primary%3ARadio", "Radio").location) // ツリーでない
        assertNull(DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio/document/primary%3ARadio%2Fa", "a").location)
        assertNull(DeviceFolder("content://com.android.externalstorage.documents/tree/primary%ZZ", "Radio").location) // 符号が壊れている
        assertNull(DeviceFolder("", "Radio").location)
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
