package dev.tseki.kikidame.data.sharedfolder

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.data.repository.RoomDownloadRepository
import dev.tseki.kikidame.data.repository.RoomLibraryRefreshRepository
import dev.tseki.kikidame.data.repository.RoomTestBase
import dev.tseki.kikidame.data.repository.testSessionStore
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.domain.ScanProgress
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 共有フォルダの全走査を番組ごとに取り込むこと、途中で止まっても続きから読めること、接続が切れたらやり直すこと、
 * 進み具合が流れること（#209 の決定 1・2・4・6）。木はメモリ上のもの、タグの読み取りはフェイク。
 */
@RunWith(AndroidJUnit4::class)
class SharedFolderScanTest : RoomTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folder = MemoryFolderTree()
    private val tags = SharedFolderSourceTest.FakeTagReader()
    private val store by lazy { testSessionStore(tmp.root, scope) }
    private val directory by lazy { EpisodesDirectory(ApplicationProvider.getApplicationContext()) }
    private val downloads by lazy { RoomDownloadRepository(db, directory, clock) }

    /** 読み取り（[FolderTree.read]）を、[failReads] の回数だけ失敗させる木。張り直し（[FolderTree.reset]）を数える。 */
    private var failReads = 0
    private var failLists = 0
    private var resets = 0
    private val flaky = object : FolderTree by folder.tree {
        override fun list(path: String): List<FolderEntry>? {
            if (failLists > 0) {
                failLists--
                throw IOException("Software caused connection abort")
            }
            return folder.tree.list(path)
        }

        override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (failReads > 0) {
                failReads--
                throw IOException("Software caused connection abort")
            }
            return folder.tree.read(path, position, buffer, offset, size)
        }

        override fun reset() {
            resets++
        }
    }

    private fun source(tagReader: TagReader = tags) =
        SharedFolderSource(flaky, tagReader, RoomScannedFiles(db.episodeDao()), retryDelay = Duration.ZERO)

    private fun repo(source: SharedFolderSource = source()) = RoomLibraryRefreshRepository(db, store, source, downloads, clock)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun connect() = store.saveSmb(SmbConnection("nas.local", "recordings", "", "alice", "pw"))

    private suspend fun hasEpisode(id: String) = db.episodeDao().findBySourceItemId(id) != null

    // --- 番組ごとの取り込み（決定 1） ---

    /** 次の番組のタグを読む時点で、前の番組はもう取り込まれている（番組が読んだ順に一覧に出る）。 */
    @Test
    fun eachProgramIsImportedAsSoonAsItsTagsAreRead() = runTest {
        connect()
        folder.put("P/A/1.m4a")
        folder.put("P/B/1.m4a")
        val seenWhenReadingB = ArrayList<Boolean>()
        val reader = TagReader { tree, path, size ->
            if (path.startsWith("P/B/")) seenWhenReadingB += runBlocking { hasEpisode("P/A/1.m4a") }
            tags.read(tree, path, size)
        }

        repo(source(reader)).refresh()

        assertEquals(listOf(true), seenWhenReadingB)
        assertNotNull(db.programDao().findBySourceItemId("P/B"))
    }

    @Test
    fun theScanListsEverythingBeforeReadingTags() = runTest {
        folder.put("P/A/1.m4a")
        folder.put("P/B/1.m4a")
        val order = ArrayList<String>()
        val listing = object : FolderTree by folder.tree {
            override fun list(path: String): List<FolderEntry>? = folder.tree.list(path).also { order += "list:$path" }
        }
        val reader = TagReader { tree, path, size -> order += "tags:$path"; tags.read(tree, path, size) }

        SharedFolderSource(listing, reader, ScannedFiles { emptyMap() }).fetchAll()

        assertEquals(listOf("list:", "list:P", "list:P/A", "list:P/B", "tags:P/A/1.m4a", "tags:P/B/1.m4a"), order)
    }

    /** タグを読まなかった番組（前と同じ回だけ）は、番組ごとには流さない（全走査の終わりの取り込みに任せる）。 */
    @Test
    fun onlyProgramsWithReadTagsAreStreamed() = runTest {
        connect()
        folder.put("P/A/1.m4a")
        folder.put("P/B/1.m4a")
        repo().refresh()
        folder.put("P/B/2.m4a")
        val streamed = ArrayList<String>()

        source().fetchAll(
            object : ScanListener {
                override suspend fun onProgramScanned(program: SourceProgram, episodes: List<SourceEpisode>) {
                    streamed += program.sourceId.value
                    assertEquals(listOf("P/B/1.m4a", "P/B/2.m4a"), episodes.map { it.sourceId.value }, "the program's episodes are complete")
                }
            },
        )

        assertEquals(listOf("P/B"), streamed)
    }

    // --- 途中で止まっても続きから読む（決定 2）。消失の判断は読み終えたときだけ ---

    @Test
    fun aFailedScanKeepsTheImportedProgramsAndTheNextScanSkipsTheirTags() = runTest {
        connect()
        folder.put("P/A/1.m4a")
        folder.put("P/C/1.m4a")
        repo().refresh()
        val lastFetched = lastFetchedAt()
        now += 1.minutes
        // C が消え、A に新しい回、B（読むと切れる）が増えた
        folder.remove("P/C/1.m4a")
        folder.put("P/A/2.m4a")
        folder.put("P/B/1.m4a")
        tags.calls.clear()
        val brokenB = TagReader { tree, path, size ->
            if (path.startsWith("P/B/")) throw IOException("connection abort")
            tags.read(tree, path, size)
        }

        assertFailsWith<ServerException.Unreachable> { repo(source(brokenB)).refresh() }

        assertEquals(true, hasEpisode("P/A/2.m4a"), "A was imported before B failed")
        assertEquals(false, hasEpisode("P/B/1.m4a"))
        assertNull(db.programDao().findBySourceItemId("P/C")!!.goneSince, "no Gone judgement from an unfinished scan")
        assertEquals(lastFetched, lastFetchedAt(), "the last sync time is not recorded")

        tags.calls.clear()
        repo().refresh()

        assertEquals(listOf("P/B/1.m4a"), tags.calls, "A's tags are not read again")
        assertNotNull(db.programDao().findBySourceItemId("P/C")!!.goneSince, "the finished scan judges C as Gone")
    }

    @Test
    fun aCancelledScanKeepsTheImportedProgramsAndTheNextScanSkipsTheirTags() = runBlocking {
        connect()
        folder.put("P/A/1.m4a")
        folder.put("P/B/1.m4a")
        val startedB = CompletableDeferred<Unit>()
        val released = AtomicBoolean(false)
        val slowB = TagReader { tree, path, size ->
            if (path.startsWith("P/B/")) {
                startedB.complete(Unit)
                while (!released.get()) Thread.sleep(5)
            }
            tags.read(tree, path, size)
        }

        val scan = async(Dispatchers.Default) { repo(source(slowB)).refresh() }
        startedB.await()
        scan.cancel()
        released.set(true)
        assertFailsWith<CancellationException> { scan.await() }

        assertEquals(true, hasEpisode("P/A/1.m4a"))
        assertEquals(false, hasEpisode("P/B/1.m4a"))

        tags.calls.clear()
        repo().refresh()
        assertEquals(listOf("P/B/1.m4a"), tags.calls)
    }

    // --- 結果の数（番組ごとの取り込みと、終わりの取り込みで二重に数えない） ---

    @Test
    fun newEpisodesAndDownloadsAreCountedOnce() = runTest {
        connect()
        folder.put("P/A/1.m4a")
        val first = repo().refresh()
        assertEquals(1, first.newEpisodes)
        val programId = db.programDao().findBySourceItemId("P/A")!!.id
        db.programDao().updateSync(programId, syncEnabled = true, keepLatest = null, deleteAfterPlayed = false)
        tags.tagsByPath["P/A/2.m4a"] = AudioTags(title = "第2回", duration = 30.minutes)
        folder.put("P/A/2.m4a")

        val second = repo().refresh()

        assertEquals(1, second.newEpisodes)
        assertEquals(2, second.enqueued)
        assertNotNull(db.localFileDao().findByEpisode(db.episodeDao().findBySourceItemId("P/A/1.m4a")!!.id))
        assertNotNull(db.localFileDao().findByEpisode(db.episodeDao().findBySourceItemId("P/A/2.m4a")!!.id))
    }

    // --- 張り直し（決定 4） ---

    @Test
    fun aDroppedConnectionIsResetAndTheSameFileIsReadAgain() = runTest {
        connect()
        folder.put("P/A/1.m4a")
        failReads = SharedFolderSource.MAX_CONSECUTIVE_FAILURES - 1

        repo().refresh()

        assertEquals(listOf("P/A/1.m4a", "P/A/1.m4a", "P/A/1.m4a"), tags.calls)
        assertEquals(2, resets)
        assertEquals(true, hasEpisode("P/A/1.m4a"))
    }

    @Test
    fun threeConsecutiveFailuresEndTheScanAsUnreachable() = runTest {
        assertEquals(3, SharedFolderSource.MAX_CONSECUTIVE_FAILURES)
        folder.put("P/A/1.m4a")
        failReads = 3

        assertFailsWith<ServerException.Unreachable> { source().fetchAll() }

        assertEquals(3, tags.calls.size)
        assertEquals(2, resets)
    }

    /** 成功を挟めば数え直す（続けて 3 回でなければ終わらない）。 */
    @Test
    fun aSuccessResetsTheFailureCount() = runTest {
        folder.put("P/A/1.m4a")
        folder.put("P/A/2.m4a")
        folder.put("P/A/3.m4a")
        var call = 0
        val reader = TagReader { tree, path, size ->
            call++
            // 1 本目: 2 回失敗して 3 回目で通る。2 本目も同じ。3 本目は 1 回で通る
            if (call in setOf(1, 2, 4, 5)) throw IOException("abort")
            tags.read(tree, path, size)
        }

        val snapshot = source(reader).fetchAll()

        assertEquals(3, snapshot.episodes.size)
        assertEquals(4, resets)
    }

    @Test
    fun listingIsRetriedToo() = runTest {
        folder.put("P/A/1.m4a")
        failLists = 2

        assertEquals(1, source().fetchAll().episodes.size)
        assertEquals(2, resets)
    }

    /** 認証の失敗などはやり直さない（やり直しても変わらない）。 */
    @Test
    fun serverExceptionsAreNotRetried() = runTest {
        folder.put("P/A/1.m4a")
        var calls = 0
        val reader = TagReader { _, _, _ ->
            calls++
            throw ServerException.Unauthorized()
        }

        assertFailsWith<ServerException.Unauthorized> { source(reader).fetchAll() }
        assertEquals(1, calls)
        assertEquals(0, resets)
    }

    // --- 進み具合（決定 6） ---

    @Test
    fun progressCountsTheFilesWhoseTagsAreRead() = runTest {
        connect()
        folder.put("P/A/1.m4a")
        folder.put("P/A/2.m4a")
        folder.put("P/B/1.m4a")
        val progress = ArrayList<ScanProgress>()

        repo().refresh(onProgress = { progress += it })

        assertEquals(listOf(ScanProgress(0, 3), ScanProgress(1, 3), ScanProgress(2, 3), ScanProgress(3, 3)), progress)

        // 2 回目: 変化が無ければ流さない。1 本増えれば、その 1 本だけを数える
        progress.clear()
        repo().refresh(onProgress = { progress += it })
        assertEquals(emptyList(), progress)
        folder.put("P/B/2.m4a")
        repo().refresh(onProgress = { progress += it })
        assertEquals(listOf(ScanProgress(0, 1), ScanProgress(1, 1)), progress)
    }

    private suspend fun lastFetchedAt() = (store.current() as SessionState.Ready).lastFetchedAt
}
