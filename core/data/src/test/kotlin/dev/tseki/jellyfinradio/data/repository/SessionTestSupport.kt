package dev.tseki.jellyfinradio.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.tseki.jellyfinradio.data.session.SessionStore
import dev.tseki.jellyfinradio.data.session.TokenCipher
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** 暗号化の代わりに印を付けるだけ。復号できたかはテストで見える。 */
class FakeTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String = "enc[$plain]"
    override fun decrypt(stored: String): String = stored.removePrefix("enc[").removeSuffix("]")
}

fun testDataStore(dir: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(dir, "session.preferences_pb") }

fun testSessionStore(dir: File, scope: CoroutineScope): SessionStore =
    SessionStore(testDataStore(dir, scope), FakeTokenCipher())
