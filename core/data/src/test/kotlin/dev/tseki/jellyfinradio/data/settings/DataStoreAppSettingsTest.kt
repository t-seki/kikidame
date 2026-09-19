package dev.tseki.jellyfinradio.data.settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.domain.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
@RunWith(AndroidJUnit4::class)
class DataStoreAppSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") } }
    private val settings by lazy { DataStoreAppSettings(dataStore) }
    @After
    fun tearDown() = scope.cancel()
    @Test
    fun themeModeDefaultsToSystemAndPersists() = runTest {
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        settings.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        // 別のインスタンスで読み直しても残る（アプリの再起動に相当）
        assertEquals(ThemeMode.DARK, DataStoreAppSettings(dataStore).themeMode.first())
    }
    /** 保存されている値が読めない（将来の項目・壊れた値）ときは「システム」に戻るだけで、読み出しは失敗しない。 */
    @Test
    fun unknownStoredValueFallsBackToSystem() = runTest {
        dataStore.edit { it[stringPreferencesKey("theme_mode")] = "AMOLED" }
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
    }
}
