package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.SessionState
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class DataStoreSessionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeJellyfinGateway()
    private val store by lazy { testSessionStore(tmp.root, scope) }
    private val repo by lazy { DataStoreSessionRepository(store, gateway) }
    private val music = LibraryView(ServerItemId("lib-music"), "Radio", "music", isMusic = true)
    private val movies = LibraryView(ServerItemId("lib-movies"), "Movies", "movies", isMusic = false)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun signInThenSelectLibraryReachesReady() = runTest {
        assertIs<SessionState.SignedOut>(repo.state.first())

        repo.signIn("jellyfin.lab.example", "alice", "secret")
        val needsLibrary = assertIs<SessionState.NeedsLibrary>(repo.state.first())
        assertEquals("https://jellyfin.lab.example/", needsLibrary.session.serverUrl)
        assertEquals("token-alice", needsLibrary.session.accessToken, "token round-trips through the cipher")

        gateway.libraries = listOf(movies, music)
        assertEquals(listOf(movies, music), repo.listLibraries())
        repo.selectLibrary(music)
        val ready = assertIs<SessionState.Ready>(repo.state.first())
        assertEquals("Radio", ready.library.name)
        assertEquals(null, ready.lastFetchedAt)
    }

    @Test
    fun wrongPasswordIsUnauthorizedAndLeavesSignedOut() = runTest {
        assertFailsWith<ServerException.Unauthorized> { repo.signIn("jellyfin.lab.example", "alice", "nope") }
        assertIs<SessionState.SignedOut>(repo.state.first())
    }

    @Test
    fun nonMusicLibraryCannotBeSelected() = runTest {
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        assertFailsWith<IllegalArgumentException> { repo.selectLibrary(movies) }
    }

    @Test
    fun signOutKeepsServerAndUserForNextTime() = runTest {
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        repo.signOut()
        val signedOut = assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals("https://jellyfin.lab.example/", signedOut.lastServerUrl)
        assertEquals("alice", signedOut.lastUserName)
    }

    @Test
    fun serverUrlNormalisation() {
        val normalize = DataStoreSessionRepository::normalizeServerUrl
        assertEquals("https://jellyfin.lab.example/", normalize("jellyfin.lab.example"))
        assertEquals("https://jellyfin.lab.example/", normalize("  https://jellyfin.lab.example//  "))
        assertEquals("https://jellyfin.lab.example:8920/", normalize("jellyfin.lab.example:8920"))
        assertFailsWith<IllegalArgumentException> { normalize("http://jellyfin.lab.example") }
        assertFailsWith<IllegalArgumentException> { normalize("   ") }
    }
}
