package dev.tseki.jellyfinradio.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** アプリ全体の設定（セッションとは別の DataStore）。「Wi-Fi のみ」は番組ではなくここに持つ（handoff）。 */
class DataStoreAppSettings(private val dataStore: DataStore<Preferences>) : AppSettingsRepository {
    override val wifiOnly: Flow<Boolean> = dataStore.data.map { it[WIFI_ONLY] ?: DEFAULT_WIFI_ONLY }

    override suspend fun setWifiOnly(value: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = value }
    }

    companion object {
        const val DEFAULT_WIFI_ONLY = true
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    }
}
