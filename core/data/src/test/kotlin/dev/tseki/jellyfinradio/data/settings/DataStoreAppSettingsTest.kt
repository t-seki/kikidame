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
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }
    private var dataStore = lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    private val settings get() = DataStoreAppSettings(dataStore.value)
    @After
    fun tearDown() = scope.cancel()
    /** 最初の DataStore を閉じてから同じファイルで作り直す（アプリの再起動に相当。同じファイルに 2 つ同時には開けない）。 */
    private fun restart() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    }
    @Test
    fun themeModeDefaultsToSystemAndPersists() = runTest {
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        settings.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        restart()
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
    }
    /** 保存されている値が読めない（将来の項目・壊れた値）ときは「システム」に戻るだけで、読み出しは失敗しない。 */
    @Test
    fun unknownStoredValueFallsBackToSystem() = runTest {
        dataStore.value.edit { it[stringPreferencesKey("theme_mode")] = "AMOLED" }
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
    }
}
