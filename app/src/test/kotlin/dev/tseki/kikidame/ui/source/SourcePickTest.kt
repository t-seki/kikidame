package dev.tseki.kikidame.ui.source

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SMB の選択肢は debug 版だけ（#198 の追加の決定）。画面の既定値は `BuildConfig.DEBUG` を渡す。 */
class SourcePickTest {
    @Test
    fun smbIsOfferedOnlyInDebugBuilds() {
        assertTrue(isSmbSelectable(debugBuild = true))
        assertFalse(isSmbSelectable(debugBuild = false))
    }
}
