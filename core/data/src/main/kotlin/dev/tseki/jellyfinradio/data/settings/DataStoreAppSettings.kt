package dev.tseki.jellyfinradio.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.PlaybackSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * アプリ全体の設定（セッションとは別の DataStore `settings`）。「Wi-Fi のみ」（handoff）と倍速（#35）。
 * 1 つの DataStore なので、どれかを書くと全部の Flow が再度流れる。値が変わったときだけ下流に伝える。
 */
class DataStoreAppSettings(private val dataStore: DataStore<Preferences>) : AppSettingsRepository {
    override val wifiOnly: Flow<Boolean> = dataStore.data.map { it[WIFI_ONLY] ?: DEFAULT_WIFI_ONLY }.distinctUntilChanged()

    override suspend fun setWifiOnly(value: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = value }
    }

    override val playbackSpeed: Flow<Float> = dataStore.data.map { PlaybackSpeed.normalize(it[PLAYBACK_SPEED]) }.distinctUntilChanged()

    override suspend fun setPlaybackSpeed(value: Float) {
        dataStore.edit { it[PLAYBACK_SPEED] = PlaybackSpeed.normalize(value) }
    }

    companion object {
        const val DEFAULT_WIFI_ONLY = true
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    }
}
