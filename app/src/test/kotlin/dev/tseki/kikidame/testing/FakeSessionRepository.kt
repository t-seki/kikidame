package dev.tseki.kikidame.testing

import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SyncAttempt
import dev.tseki.kikidame.domain.SyncOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/** テストで使う端末のフォルダの tree URI。 */
const val DEVICE_TREE = "content://com.android.externalstorage.documents/tree/primary%3ARadio"

/** Jellyfin に接続し、ライブラリを選んだ後の状態。 */
fun jellyfinReady(lastFetchedAt: Instant? = null, lastAttemptedAt: Instant? = null) = SessionState.Ready(
    source = ConnectedSource.Jellyfin(
        Session("https://jellyfin.lab.example/", "alice", "user-alice", "token"),
        SelectedLibrary(SourceItemId("lib-1"), "Radio"),
    ),
    lastFetchedAt = lastFetchedAt,
    lastAttemptedAt = lastAttemptedAt,
)

/** 端末のフォルダ（[DEVICE_TREE]）に接続した後の状態。 */
fun deviceFolderReady() = SessionState.Ready(ConnectedSource.Device(DeviceFolder(DEVICE_TREE, "Radio")), lastFetchedAt = null)

/**
 * 状態を [state] に持つだけの [SessionRepository]。[signInError] があれば [signIn] が投げる。
 * [signOut] は呼ばれたことを [signedOut] に残し、状態をログアウトにする。接続・ライブラリの選択は何もしない。
 */
class FakeSessionRepository(initial: SessionState = jellyfinReady()) : SessionRepository {
    override val state = MutableStateFlow(initial)
    var signInError: Exception? = null
    var signedOut = false
        private set
    override suspend fun signIn(serverUrl: String, userName: String, password: String) {
        signInError?.let { throw it }
    }
    override suspend fun connectSharedFolder(connection: SmbConnection) = Unit
    override suspend fun connectDeviceFolder(treeUri: String) = Unit
    override suspend fun listLibraries(): List<LibraryView> = emptyList()
    override suspend fun selectLibrary(library: LibraryView) = Unit
    override suspend fun signOut() {
        signedOut = true
        state.value = SessionState.SignedOut(null, null)
    }

    /** 記録した全走査の最後の試み（記録した順）。 */
    val syncAttempts = mutableListOf<SyncAttempt>()

    /** 接続済みなら状態にも反映する（成功なら通知を出した記録も消す。`SessionStore` と同じ）。 */
    override suspend fun recordSyncAttempt(attempt: SyncAttempt) {
        syncAttempts += attempt
        val ready = state.value as? SessionState.Ready ?: return
        state.value = ready.copy(
            lastSyncAttempt = attempt,
            syncFailureNotified = ready.syncFailureNotified && attempt.outcome != SyncOutcome.Succeeded,
        )
    }

    override suspend fun markSyncFailureNotified() {
        val ready = state.value as? SessionState.Ready ?: return
        state.value = ready.copy(syncFailureNotified = true)
    }
}
