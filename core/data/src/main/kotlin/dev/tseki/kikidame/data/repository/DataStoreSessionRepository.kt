package dev.tseki.kikidame.data.repository

import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.credentials
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.smb.FolderTreeFactory
import dev.tseki.kikidame.data.smb.SmbPaths
import dev.tseki.kikidame.domain.ConnectedSource
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
