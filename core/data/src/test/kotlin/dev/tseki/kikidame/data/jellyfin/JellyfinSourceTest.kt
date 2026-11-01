package dev.tseki.kikidame.data.jellyfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.repository.FakeJellyfinGateway
import dev.tseki.kikidame.data.repository.testSessionStore
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SourceException
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

/** Jellyfin の取得元（ADR 0007）。認証の情報とライブラリを [dev.tseki.kikidame.data.session.SessionStore] から読んで渡す。 */
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
        // 種類を指定せずに保存したライブラリは音楽
        assertEquals(listOf(SelectedLibrary(SourceItemId("lib-music"), "Radio", LibraryKind.MUSIC)), gateway.usedLibraries)
    }

    /** ブックのライブラリは、一覧も 1 番組の取得もライブラリの種類と一緒に渡す。 */
    @Test
    fun programCallsPassTheSelectedBookLibrary() = runTest {
        store.saveSession(session)
        val books = SelectedLibrary(SourceItemId("lib-books"), "Radio", LibraryKind.BOOKS)
        store.saveLibrary(books)
        val program = SourceItemId("TBSラジオ/番組")
        gateway.snapshot = SourceSnapshot(listOf(SourceProgram(program, "番組", "TBSラジオ")), emptyList())

        source.fetchAll()
        assertEquals(program, source.fetchProgram(program)?.sourceId)
        source.fetchProgramEpisodes(program)
        source.fetchProgramImage(program)
        assertEquals(List(4) { books }, gateway.usedLibraries)
    }

    @Test
    fun fetchAllNeedsASelectedLibrary() = runTest {
        store.saveSession(session)
        assertFailsWith<SourceException.Unauthorized> { source.fetchAll() }
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

    /** 番組の画像は、番組の取得と同じ認証の情報で取る。 */
    @Test
    fun fetchProgramImageUsesTheStoredCredentials() = runTest {
        store.saveSession(session)
        store.saveLibrary(SelectedLibrary(SourceItemId("lib-music"), "Radio"))
        gateway.programImages[SourceItemId("album-1")] = byteArrayOf(9, 8)

        assertEquals(listOf<Byte>(9, 8), source.fetchProgramImage(SourceItemId("album-1"))?.toList())
        assertEquals(null, source.fetchProgramImage(SourceItemId("album-2")))
        assertEquals(listOf(credentials, credentials), gateway.usedCredentials)
    }

    /**
     * ライブラリを選ぶ前は、番組の組み方（ライブラリの種類）が決まらないので、番組についての呼び出しは失敗にする。
     * ログアウトにはしない（[SourceException.Unauthorized] ではない）。
     */
    @Test
    fun programCallsBeforeALibraryIsSelectedFail() = runTest {
        store.saveSession(session)
        assertFailsWith<SourceException.Failed> { source.fetchProgramImage(SourceItemId("album-1")) }
        assertFailsWith<SourceException.Failed> { source.fetchProgram(SourceItemId("album-1")) }
        assertFailsWith<SourceException.Failed> { source.fetchProgramEpisodes(SourceItemId("album-1")) }
        assertEquals(emptyList(), gateway.usedCredentials)
    }

    @Test
    fun signedOutIsUnauthorized() = runTest {
        assertFailsWith<SourceException.Unauthorized> { source.fetchProgramImage(SourceItemId("album-1")) }
        assertFailsWith<SourceException.Unauthorized> { source.fetchAll() }
        assertFailsWith<SourceException.Unauthorized> { source.fetchProgram(SourceItemId("album-1")) }
        assertFailsWith<SourceException.Unauthorized> { source.fetchProgramEpisodes(SourceItemId("album-1")) }
        assertFailsWith<SourceException.Unauthorized> { source.openDownload(SourceItemId("audio-1"), rangeStart = 0) }
        assertEquals(emptyList(), gateway.usedCredentials)
    }
}
