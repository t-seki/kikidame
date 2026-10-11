package dev.tseki.kikidame.data.repository

import android.util.Log
import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.credentials
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.device.DeviceDocuments
import dev.tseki.kikidame.data.sharedfolder.FolderTreeFactory
import dev.tseki.kikidame.data.smb.SmbPaths
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.UnsupportedFolderException
import dev.tseki.kikidame.domain.SmbConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreSessionRepository @Inject constructor(
    private val store: SessionStore,
    private val gateway: JellyfinGateway,
    private val trees: FolderTreeFactory,
    private val documents: DeviceDocuments,
) : SessionRepository {

    override val state: Flow<SessionState> = store.state

    override suspend fun signIn(serverUrl: String, userName: String, password: String) {
        val session = gateway.signIn(normalizeServerUrl(serverUrl), userName.trim(), password)
        store.saveSession(session)
    }

    override suspend fun connectSharedFolder(connection: SmbConnection) {
        val normalized = connection.copy(
            host = SmbPaths.normalizeHost(connection.host),
            share = connection.share.trim().trim('/', '\\'),
            path = SmbPaths.normalizeBase(connection.path),
            userName = connection.userName.trim(),
        )
        require(normalized.share.isNotEmpty()) { "share is empty" }
        // 共有の直下が読めるか。読めなければ保存しない
        withContext(Dispatchers.IO) {
            val tree = trees.open(normalized)
            try {
                tree.list("") ?: throw ServerException.Failed("shared folder not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                throw e
            } catch (e: Exception) {
                throw ServerException.Unreachable(e)
            } finally {
                tree.close()
            }
        }
        store.saveSmb(normalized)
    }

    override suspend fun connectDeviceFolder(treeUri: String) {
        if (!DeviceFolder.isDeviceStorage(treeUri)) throw UnsupportedFolderException(treeUri)
        val name = withContext(Dispatchers.IO) {
            try {
                documents.takePermission(treeUri)
                // フォルダの直下が読めるか。読めなければ保存しない
                trees.open(DeviceFolder(treeUri, name = "")).use { tree ->
                    tree.list("") ?: throw ServerException.Failed("folder not found")
                }
                val name = documents.query(treeUri, documents.rootDocumentId(treeUri))?.name.orEmpty()
                // 前に選んでいたフォルダ（アクセスが切れて選び直したときなど）の権限を手放す。失敗しても接続は続ける。
                // 保存（Ready になり画面が離れる）より前に済ませ、保存を最後の一手にする
                try {
                    documents.persistedTreeUris().filter { it != treeUri }.forEach { documents.releasePermission(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "release an old folder permission failed", e)
                }
                name
            } catch (e: Exception) {
                // 取った権限を手放す。手放すのに失敗しても、元の失敗を投げる
                runCatching { documents.releasePermission(treeUri) }
                when (e) {
                    is CancellationException, is ServerException -> throw e
                    is SecurityException -> throw ServerException.Unauthorized(e)
                    else -> throw ServerException.Failed(e.message ?: e::class.simpleName.orEmpty(), e)
                }
            }
        }
        store.saveDeviceFolder(DeviceFolder(treeUri, name))
    }

    override suspend fun listLibraries(): List<LibraryView> =
        gateway.listLibraries(requireSession().credentials())

    override suspend fun selectLibrary(library: LibraryView) {
        require(library.isMusic) { "only music libraries can be selected" }
        store.saveLibrary(SelectedLibrary(library.id, library.name))
    }

    override suspend fun signOut() = store.clearCredentials()

    private suspend fun requireSession(): Session = when (val s = store.current()) {
        is SessionState.NeedsLibrary -> s.session
        is SessionState.Ready -> (s.source as? ConnectedSource.Jellyfin)?.session ?: throw ServerException.Unauthorized()
        is SessionState.SignedOut -> throw ServerException.Unauthorized()
    }

    companion object {
        private const val TAG = "SessionRepository"

        /** スキーム省略は `https://` を補う。平文 HTTP は受け付けない。末尾のスラッシュを揃える。 */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "server url is empty" }
            val withScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) ->
                    throw IllegalArgumentException("plain http is not supported")
                else -> "https://$trimmed"
            }
            return "$withScheme/"
        }
    }
}
