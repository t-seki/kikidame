package dev.tseki.kikidame.ui.settings

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 電池の最適化の一覧の画面を開く Intent。権限の要らない action で、URI を付けない（Robolectric）。 */
@RunWith(AndroidJUnit4::class)
class BatteryOptimizationTest {
    @Test
    fun intentOpensTheBatteryOptimizationList() {
        val intent = batteryOptimizationSettingsIntent()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertNull(intent.data)
    }
}
