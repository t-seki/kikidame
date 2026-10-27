package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.session.SessionStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tseki.kikidame.data.sharedfolder.FolderEntry
import dev.tseki.kikidame.data.device.MemoryDeviceDocuments
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.UnsupportedFolderException
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SmbConnection
import java.io.IOException
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SyncAttempt
import dev.tseki.kikidame.domain.SyncOutcome
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
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class DataStoreSessionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeJellyfinGateway()
    private val dataStore by lazy { testDataStore(tmp.root, scope) }
    private val store by lazy { SessionStore(dataStore, FakeCredentialCipher()) }
    private val tree = FakeFolderTree()
    private val trees = FakeFolderTreeFactory(tree)
    private val documents = MemoryDeviceDocuments(granted = false)
    private val repo by lazy { DataStoreSessionRepository(store, gateway, trees, documents) }
    private val folderUri = MemoryDeviceDocuments.TREE_URI
    private val smb = SmbConnection("nas.local", "recordings", "radio", "alice", "pw")
    private val music = LibraryView(SourceItemId("lib-music"), "Radio", "music", LibraryKind.MUSIC)
    private val books = LibraryView(SourceItemId("lib-books"), "Radio (books)", "books", LibraryKind.BOOKS)
    private val movies = LibraryView(SourceItemId("lib-movies"), "Movies", "movies", kind = null)

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
        assertFailsWith<SourceException.Unauthorized> { repo.signIn("jellyfin.lab.example", "alice", "nope") }
        assertIs<SessionState.SignedOut>(repo.state.first())
    }

    @Test
    fun onlyMusicAndBookLibrariesCanBeSelected() = runTest {
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        assertFailsWith<IllegalArgumentException> { repo.selectLibrary(movies) }
        assertIs<SessionState.NeedsLibrary>(repo.state.first())

        // ブックのライブラリも選べ、種類が読み戻せる（#354）
        repo.selectLibrary(books)
        val library = assertIs<ConnectedSource.Jellyfin>(assertIs<SessionState.Ready>(repo.state.first()).source).library
        assertEquals(SelectedLibrary(SourceItemId("lib-books"), "Radio (books)", LibraryKind.BOOKS), library)
    }

    /**
     * 同じサーバ・同じユーザーのままライブラリを選び直すのは、取得元を変えることに当たらない（ADR 0013 の決定 7）。
     * 同期の時刻は消さない（手元の番組・各回は次の同期の突合で結び直す。[RoomLibrarySwitchTest]）。
     */
    @Test
    fun reselectingALibraryKeepsTheSyncTimes() = runTest {
        val at = Instant.parse("2026-10-27T00:00:00Z")
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        store.saveLastFetchedAt(at)

        repo.selectLibrary(books)
        val ready = assertIs<SessionState.Ready>(repo.state.first())
        assertEquals(LibraryKind.BOOKS, assertIs<ConnectedSource.Jellyfin>(ready.source).library.kind)
        assertEquals(at, ready.lastFetchedAt)

        repo.selectLibrary(music)
        assertEquals(LibraryKind.MUSIC, assertIs<ConnectedSource.Jellyfin>(assertIs<SessionState.Ready>(repo.state.first()).source).library.kind)
    }

    /** ログアウトと再ログインで、ライブラリの種類もライブラリと一緒に消える（#354）。 */
    @Test
    fun libraryKindIsClearedWithTheLibrary() = runTest {
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(books)
        repo.signOut()
        assertFalse("library_kind" in dataStore.data.first().asMap().keys.map { it.name })

        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(books)
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        assertIs<SessionState.NeedsLibrary>(repo.state.first())
        assertFalse("library_kind" in dataStore.data.first().asMap().keys.map { it.name })
    }

    /** 読めない種類（新しい版が書いた値など）は、ライブラリを選び直させる（#354）。 */
    @Test
    fun unknownLibraryKindNeedsALibrary() = runTest {
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        dataStore.edit { it[stringPreferencesKey("library_kind")] = "podcasts" }
        assertIs<SessionState.NeedsLibrary>(repo.state.first())
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

    /** 全走査の最後の試み（#346）は結果ごとに読み戻せ、成功で通知の記録が消える。ログアウト・取得元の接続し直しで消える（決定 6）。 */
    @Test
    fun syncAttemptRoundTripsAndIsClearedWithTheSyncTimes() = runTest {
        val at = Instant.parse("2026-10-26T05:00:00Z")
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        for (outcome in listOf(
            SyncOutcome.SkippedNotOnWifi,
            SyncOutcome.Unreachable,
            SyncOutcome.FetchFailed("HTTP 500"),
            SyncOutcome.ImportFailed("SQLiteException"),
            SyncOutcome.Succeeded,
        )) {
            repo.recordSyncAttempt(SyncAttempt(at, outcome))
            assertEquals(SyncAttempt(at, outcome), assertIs<SessionState.Ready>(repo.state.first()).lastSyncAttempt)
        }
        assertNull(assertIs<SessionState.Ready>(repo.state.first()).lastAttemptedAt, "the attempt is kept apart from lastAttemptedAt")

        repo.recordSyncAttempt(SyncAttempt(at, SyncOutcome.Unreachable))
        repo.markSyncFailureNotified()
        assertTrue(assertIs<SessionState.Ready>(repo.state.first()).syncFailureNotified)
        repo.recordSyncAttempt(SyncAttempt(at, SyncOutcome.FetchFailed("HTTP 500")))
        assertTrue(assertIs<SessionState.Ready>(repo.state.first()).syncFailureNotified, "another failure keeps the record")
        repo.recordSyncAttempt(SyncAttempt(at, SyncOutcome.Succeeded))
        assertFalse(assertIs<SessionState.Ready>(repo.state.first()).syncFailureNotified, "a success clears the record")

        repo.recordSyncAttempt(SyncAttempt(at, SyncOutcome.Unreachable))
        repo.markSyncFailureNotified()
        repo.signOut()
        val keys = dataStore.data.first().asMap().keys.map { it.name }
        assertFalse(keys.any { it.startsWith("sync_attempt") || it == "sync_failure_notified" }, "sign-out clears them: $keys")

        repo.connectSharedFolder(smb)
        repo.recordSyncAttempt(SyncAttempt(at, SyncOutcome.Unreachable))
        repo.markSyncFailureNotified()
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        repo.selectLibrary(music)
        val switched = assertIs<SessionState.Ready>(repo.state.first())
        assertNull(switched.lastSyncAttempt, "connecting another source clears the attempt")
        assertFalse(switched.syncFailureNotified)
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
        // 前の版は種類を書いていない（音楽のライブラリだけを選べた）。音楽として読む（#354）
        assertEquals(LibraryKind.MUSIC, jellyfin.library.kind)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), ready.lastFetchedAt)
        assertEquals(Instant.fromEpochMilliseconds(2_000L), ready.lastAttemptedAt)
    }

    @Test
    fun connectSharedFolderChecksTheRootThenReachesReady() = runTest {
        tree.entries = mapOf("" to listOf(FolderEntry("Publisher", true, 0, Instant.fromEpochMilliseconds(0))))
        repo.connectSharedFolder(SmbConnection("  smb://nas.local/ ", " recordings ", "\\radio//2026/", " alice ", "pw"))

        assertEquals(listOf(smb.copy(path = "radio/2026")), trees.opened)
        assertEquals(1, trees.closes, "the probe connection is closed")
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
        tree.failure = SourceException.Unauthorized()
        assertFailsWith<SourceException.Unauthorized> { repo.connectSharedFolder(smb) }
        tree.failure = IOException("no route to host")
        assertFailsWith<SourceException.Unreachable> { repo.connectSharedFolder(smb) }
        tree.failure = null
        tree.entries = emptyMap() // 共有の中のパスが無い
        assertFailsWith<SourceException.Failed> { repo.connectSharedFolder(smb) }
        assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals(trees.opened.size, trees.closes, "every connection tried is closed")
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
        assertFailsWith<SourceException.Unauthorized> { repo.listLibraries() }
    }

    /** 端末のフォルダ（#199）: 永続権限を取り、直下が読めたらフォルダ名と一緒に保存する。 */
    @Test
    fun connectDeviceFolderTakesThePermissionChecksTheRootThenReachesReady() = runTest {
        repo.connectDeviceFolder(folderUri)

        assertEquals(listOf(DeviceFolder(folderUri, "")), trees.openedFolders, "the root is read before saving")
        assertEquals(1, trees.closes, "the probe tree is closed")
        assertTrue(documents.hasReadPermission(folderUri), "the persisted permission is taken")
        val ready = assertIs<SessionState.Ready>(repo.state.first())
        assertEquals(ConnectedSource.Device(DeviceFolder(folderUri, "Radio")), ready.source)
        assertNull(ready.lastFetchedAt)
    }

    /** 端末のストレージ以外の provider（#199 の決定 3）は、権限を取らずに断る。 */
    @Test
    fun otherProvidersAreRejectedBeforeTakingThePermission() = runTest {
        val drive = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dabc"
        assertFailsWith<UnsupportedFolderException> { repo.connectDeviceFolder(drive) }
        assertEquals(emptyList(), documents.persistedTreeUris())
        assertEquals(emptyList(), trees.openedFolders)
        assertIs<SessionState.SignedOut>(repo.state.first())
    }

    /** 直下が読めなければ保存せず、取った権限を手放す。 */
    @Test
    fun deviceFolderFailuresAreNotSavedAndReleaseThePermission() = runTest {
        tree.entries = emptyMap() // 根が読めない
        assertFailsWith<SourceException.Failed> { repo.connectDeviceFolder(folderUri) }
        tree.entries = mapOf("" to emptyList())
        tree.failure = SourceException.Unauthorized()
        assertFailsWith<SourceException.Unauthorized> { repo.connectDeviceFolder(folderUri) }
        tree.failure = IOException("I/O error")
        assertFailsWith<SourceException.Failed> { repo.connectDeviceFolder(folderUri) }

        assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals(emptyList(), documents.persistedTreeUris())
        assertEquals(listOf(folderUri, folderUri, folderUri), documents.released)
    }

    /** アクセスが切れてログアウトしても（#199 の決定 5）、どのフォルダだったかは残り、選び直せば接続に戻る。 */
    @Test
    fun signOutOfADeviceFolderKeepsTheFolderAndCanPickAgain() = runTest {
        repo.connectDeviceFolder(folderUri)
        repo.signOut()
        val signedOut = assertIs<SessionState.SignedOut>(repo.state.first())
        assertEquals(DeviceFolder(folderUri, "Radio"), signedOut.lastDeviceFolder)
        assertNull(signedOut.lastSmb)
        assertNull(signedOut.lastServerUrl)

        repo.connectDeviceFolder(folderUri)
        assertIs<ConnectedSource.Device>(assertIs<SessionState.Ready>(repo.state.first()).source)
    }

    /** 選び直したら、前に選んでいたフォルダの権限は手放す（持っている権限の数には上限がある）。 */
    @Test
    fun pickingAnotherFolderReleasesTheOldPermission() = runTest {
        val old = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        documents.grant(old)
        repo.connectDeviceFolder(folderUri)
        assertEquals(listOf(folderUri), documents.persistedTreeUris())
        assertEquals(listOf(old), documents.released)
    }

    /** 取得元は同時に 1 つ。端末のフォルダとほかの取得元は、片方に接続するともう片方が消える。 */
    @Test
    fun deviceFolderAndOtherSourcesForgetEachOther() = runTest {
        repo.connectSharedFolder(smb)
        repo.connectDeviceFolder(folderUri)
        assertIs<ConnectedSource.Device>(assertIs<SessionState.Ready>(repo.state.first()).source)
        assertFalse("smb_host" in dataStore.data.first().asMap().keys.map { it.name })

        repo.connectSharedFolder(smb)
        assertIs<ConnectedSource.Smb>(assertIs<SessionState.Ready>(repo.state.first()).source)
        assertFalse("device_tree_uri" in dataStore.data.first().asMap().keys.map { it.name })

        repo.connectDeviceFolder(folderUri)
        repo.signIn("jellyfin.lab.example", "alice", "secret")
        assertIs<SessionState.NeedsLibrary>(repo.state.first())
        assertFalse("device_tree_uri" in dataStore.data.first().asMap().keys.map { it.name })
    }
}
