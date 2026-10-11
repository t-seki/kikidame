package dev.tseki.kikidame.testing

import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.ProgramListTab
import dev.tseki.kikidame.domain.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/** 設定をメモリに持つだけの [AppSettingsRepository]。 */
class FakeAppSettingsRepository(wifiOnly: Boolean = true) : AppSettingsRepository {
    override val wifiOnly = MutableStateFlow(wifiOnly)
    override suspend fun setWifiOnly(value: Boolean) {
        wifiOnly.value = value
    }
    override val playbackSpeed = MutableStateFlow(1.0f)
    override suspend fun setPlaybackSpeed(value: Float) {
        playbackSpeed.value = value
    }
    override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    override suspend fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
    }
    override val programListTab = MutableStateFlow(ProgramListTab.CONTINUE_LISTENING)
    override suspend fun setProgramListTab(value: ProgramListTab) {
        programListTab.value = value
    }
}
