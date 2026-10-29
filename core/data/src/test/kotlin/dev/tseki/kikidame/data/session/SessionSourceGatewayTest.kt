package dev.tseki.kikidame.data.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.jellyfin.JellyfinSource
import dev.tseki.kikidame.data.repository.FakeFolderTreeFactory
import dev.tseki.kikidame.data.repository.FakeJellyfinGateway
import dev.tseki.kikidame.data.repository.testSessionStore
import dev.tseki.kikidame.data.sharedfolder.AudioTags
import dev.tseki.kikidame.data.sharedfolder.FolderTree
import dev.tseki.kikidame.data.sharedfolder.MemoryFolderTree
import dev.tseki.kikidame.data.sharedfolder.ScannedFiles
import dev.tseki.kikidame.data.sharedfolder.TagReader
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.domain.SourceEpisode
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
import kotlin.test.assertFalse

/** 今の接続の種類で Jellyfin か共有フォルダに振り分けること。共有フォルダの木はメモリ上のもの。 */
@RunWith(AndroidJUnit4::class)
class SessionSourceGatewayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jellyfinGateway = FakeJellyfinGateway()
    private val store by lazy { testSessionStore(tmp.root, scope) }
    private val folder = MemoryFolderTree()
    private val trees = FakeFolderTreeFactory(folder.tree)
    private val deviceFolder = DeviceFolder("content://com.android.externalstorage.documents/tree/primary%3ARadio", "Radio")
    private val tags = object : TagReader {
        override fun read(tree: FolderTree, path: String, sizeBytes: Long) = AudioTags()
    }
    private val gateway by lazy {
        SessionSourceGateway(store, JellyfinSource(jellyfinGateway, store), trees, tags, ScannedFiles { emptyMap() })
    }
    private val smb = SmbConnection("nas.local", "recordings", "", "alice", "pw")
    private val session = Session("https://jellyfin.lab.example/", "alice", "user-alice", "token-alice")

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun jellyfinConnectionGoesToJellyfin() = runTest {
        store.saveSession(session)
        store.saveLibrary(SelectedLibrary(SourceItemId("lib-music"), "Radio"))
        jellyfinGateway.snapshot = SourceSnapshot(listOf(SourceProgram(SourceItemId("album-1"), "番組", "TBSラジオ")), emptyList())

        assertEquals(jellyfinGateway.snapshot, gateway.fetchAll())
        assertEquals(emptyList(), trees.opened, "no shared folder is opened")
    }

    @Test
    fun sharedFolderConnectionScansTheTreeAndClosesIt() = runTest {
        store.saveSmb(smb)
        folder.put("TBSラジオ/番組/2026-09-18.m4a")

        val snapshot = gateway.fetchAll()

        assertEquals(listOf(SourceItemId("TBSラジオ/番組")), snapshot.programs.map { it.sourceId })
        assertEquals(listOf(smb), trees.opened)
        assertEquals(1, trees.closes, "the connection is closed after the scan")
    }

    /** 途中経過の受け手も共有フォルダへ渡す（渡さないと全部読んでから取り込む形に戻る）。 */
    @Test
    fun sharedFolderScanStreamsProgramsToTheListener() = runTest {
        store.saveSmb(smb)
        folder.put("TBSラジオ/番組/2026-09-18.m4a")
        val streamed = ArrayList<SourceItemId>()
        val progress = ArrayList<Pair<Int, Int>>()

        gateway.fetchAll(
            object : ScanListener {
                override fun onTagProgress(read: Int, total: Int) {
                    progress += read to total
                }

                override suspend fun onProgramScanned(program: SourceProgram, episodes: List<SourceEpisode>) {
                    streamed += program.sourceId
                }
            },
        )

        assertEquals(listOf(SourceItemId("TBSラジオ/番組")), streamed)
        assertEquals(listOf(0 to 1, 1 to 1), progress)
        assertEquals(1, trees.closes)
    }

    @Test
    fun connectionIsClosedWhenTheScanFails() = runTest {
        store.saveSmb(smb)
        folder.failing = true
        assertFailsWith<SourceException.Unreachable> { gateway.fetchAll() }
        assertEquals(1, trees.closes)
    }

    /** ダウンロードの本体を閉じると接続も閉じる。閉じるまでは開いたまま。 */
    @Test
    fun downloadKeepsTheConnectionUntilTheStreamIsClosed() = runTest {
        store.saveSmb(smb)
        folder.put("A/B/a.m4a", ByteArray(10))

        val stream = gateway.openDownload(SourceItemId("A/B/a.m4a"), rangeStart = 4)
        assertEquals(10L, stream.totalBytes)
        assertEquals(4L, stream.resumedFrom)
        assertEquals(0, trees.closes)
        stream.close()
        assertEquals(1, trees.closes)
    }

    @Test
    fun failedDownloadClosesTheConnection() = runTest {
        store.saveSmb(smb)
        assertFailsWith<SourceException.Failed> { gateway.openDownload(SourceItemId("A/B/none.m4a"), rangeStart = 0) }
        assertEquals(1, trees.closes)
    }

    @Test
    fun signedOutIsUnauthorized() = runTest {
        assertFailsWith<SourceException.Unauthorized> { gateway.fetchAll() }
        store.saveSmb(smb)
        store.clearCredentials()
        assertFailsWith<SourceException.Unauthorized> { gateway.fetchProgram(SourceItemId("A/B")) }
        assertFalse(trees.opened.isNotEmpty(), "a signed-out shared folder is not opened")
    }

    /** 端末のフォルダも共有フォルダとして、その木で走査して閉じる。 */
    @Test
    fun deviceFolderScansItsTreeAndClosesIt() = runTest {
        store.saveDeviceFolder(deviceFolder)
        folder.put("TBSラジオ/番組/2026-09-18.m4a")

        val snapshot = gateway.fetchAll()

        assertEquals(listOf(SourceItemId("TBSラジオ/番組")), snapshot.programs.map { it.sourceId })
        assertEquals(listOf(deviceFolder), trees.openedFolders)
        assertEquals(emptyList(), trees.opened, "no SMB connection is opened")
        assertEquals(1, trees.closes)
    }

    @Test
    fun deviceFolderDownloadReadsFromItsTree() = runTest {
        store.saveDeviceFolder(deviceFolder)
        folder.put("A/B/a.m4a", ByteArray(10))

        val stream = gateway.openDownload(SourceItemId("A/B/a.m4a"), rangeStart = 0)
        assertEquals(10L, stream.totalBytes)
        stream.close()
        assertEquals(listOf(deviceFolder), trees.openedFolders)
        assertEquals(1, trees.closes)
    }

    @Test
    fun signedOutDeviceFolderIsNotOpened() = runTest {
        store.saveDeviceFolder(deviceFolder)
        store.clearCredentials()
        assertFailsWith<SourceException.Unauthorized> { gateway.fetchAll() }
        assertEquals(emptyList(), trees.openedFolders)
    }
}
