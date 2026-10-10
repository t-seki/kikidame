package dev.tseki.kikidame.data.sharedfolder

import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceItemId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.FileNotFoundException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * 端末のフォルダ（SAF、#199）の木。パスから文書への対応、null（無い）と例外（アクセスが切れた）の使い分け、部分読みと続きからの読み出し。
 * 文書はメモリ上のもの（[MemoryDeviceDocuments]）。
 */
class DeviceFolderTreeTest {
    private val documents = MemoryDeviceDocuments().apply {
        folder("TBS")
        folder("TBS/Junk")
        file("TBS/Junk/2026-10-01.m4a", ByteArray(10) { it.toByte() })
        file("TBS/notes.txt", ByteArray(3))
    }

    private fun tree() = DeviceFolderTree(documents, documents.treeUri)

    @Test
    fun listsFoldersAndFilesByRelativePath() {
        val tree = tree()
        assertEquals(listOf(FolderEntry("TBS", isDirectory = true, sizeBytes = 0, modifiedAt = Instant.fromEpochMilliseconds(1_000))), tree.list(""))
        assertEquals(listOf("Junk", "notes.txt"), tree.list("TBS")?.map { it.name })
        assertEquals(
            listOf(FolderEntry("2026-10-01.m4a", isDirectory = false, sizeBytes = 10, modifiedAt = Instant.fromEpochMilliseconds(2_000))),
            tree.list("TBS/Junk"),
        )
    }

    @Test
    fun resolvesADeepPathWithoutListingFirst() {
        // 番組だけの取得（fetchProgram）は根から順に一覧せずに番組のパスを渡す
        assertEquals(listOf("2026-10-01.m4a"), tree().list("TBS/Junk")?.map { it.name })
    }

    @Test
    fun listsEachFolderOnceWithinATree() {
        val tree = tree()
        tree.list("")
        tree.list("TBS")
        tree.list("TBS/Junk")
        tree.read("TBS/Junk/2026-10-01.m4a", 0, ByteArray(4), 0, 4)
        // 根・TBS・Junk を 1 回ずつ（ファイルの位置を引くのに、もう一覧した Junk を一覧し直さない）
        assertEquals(listOf("doc:", "doc:/TBS", "doc:/TBS/Junk"), documents.listed)
    }

    @Test
    fun missingFolderUnderAnExistingRootIsNull() {
        val tree = tree()
        assertNull(tree.list("TBS/Gone"))
        assertNull(tree.list("Nobody/Junk"))
        // ファイルはフォルダでない
        assertNull(tree.list("TBS/notes.txt"))
    }

    @Test
    fun missingRootIsUnauthorizedNotNull() {
        // 根が無いのを null で返すと、全走査が到達不能で終わるか、番組ごとの取得が全番組を消失と判断する（#199 の決定 5）
        documents.rootGone = true
        assertFailsWith<ServerException.Unauthorized> { tree().list("") }
        assertFailsWith<ServerException.Unauthorized> { tree().list("TBS/Junk") }
    }

    /**
     * 根を問い合わせて例外になったときは、無いと分かったわけではない（provider が落ちた、SD カードが一時的に外れたなども同じ形で来うる）ので、
     * ログアウトにせず通常の失敗にする（PR #214 1 周目 #2）。cursor が null のときも、実装（`ContentResolverDeviceDocuments`）が例外にするので同じ
     */
    @Test
    fun failingToQueryTheRootIsAnOrdinaryFailureNotUnauthorized() {
        documents.queryFailure = IllegalArgumentException("Failed during query")
        val error = assertFailsWith<IllegalArgumentException> { tree().list("") }
        assertEquals("Failed during query", error.message)
    }

    @Test
    fun rootThatIsNotAFolderIsUnauthorized() {
        val files = MemoryDeviceDocuments()
        val tree = DeviceFolderTree(object : DeviceDocuments by files {
            override fun query(treeUri: String, documentId: String) = files.query(treeUri, documentId)?.copy(isDirectory = false)
        }, files.treeUri)
        assertFailsWith<ServerException.Unauthorized> { tree.list("") }
    }

