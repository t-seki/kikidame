package dev.tseki.kikidame.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * セッションの永続化（Preferences DataStore）。トークンだけ [TokenCipher] で暗号化して置く。
 * パスワードは保存しない。
 */
class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    val state: Flow<SessionState> = dataStore.data.map { it.toState() }

    suspend fun current(): SessionState = state.first()

    suspend fun saveSession(session: Session) {
        val encrypted = cipher.encrypt(session.accessToken)
        dataStore.edit {
            it[SERVER_URL] = session.serverUrl
            it[USER_NAME] = session.userName
            it[USER_ID] = session.userId
            it[TOKEN] = encrypted
            it.remove(LIBRARY_ID)
            it.remove(LIBRARY_NAME)
            it.remove(LAST_FETCHED_AT)
        }
    }

    suspend fun saveLibrary(library: SelectedLibrary) {
        dataStore.edit {
            it[LIBRARY_ID] = library.id.value
            it[LIBRARY_NAME] = library.name
        }
    }

    suspend fun saveLastFetchedAt(at: Instant) {
        dataStore.edit { it[LAST_FETCHED_AT] = at.toEpochMilliseconds() }
    }

    /** ログアウト。サーバ URL とユーザー名は次回の入力補助として残す。 */
    suspend fun clearCredentials() {
        dataStore.edit {
            it.remove(USER_ID)
            it.remove(TOKEN)
            it.remove(LIBRARY_ID)
            it.remove(LIBRARY_NAME)
            it.remove(LAST_FETCHED_AT)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toState(): SessionState {
        val serverUrl = this[SERVER_URL]
        val userName = this[USER_NAME]
        val userId = this[USER_ID]
        val token = this[TOKEN]?.let { stored -> runCatching { cipher.decrypt(stored) }.getOrNull() }
        if (serverUrl == null || userName == null || userId == null || token == null) {
            return SessionState.SignedOut(lastServerUrl = serverUrl, lastUserName = userName)
        }
        val session = Session(serverUrl, userName, userId, token)
        val libraryId = this[LIBRARY_ID]
        val libraryName = this[LIBRARY_NAME]
        if (libraryId == null || libraryName == null) return SessionState.NeedsLibrary(session)
        return SessionState.Ready(
            session = session,
            library = SelectedLibrary(ServerItemId(libraryId), libraryName),
            lastFetchedAt = this[LAST_FETCHED_AT]?.let(Instant::fromEpochMilliseconds),
        )
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ID = stringPreferencesKey("user_id")
        val TOKEN = stringPreferencesKey("access_token")
        val LIBRARY_ID = stringPreferencesKey("library_id")
        val LIBRARY_NAME = stringPreferencesKey("library_name")
        val LAST_FETCHED_AT = longPreferencesKey("last_fetched_at")
    }
}
