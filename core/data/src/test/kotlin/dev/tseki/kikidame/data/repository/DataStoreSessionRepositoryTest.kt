package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.session.SessionStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tseki.kikidame.data.sharedfolder.FolderEntry
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SmbConnection
import java.io.IOException
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceItemId
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class DataStoreSessionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeJellyfinGateway()
    private val dataStore by lazy { testDataStore(tmp.root, scope) }
    private val store by lazy { SessionStore(dataStore, FakeTokenCipher()) }
    private val trees = FakeFolderTreeFactory()
    private val repo by lazy { DataStoreSessionRepository(store, gateway, trees) }
    private val smb = SmbConnection("nas.local", "recordings", "radio", "alice", "pw")
    private val music = LibraryView(SourceItemId("lib-music"), "Radio", "music", isMusic = true)
    private val movies = LibraryView(SourceItemId("lib-movies"), "Movies", "movies", isMusic = false)

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
        assertEquals("Radio", assertIs<ConnectedSource.Jellyfin>(ready.source).library.name)
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

    /** 全走査を試みた時刻（#135）は最終同期の時刻と一緒に、ログアウト・再ログインで消える。 */
    @Test
    fun signOutAndSignInClearTheSyncTimes() = runTest {
        val at = Instant.parse("2026-09-24T00:00:00Z")
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        store.saveLastFetchedAt(at)
        store.saveLastAttemptedAt(at)
        assertEquals(at, assertIs<SessionState.Ready>(repo.state.first()).lastAttemptedAt)

        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        val relogged = assertIs<SessionState.Ready>(repo.state.first())
        assertNull(relogged.lastFetchedAt)
        assertNull(relogged.lastAttemptedAt)

        store.saveLastFetchedAt(at)
        store.saveLastAttemptedAt(at)
        repo.signOut()
        val keys = dataStore.data.first().asMap().keys.map { it.name }
        assertFalse("last_fetched_at" in keys)
        assertFalse("last_attempted_at" in keys)
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

    /** 更新の前のバージョンが書いた DataStore（Jellyfin のキーだけ）が、更新の後も Jellyfin の接続として読める（epic #195 の決定 12）。 */
    @Test
    fun jellyfinKeysWrittenByThePreviousVersionStillReadAsReady() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("server_url")] = "https://jellyfin.lab.example/"
            it[stringPreferencesKey("user_name")] = "alice"
            it[stringPreferencesKey("user_id")] = "user-alice"
            it[stringPreferencesKey("access_token")] = "enc[token-alice]"
            it[stringPreferencesKey("library_id")] = "lib-music"
            it[stringPreferencesKey("library_name")] = "Radio"
            it[longPreferencesKey("last_fetched_at")] = 1_000L
            it[longPreferencesKey("last_attempted_at")] = 2_000L
        }
        val ready = assertIs<SessionState.Ready>(repo.state.first())
        val jellyfin = assertIs<ConnectedSource.Jellyfin>(ready.source)
        assertEquals("https://jellyfin.lab.example/", jellyfin.session.serverUrl)
        assertEquals("token-alice", jellyfin.session.accessToken)
        assertEquals(SourceItemId("lib-music"), jellyfin.library.id)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), ready.lastFetchedAt)
        assertEquals(Instant.fromEpochMilliseconds(2_000L), ready.lastAttemptedAt)
    }

    @Test
    fun connectSharedFolderChecksTheRootThenReachesReady() = runTest {
        trees.tree.entries = mapOf("" to listOf(FolderEntry("Publisher", true, 0, Instant.fromEpochMilliseconds(0))))
        repo.connectSharedFolder(SmbConnection("  smb://nas.local/ ", " recordings ", "\\radio//2026/", " alice ", "pw"))

        assertEquals(listOf(smb.copy(path = "radio/2026")), trees.opened)
        assertEquals(true, trees.tree.closed, "the probe connection is closed")
        val ready = assertIs<SessionState.Ready>(repo.state.first())
        assertEquals(ConnectedSource.Smb(smb.copy(path = "radio/2026")), ready.source)
        assertNull(ready.lastFetchedAt)
        assertEquals("enc[pw]", dataStore.data.first()[stringPreferencesKey("smb_password")], "password is encrypted at rest")
    }

    @Test
    fun guestConnectionNeedsNoPassword() = runTest {
        repo.connectSharedFolder(SmbConnection("nas.local", "public", "", "", "", guest = true))
        val smb = assertIs<ConnectedSource.Smb>(assertIs<SessionState.Ready>(repo.state.first()).source)
        assertEquals(true, smb.connection.guest)
        assertNull(dataStore.data.first()[stringPreferencesKey("smb_password")])
    }

    @Test
    fun connectionFailuresAreNotSaved() = runTest {
        trees.tree.failure = ServerException.Unauthorized()
        assertFailsWith<ServerException.Unauthorized> { repo.connectSharedFolder(smb) }
        trees.tree.failure = IOException("no route to host")
        assertFailsWith<ServerException.Unreachable> { repo.connectSharedFolder(smb) }
        trees.tree.failure = null
        trees.tree.entries = emptyMap() // 共有の中のパスが無い
        assertFailsWith<ServerException.Failed> { repo.connectSharedFolder(smb) }
        assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals(true, trees.tree.closed)
    }

    @Test
    fun invalidInputIsRejectedBeforeConnecting() = runTest {
        assertFailsWith<IllegalArgumentException> { repo.connectSharedFolder(smb.copy(host = " ")) }
        assertFailsWith<IllegalArgumentException> { repo.connectSharedFolder(smb.copy(share = "/")) }
        assertFailsWith<IllegalArgumentException> { repo.connectSharedFolder(smb.copy(path = "../other")) }
        assertEquals(emptyList(), trees.opened)
    }

    @Test
    fun signOutOfSharedFolderKeepsWhereButNotThePassword() = runTest {
        repo.connectSharedFolder(smb)
        repo.signOut()
        val signedOut = assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals(smb.copy(password = ""), signedOut.lastSmb)
        val keys = dataStore.data.first().asMap().keys.map { it.name }
        assertFalse("smb_password" in keys)
    }

    /** ゲストかどうかもホストなどと同じく残る（再接続の画面でスイッチが前の値のまま出る）。パスワードの無いゲストでもログアウトできる。 */
    @Test
    fun signOutOfAGuestConnectionKeepsGuestAndLogsOut() = runTest {
        repo.connectSharedFolder(SmbConnection("nas.local", "public", "", "", "", guest = true))
        repo.signOut()
        val signedOut = assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals(SmbConnection("nas.local", "public", "", "", "", guest = true), signedOut.lastSmb)

        repo.connectSharedFolder(SmbConnection("nas.local", "public", "", "", "", guest = true))
        assertIs<SessionState.Ready>(repo.state.first())
    }

    /** 取得元は同時に 1 つ。片方に接続すると、もう片方の接続は消える。 */
    @Test
    fun connectingToOneSourceForgetsTheOther() = runTest {
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        repo.connectSharedFolder(smb)
        assertIs<ConnectedSource.Smb>(assertIs<SessionState.Ready>(repo.state.first()).source)
        assertFalse("server_url" in dataStore.data.first().asMap().keys.map { it.name })

        repo.signIn("jellyfin.lab.example", "alice", "secret")
        assertIs<SessionState.NeedsLibrary>(repo.state.first())
        assertFalse("smb_host" in dataStore.data.first().asMap().keys.map { it.name })
    }

    @Test
    fun jellyfinCallsFailWhileConnectedToASharedFolder() = runTest {
        repo.connectSharedFolder(smb)
        assertFailsWith<ServerException.Unauthorized> { repo.listLibraries() }
    }
}
