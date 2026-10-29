package dev.tseki.kikidame.ui.settings

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** 電池の最適化を外す OS の確認の Intent。action と `package:` の URI を見る（Robolectric）。 */
@RunWith(AndroidJUnit4::class)
class BatteryOptimizationTest {
    @Test
    fun intentAsksTheOsToIgnoreOptimizationsForThePackage() {
        val intent = ignoreBatteryOptimizationsIntent("dev.tseki.kikidame")
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:dev.tseki.kikidame", intent.data.toString())
    }
}
