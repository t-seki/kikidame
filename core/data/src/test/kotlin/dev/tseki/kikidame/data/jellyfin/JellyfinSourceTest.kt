package dev.tseki.kikidame.data.jellyfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.repository.FakeJellyfinGateway
import dev.tseki.kikidame.data.repository.testSessionStore
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Jellyfin の取得元（ADR 0010）。認証の情報とライブラリを [dev.tseki.kikidame.data.session.SessionStore] から読んで渡す。 */
@RunWith(AndroidJUnit4::class)
class JellyfinSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeJellyfinGateway()
    private val store by lazy { testSessionStore(tmp.root, scope) }
    private val source by lazy { JellyfinSource(gateway, store) }
    private val session = Session("https://jellyfin.lab.example/", "alice", userId = "user-alice", accessToken = "token-alice")
    private val credentials = ServerCredentials("https://jellyfin.lab.example/", "token-alice", "user-alice")

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun fetchAllReadsTheSelectedLibraryWithTheStoredCredentials() = runTest {
        store.saveSession(session)
        store.saveLibrary(SelectedLibrary(SourceItemId("lib-music"), "Radio"))
        gateway.snapshot = SourceSnapshot(listOf(SourceProgram(SourceItemId("album-1"), "番組", "TBSラジオ")), emptyList())

        assertEquals(gateway.snapshot, source.fetchAll())
        assertEquals(listOf(SourceItemId("lib-music")), gateway.fetchedLibraries)
        assertEquals(listOf(credentials), gateway.usedCredentials)
    }

    @Test
    fun fetchAllNeedsASelectedLibrary() = runTest {
        store.saveSession(session)
        assertFailsWith<ServerException.Unauthorized> { source.fetchAll() }
        assertEquals(emptyList(), gateway.fetchedLibraries)
    }

    /** ダウンロードはライブラリを選ぶ前でも行う（`DownloadWorker` の挙動）。 */
    @Test
    fun openDownloadWorksBeforeALibraryIsSelected() = runTest {
        store.saveSession(session)
        gateway.files[SourceItemId("audio-1")] = byteArrayOf(1, 2, 3)

        source.openDownload(SourceItemId("audio-1"), rangeStart = 0).use { stream ->
            assertEquals(3L, stream.totalBytes)
        }
        assertEquals(listOf(credentials), gateway.usedCredentials)
    }

    @Test
    fun signedOutIsUnauthorized() = runTest {
        assertFailsWith<ServerException.Unauthorized> { source.fetchAll() }
        assertFailsWith<ServerException.Unauthorized> { source.fetchProgram(SourceItemId("album-1")) }
        assertFailsWith<ServerException.Unauthorized> { source.fetchProgramEpisodes(SourceItemId("album-1")) }
        assertFailsWith<ServerException.Unauthorized> { source.openDownload(SourceItemId("audio-1"), rangeStart = 0) }
        assertEquals(emptyList(), gateway.usedCredentials)
    }
}
