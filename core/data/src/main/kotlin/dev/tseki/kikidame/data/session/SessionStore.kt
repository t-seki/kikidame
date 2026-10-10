package dev.tseki.kikidame.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * セッションの永続化（Preferences DataStore）。取得元は同時に 1 つで、Jellyfin のキー（`server_url` など。
 * 以前のバージョンから変えていないので、更新の後も接続が残る。epic #195 の決定 12）か、SMB のキー（`smb_*`）のどちらかを持つ。
 * `smb_host` があれば SMB、無ければ Jellyfin とみなす。
 * Jellyfin のトークンと SMB のパスワードは [TokenCipher] で暗号化して置く。Jellyfin のパスワードは保存しない。
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
            it.removeSmb()
            it.remove(LIBRARY_ID)
            it.remove(LIBRARY_NAME)
            it.remove(LAST_FETCHED_AT)
            it.remove(LAST_ATTEMPTED_AT)
        }
    }

    /** SMB の接続を保存する。Jellyfin の接続は消す（取得元は同時に 1 つ）。 */
    suspend fun saveSmb(connection: SmbConnection) {
        val encrypted = if (connection.guest) null else cipher.encrypt(connection.password)
        dataStore.edit {
            it.removeJellyfin()
            it[SMB_HOST] = connection.host
            it[SMB_SHARE] = connection.share
            it[SMB_PATH] = connection.path
            it[SMB_USER] = connection.userName
            it[SMB_GUEST] = connection.guest
            if (encrypted != null) it[SMB_PASSWORD] = encrypted else it.remove(SMB_PASSWORD)
            it.remove(LAST_FETCHED_AT)
            it.remove(LAST_ATTEMPTED_AT)
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

    /** 全走査を試みた時刻。サーバに問い合わせる直前に、成功・失敗を問わず記録する（#135）。 */
    suspend fun saveLastAttemptedAt(at: Instant) {
        dataStore.edit { it[LAST_ATTEMPTED_AT] = at.toEpochMilliseconds() }
    }

    /** ログアウト。サーバ URL とユーザー名（SMB はホスト・共有名・パス・ユーザー名）は次回の入力補助として残す。 */
    suspend fun clearCredentials() {
        dataStore.edit {
            it.remove(USER_ID)
            it.remove(TOKEN)
            it.remove(SMB_PASSWORD)
            it.remove(SMB_GUEST)
            it.remove(LIBRARY_ID)
            it.remove(LIBRARY_NAME)
            it.remove(LAST_FETCHED_AT)
            it.remove(LAST_ATTEMPTED_AT)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun MutablePreferences.removeJellyfin() {
        remove(SERVER_URL)
        remove(USER_NAME)
        remove(USER_ID)
        remove(TOKEN)
        remove(LIBRARY_ID)
        remove(LIBRARY_NAME)
    }

    private fun MutablePreferences.removeSmb() {
        remove(SMB_HOST)
        remove(SMB_SHARE)
        remove(SMB_PATH)
        remove(SMB_USER)
        remove(SMB_PASSWORD)
        remove(SMB_GUEST)
    }

    private fun Preferences.toState(): SessionState =
        if (this[SMB_HOST] != null) toSmbState() else toJellyfinState()

    private fun Preferences.toSmbState(): SessionState {
        val host = this[SMB_HOST]
        val share = this[SMB_SHARE]
        val userName = this[SMB_USER].orEmpty()
        val path = this[SMB_PATH].orEmpty()
        val guest = this[SMB_GUEST] ?: false
        val password = this[SMB_PASSWORD]?.let { stored -> runCatching { cipher.decrypt(stored) }.getOrNull() }
        if (host == null || share == null) return SessionState.SignedOut(null, null)
        // ログアウトするとパスワードが消える（ゲストはパスワードが要らないので、ゲストのままなら Ready）
        if (!guest && password == null) {
            return SessionState.SignedOut(null, null, lastSmb = SmbConnection(host, share, path, userName, password = ""))
        }
        return SessionState.Ready(
            source = ConnectedSource.Smb(SmbConnection(host, share, path, userName, password.orEmpty(), guest)),
            lastFetchedAt = this[LAST_FETCHED_AT]?.let(Instant::fromEpochMilliseconds),
            lastAttemptedAt = this[LAST_ATTEMPTED_AT]?.let(Instant::fromEpochMilliseconds),
        )
    }

    private fun Preferences.toJellyfinState(): SessionState {
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
            source = ConnectedSource.Jellyfin(session, SelectedLibrary(SourceItemId(libraryId), libraryName)),
            lastFetchedAt = this[LAST_FETCHED_AT]?.let(Instant::fromEpochMilliseconds),
            lastAttemptedAt = this[LAST_ATTEMPTED_AT]?.let(Instant::fromEpochMilliseconds),
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
        val LAST_ATTEMPTED_AT = longPreferencesKey("last_attempted_at")
        val SMB_HOST = stringPreferencesKey("smb_host")
        val SMB_SHARE = stringPreferencesKey("smb_share")
        val SMB_PATH = stringPreferencesKey("smb_path")
        val SMB_USER = stringPreferencesKey("smb_user")
        val SMB_PASSWORD = stringPreferencesKey("smb_password")
        val SMB_GUEST = booleanPreferencesKey("smb_guest")
    }
}