    @Test
    fun noPersistedPermissionIsUnauthorized() {
        val tree = DeviceFolderTree(MemoryDeviceDocuments(granted = false), MemoryDeviceDocuments.TREE_URI)
        assertFailsWith<ServerException.Unauthorized> { tree.list("") }
    }

    @Test
    fun securityExceptionIsUnauthorized() {
        val tree = tree()
        tree.list("")
        documents.securityFailure = true
        assertFailsWith<ServerException.Unauthorized> { tree.list("TBS") }
        assertFailsWith<ServerException.Unauthorized> { tree.open("TBS/Junk/2026-10-01.m4a", 0) }
    }

    @Test
    fun readsAtAPositionAndKeepsTheFileOpen() {
        val tree = tree()
        val buffer = ByteArray(4)
        assertEquals(4, tree.read("TBS/Junk/2026-10-01.m4a", 6, buffer, 0, 4))
        assertContentEquals(byteArrayOf(6, 7, 8, 9), buffer)
        assertEquals(2, tree.read("TBS/Junk/2026-10-01.m4a", 8, buffer, 1, 4))
        assertEquals(-1, tree.read("TBS/Junk/2026-10-01.m4a", 10, buffer, 0, 4))
        assertEquals(1, documents.opened.size)
        tree.close()
    }

    @Test
    fun opensFromAnOffset() {
        val stream = tree().open("TBS/Junk/2026-10-01.m4a", 7)
        assertEquals(10, stream.sizeBytes)
        assertContentEquals(byteArrayOf(7, 8, 9), stream.body.use { it.readBytes() })
    }

    @Test
    fun openingAMissingFileIsFileNotFound() {
        assertFailsWith<FileNotFoundException> { tree().open("TBS/Junk/gone.m4a", 0) }
        assertFailsWith<FileNotFoundException> { tree().read("TBS/Junk", 0, ByteArray(1), 0, 1) }
    }

    @Test
    fun missingModifiedTimeFallsBackToTheEpoch() {
        documents.file("TBS/Junk/no-date.m4a", ByteArray(1), modified = null)
        val entry = tree().list("TBS/Junk")?.single { it.name == "no-date.m4a" }
        assertEquals(Instant.fromEpochMilliseconds(0), entry?.modifiedAt)
    }

    @Test
    fun scansThroughSharedFolderSource() = runTest {
        val source = SharedFolderSource(tree(), { _, _, _ -> AudioTags(title = "ジャンク") }, { emptyMap() })
        val snapshot = source.fetchAll()
        assertEquals(listOf(SourceItemId("TBS/Junk")), snapshot.programs.map { it.sourceId })
        assertEquals(listOf(SourceItemId("TBS/Junk/2026-10-01.m4a")), snapshot.episodes.map { it.sourceId })
        assertEquals("TBS", snapshot.programs.single().publisherName)
    }

    /** 根の問い合わせが失敗し続けたら、ログアウトせずに到達不能（判断保留）で終わる。手元のデータは消えない。 */
    @Test
    fun failingRootQueryDuringAScanIsUnreachable() = runTest {
        documents.queryFailure = IllegalArgumentException("Failed during query")
        val source = SharedFolderSource(tree(), { _, _, _ -> AudioTags() }, { emptyMap() })
        assertFailsWith<ServerException.Unreachable> { source.fetchAll() }
        assertFailsWith<ServerException.Unreachable> { source.fetchProgram(SourceItemId("TBS/Junk")) }
    }

    @Test
    fun lostAccessDuringAScanIsUnauthorizedWithoutRetrying() = runTest {
        // アクセスが切れたらやり直さずにログアウトへ（到達不能にしない。#199 の決定 5）
        documents.rootGone = true
        val source = SharedFolderSource(tree(), { _, _, _ -> AudioTags() }, { emptyMap() })
        assertFailsWith<ServerException.Unauthorized> { source.fetchAll() }
        assertFailsWith<ServerException.Unauthorized> { source.fetchProgram(SourceItemId("TBS/Junk")) }
    }
}
