package dev.tseki.kikidame.ui.connect

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.ui.UiText
import org.junit.Test
import kotlin.test.assertEquals

/** 端末のフォルダの、画面に出す場所（PR #214 のマージ前の検証で足した）。 */
class DeviceFolderDisplayPathTest {
    private val separator = UiText.Plain(" / ")

    @Test
    fun internalStorageIsNamedAndFollowedByThePath() {
        val folder = DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3AKikidameTest%2FRadio", "Radio")
        assertEquals(
            UiText.Joined(
                listOf(UiText.Res(R.string.device_folder_internal_storage), UiText.Plain("KikidameTest"), UiText.Plain("Radio")),
                separator,
            ),
            folder.displayPath(),
        )
    }

    @Test
    fun otherVolumesShowTheirId() {
        val folder = DeviceFolder("content://com.android.externalstorage.documents/tree/1234-5678%3ARadio", "Radio")
        assertEquals(UiText.Joined(listOf(UiText.Plain("1234-5678"), UiText.Plain("Radio")), separator), folder.displayPath())
    }

    /** 文書 ID が想定の形でなければ、保存済みの表示名に戻す（保存済みの `name` は今までどおり使える）。 */
    @Test
    fun malformedIdFallsBackToTheSavedName() {
        val folder = DeviceFolder("content://com.android.externalstorage.documents/tree/Radio", "Radio")
        assertEquals(UiText.Plain("Radio"), folder.displayPath())
    }
}
