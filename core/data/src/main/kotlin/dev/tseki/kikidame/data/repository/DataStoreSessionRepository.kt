package dev.tseki.kikidame.data.repository

import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.credentials
import dev.tseki.kikidame.data.session.SessionStore
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
) : SessionRepository {

    override val state: Flow<SessionState> = store.state

    override suspend fun signIn(serverUrl: String, userName: String, password: String) {
        val session = gateway.signIn(normalizeServerUrl(serverUrl), userName.trim(), password)
        store.saveSession(session)
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
        is SessionState.Ready -> s.session
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
