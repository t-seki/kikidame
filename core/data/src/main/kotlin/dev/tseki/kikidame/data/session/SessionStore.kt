package dev.tseki.kikidame.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SyncAttempt
import dev.tseki.kikidame.domain.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * セッションの永続化（Preferences DataStore）。取得元は同時に 1 つで、Jellyfin のキー（`server_url` など。
 * 以前のバージョンから変えていないので、更新の後も接続が残る。epic #195 の決定 12）、SMB のキー（`smb_*`）、
 * 端末のフォルダのキー（`device_*`、#199）のどれか 1 つを持つ。`smb_host` があれば SMB、`device_tree_uri` があれば端末のフォルダ、
 * どちらも無ければ Jellyfin とみなす。
 * Jellyfin のトークンと SMB のパスワードは [CredentialCipher] で暗号化して置く。Jellyfin のパスワードは保存しない。
 */
class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher,
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
            it.removeDevice()
            it.remove(LIBRARY_ID)
            it.remove(LIBRARY_NAME)
            it.removeSyncTimes()
        }
    }

    /** SMB の接続を保存する。Jellyfin の接続は消す（取得元は同時に 1 つ）。 */
    suspend fun saveSmb(connection: SmbConnection) {
        val encrypted = if (connection.guest) null else cipher.encrypt(connection.password)
        dataStore.edit {
            it.removeJellyfin()
            it.removeDevice()
            it[SMB_HOST] = connection.host
            it[SMB_SHARE] = connection.share
            it[SMB_PATH] = connection.path
            it[SMB_USER] = connection.userName
            it[SMB_GUEST] = connection.guest
            it.remove(SMB_SIGNED_OUT)
            if (encrypted != null) it[SMB_PASSWORD] = encrypted else it.remove(SMB_PASSWORD)
            it.removeSyncTimes()
        }
    }

    /** 端末のフォルダを保存する（#199）。Jellyfin と SMB の接続は消す（取得元は同時に 1 つ）。 */
    suspend fun saveDeviceFolder(folder: DeviceFolder) {
        dataStore.edit {
            it.removeJellyfin()
            it.removeSmb()
            it[DEVICE_TREE_URI] = folder.treeUri
            it[DEVICE_NAME] = folder.name
            it.remove(DEVICE_SIGNED_OUT)
            it.removeSyncTimes()
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

    /**
     * 全走査の最後の試みの結果と時刻（#346）。[LAST_ATTEMPTED_AT] とは別のキーに置く。成功なら、同期の失敗の通知を出した記録も消す。
     */
    suspend fun saveSyncAttempt(attempt: SyncAttempt) {
        val (kind, detail) = when (val outcome = attempt.outcome) {
            SyncOutcome.Succeeded -> OUTCOME_SUCCEEDED to null
            SyncOutcome.SkippedNotOnWifi -> OUTCOME_NOT_ON_WIFI to null
            SyncOutcome.Unreachable -> OUTCOME_UNREACHABLE to null
            is SyncOutcome.FetchFailed -> OUTCOME_FETCH_FAILED to outcome.message
            is SyncOutcome.ImportFailed -> OUTCOME_IMPORT_FAILED to outcome.exceptionName
        }
        dataStore.edit {
            it[SYNC_ATTEMPT_AT] = attempt.at.toEpochMilliseconds()
            it[SYNC_ATTEMPT_OUTCOME] = kind
            if (detail != null) it[SYNC_ATTEMPT_DETAIL] = detail else it.remove(SYNC_ATTEMPT_DETAIL)
            if (attempt.outcome == SyncOutcome.Succeeded) it.remove(SYNC_FAILURE_NOTIFIED)
        }
    }

    /** 同期の失敗の通知を出した記録（#346）。全走査が成功したとき（[saveSyncAttempt]）と、同期の時刻を消すときに消える。 */
    suspend fun saveSyncFailureNotified() {
        dataStore.edit { it[SYNC_FAILURE_NOTIFIED] = true }
    }

    /**
     * ログアウト。サーバ URL とユーザー名（SMB はホスト・共有名・パス・ユーザー名）は次回の入力補助として残す。
     * 端末のフォルダは、URI と名前を残してログアウトの印を立てる（アクセスが切れたフォルダを選び直させる。#199）。
     */
    suspend fun clearCredentials() {
        dataStore.edit {
            it.remove(USER_ID)
            it.remove(TOKEN)
            it.remove(SMB_PASSWORD)
            // ゲスト接続はパスワードが無いので、ログアウトした印を別に置く。ゲストかどうかはホストなどと同じく次回の入力補助として残す
            if (it[SMB_HOST] != null) it[SMB_SIGNED_OUT] = true
            if (it[DEVICE_TREE_URI] != null) it[DEVICE_SIGNED_OUT] = true
            it.remove(LIBRARY_ID)
            it.remove(LIBRARY_NAME)
            it.removeSyncTimes()
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
        remove(SMB_SIGNED_OUT)
    }

    private fun MutablePreferences.removeDevice() {
        remove(DEVICE_TREE_URI)
        remove(DEVICE_NAME)
        remove(DEVICE_SIGNED_OUT)
    }

    /**
     * 取得元が変わったときとログアウトのときに、同期の時刻（最終同期・最終の試み）を消す。全走査の最後の試みの結果と、
     * 同期の失敗の通知を出した記録も一緒に消す（#346 の決定 6）。
     */
    private fun MutablePreferences.removeSyncTimes() {
        remove(LAST_FETCHED_AT)
        remove(LAST_ATTEMPTED_AT)
        remove(SYNC_ATTEMPT_AT)
        remove(SYNC_ATTEMPT_OUTCOME)
        remove(SYNC_ATTEMPT_DETAIL)
        remove(SYNC_FAILURE_NOTIFIED)
    }

    /** 全走査の最後の試み。時刻か結果が無い、結果の値を読めない（新しい版で足した値を古い版が読んだなど）ときは null。 */
    private fun Preferences.toSyncAttempt(): SyncAttempt? {
        val at = this[SYNC_ATTEMPT_AT]?.let(Instant::fromEpochMilliseconds) ?: return null
        val detail = this[SYNC_ATTEMPT_DETAIL].orEmpty()
        val outcome = when (this[SYNC_ATTEMPT_OUTCOME]) {
            OUTCOME_SUCCEEDED -> SyncOutcome.Succeeded
            OUTCOME_NOT_ON_WIFI -> SyncOutcome.SkippedNotOnWifi
            OUTCOME_UNREACHABLE -> SyncOutcome.Unreachable
            OUTCOME_FETCH_FAILED -> SyncOutcome.FetchFailed(detail)
            OUTCOME_IMPORT_FAILED -> SyncOutcome.ImportFailed(detail)
            else -> return null
        }
        return SyncAttempt(at, outcome)
    }

    /** 接続済みの状態。同期の時刻は取得元によらず同じキーから読む。 */
    private fun Preferences.toReady(source: ConnectedSource): SessionState.Ready = SessionState.Ready(
        source = source,
        lastFetchedAt = this[LAST_FETCHED_AT]?.let(Instant::fromEpochMilliseconds),
        lastAttemptedAt = this[LAST_ATTEMPTED_AT]?.let(Instant::fromEpochMilliseconds),
        lastSyncAttempt = toSyncAttempt(),
        syncFailureNotified = this[SYNC_FAILURE_NOTIFIED] ?: false,
    )

    /** 暗号化して置いた値を戻す。無いとき・復号できないとき（鍵が変わったなど）は null。 */
    private fun decryptOrNull(stored: String?): String? = stored?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    private fun Preferences.toState(): SessionState = when {
        this[SMB_HOST] != null -> toSmbState()
        this[DEVICE_TREE_URI] != null -> toDeviceState()
        else -> toJellyfinState()
    }

    private fun Preferences.toDeviceState(): SessionState {
        val folder = DeviceFolder(treeUri = this[DEVICE_TREE_URI].orEmpty(), name = this[DEVICE_NAME].orEmpty())
        if (this[DEVICE_SIGNED_OUT] == true) return SessionState.SignedOut(null, null, lastDeviceFolder = folder)
        return toReady(ConnectedSource.Device(folder))
    }

    private fun Preferences.toSmbState(): SessionState {
        val host = this[SMB_HOST]
        val share = this[SMB_SHARE]
        val userName = this[SMB_USER].orEmpty()
        val path = this[SMB_PATH].orEmpty()
        val guest = this[SMB_GUEST] ?: false
        val password = decryptOrNull(this[SMB_PASSWORD])
        if (host == null || share == null) return SessionState.SignedOut(null, null)
        // ログアウトするとパスワードが消え、印が立つ（ゲストはパスワードが要らないので、印で見分ける）
        if (this[SMB_SIGNED_OUT] == true || (!guest && password == null)) {
            return SessionState.SignedOut(null, null, lastSmb = SmbConnection(host, share, path, userName, password = "", guest = guest))
        }
        return toReady(ConnectedSource.Smb(SmbConnection(host, share, path, userName, password.orEmpty(), guest)))
    }

    private fun Preferences.toJellyfinState(): SessionState {
        val serverUrl = this[SERVER_URL]
        val userName = this[USER_NAME]
        val userId = this[USER_ID]
        val token = decryptOrNull(this[TOKEN])
        if (serverUrl == null || userName == null || userId == null || token == null) {
            return SessionState.SignedOut(lastServerUrl = serverUrl, lastUserName = userName)
        }
        val session = Session(serverUrl, userName, userId, token)
        val libraryId = this[LIBRARY_ID]
        val libraryName = this[LIBRARY_NAME]
        if (libraryId == null || libraryName == null) return SessionState.NeedsLibrary(session)
        return toReady(ConnectedSource.Jellyfin(session, SelectedLibrary(SourceItemId(libraryId), libraryName)))
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
        val SYNC_ATTEMPT_AT = longPreferencesKey("sync_attempt_at")
        val SYNC_ATTEMPT_OUTCOME = stringPreferencesKey("sync_attempt_outcome")
        val SYNC_ATTEMPT_DETAIL = stringPreferencesKey("sync_attempt_detail")
        val SYNC_FAILURE_NOTIFIED = booleanPreferencesKey("sync_failure_notified")
        const val OUTCOME_SUCCEEDED = "succeeded"
        const val OUTCOME_NOT_ON_WIFI = "not_on_wifi"
        const val OUTCOME_UNREACHABLE = "unreachable"
        const val OUTCOME_FETCH_FAILED = "fetch_failed"
        const val OUTCOME_IMPORT_FAILED = "import_failed"
        val SMB_HOST = stringPreferencesKey("smb_host")
        val SMB_SHARE = stringPreferencesKey("smb_share")
        val SMB_PATH = stringPreferencesKey("smb_path")
        val SMB_USER = stringPreferencesKey("smb_user")
        val SMB_PASSWORD = stringPreferencesKey("smb_password")
        val SMB_GUEST = booleanPreferencesKey("smb_guest")
        val SMB_SIGNED_OUT = booleanPreferencesKey("smb_signed_out")
        val DEVICE_TREE_URI = stringPreferencesKey("device_tree_uri")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DEVICE_SIGNED_OUT = booleanPreferencesKey("device_signed_out")
    }
}
