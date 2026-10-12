package dev.tseki.kikidame.data.sharedfolder

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.repository.RefreshTestBase
import dev.tseki.kikidame.data.repository.RoomLocalDataReset
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceImage
import dev.tseki.kikidame.domain.SourceItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 番組の画像（ADR 0012、#264）: 共有フォルダは番組のフォルダの `folder.jpg` だけを読み、同期が手元のファイルにそろえる。
 * 取得元はメモリ上の木（[MemoryFolderTree]）の [SharedFolderSource] で、画像の中身を取った回数と失敗を [CountingSource] で見る。
 */
@RunWith(AndroidJUnit4::class)
class ProgramImageTest : RefreshTestBase() {
    private val folder = MemoryFolderTree()
    private val shared by lazy { SharedFolderSource(folder.tree, SharedFolderSourceTest.FakeTagReader(), RoomScannedFiles(db.episodeDao())) }
    private val source by lazy { CountingSource(shared) }
    private val repo by lazy { refreshRepository(source) }

    /** 画像の中身を取った番組を数える。[failImage] を立てると画像の中身の取得だけが失敗する。 */
    class CountingSource(private val inner: SourceGateway) : SourceGateway by inner {
        val imageFetches = ArrayList<String>()
        var failImage = false

        override suspend fun fetchProgramImage(programId: SourceItemId): ByteArray? {
            imageFetches += programId.value
            if (failImage) throw SourceException.Unreachable(IOException("unreachable"))
            return inner.fetchProgramImage(programId)
        }
    }

    private suspend fun program(name: String): Program = library.observePrograms().first().single { it.program.name == name }.program

    private suspend fun signIn() = store.saveSmb(SmbConnection("nas.local", "recordings", "", "alice", "pw"))

    private val jpeg = byteArrayOf(1, 2, 3)
    private val newer = byteArrayOf(4, 5, 6, 7)

    @Test
    fun onlyFolderJpgInTheProgramFolderIsTheProgramImage() = runTest {
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        folder.put("TBSラジオ/A/folder.jpg", jpeg, Instant.parse("2026-09-18T01:02:03.456Z"))
        folder.put("TBSラジオ/B/2026-09-18.m4a")
        folder.put("TBSラジオ/B/Folder.JPG", jpeg)
        folder.put("TBSラジオ/C/folder.png", jpeg)
        folder.put("TBSラジオ/folder.jpg", jpeg)
        folder.put("TBSラジオ/D/extra/folder.jpg", jpeg)
        folder.put("folder.jpg", jpeg)

        val snapshot = shared.fetchAll()

        assertEquals(
            mapOf(
                "TBSラジオ/A" to SourceImage("3:${Instant.parse("2026-09-18T01:02:03.456Z").toEpochMilliseconds()}"),
                "TBSラジオ/B" to null,
                "TBSラジオ/C" to null,
                "TBSラジオ/D" to null,
            ),
            snapshot.programs.associate { it.sourceId.value to it.image },
        )
        // 画像は各回にならない
        assertEquals(listOf("TBSラジオ/A/2026-09-18.m4a", "TBSラジオ/B/2026-09-18.m4a"), snapshot.episodes.map { it.sourceId.value })
        assertEquals(snapshot.programs[0].image, shared.fetchProgram(SourceItemId("TBSラジオ/A"))!!.image)
        assertNull(shared.fetchProgram(SourceItemId("TBSラジオ/B"))!!.image)
        assertContentEquals(jpeg, shared.fetchProgramImage(SourceItemId("TBSラジオ/A")))
        assertNull(shared.fetchProgramImage(SourceItemId("TBSラジオ/B")))
        assertNull(shared.fetchProgramImage(SourceItemId("TBSラジオ")), "not a program id")
    }

    @Test
    fun syncKeepsTheImageAndFetchesItOnlyWhenItChanges() = runTest {
        signIn()
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        folder.put("TBSラジオ/A/folder.jpg", jpeg)

        repo.refresh()
        val first = assertNotNull(program("A").image)
        assertContentEquals(jpeg, File(first.path).readBytes())
        assertTrue(first.path.startsWith(images.root.absolutePath))
        assertEquals(listOf("TBSラジオ/A"), source.imageFetches)

        // 版が同じなら取り直さない
        repo.refresh()
        assertEquals(first, program("A").image)
        assertEquals(1, source.imageFetches.size)

        // 変わったら（rajikura は保存のたびに上書きする）別の名前のファイルに取り直し、前のファイルを消す
        folder.put("TBSラジオ/A/folder.jpg", newer, MemoryFolderTree.EPOCH + 1.hours)
        repo.refresh()
        val second = assertNotNull(program("A").image)
        assertNotEquals(first.path, second.path)
        assertContentEquals(newer, File(second.path).readBytes())
        assertFalse(File(first.path).exists())
        assertEquals(2, source.imageFetches.size)

        // 無くなったら画像なし。ファイルも消す
        folder.remove("TBSラジオ/A/folder.jpg")
        repo.refresh()
        assertNull(program("A").image)
        assertFalse(File(second.path).exists())
        assertTrue(images.root.listFiles().isNullOrEmpty())
    }

    /** 新しい回の無い番組（全走査の途中で取り込まれない）でも、最後の取り込みで画像を取る。 */
    @Test
    fun programWithoutNewEpisodesGetsItsImageAtTheEndOfTheScan() = runTest {
        signIn()
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        repo.refresh()
        assertNull(program("A").image)

        folder.put("TBSラジオ/A/folder.jpg", jpeg)
        repo.refresh()

        assertContentEquals(jpeg, File(assertNotNull(program("A").image).path).readBytes())
    }

    @Test
    fun failureKeepsThePreviousImageAndDoesNotStopTheSync() = runTest {
        signIn()
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        folder.put("TBSラジオ/A/folder.jpg", jpeg)
        repo.refresh()
        val before = assertNotNull(program("A").image)

        folder.put("TBSラジオ/A/folder.jpg", newer, MemoryFolderTree.EPOCH + 1.hours)
        folder.put("TBSラジオ/A/2026-09-25.m4a")
        source.failImage = true
        val result = repo.refresh()

        assertEquals(1, result.newEpisodes)
        assertEquals(before, program("A").image)
        assertContentEquals(jpeg, File(before.path).readBytes())

        // 次の同期で取り直す（版を書いていないので）
        source.failImage = false
        repo.refresh()
        assertContentEquals(newer, File(assertNotNull(program("A").image).path).readBytes())
    }

    @Test
    fun syncProgramUpdatesTheImage() = runTest {
        signIn()
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        repo.refresh()
        folder.put("TBSラジオ/A/folder.jpg", jpeg)

        repo.syncProgram(program("A").id)

        assertContentEquals(jpeg, File(assertNotNull(program("A").image).path).readBytes())
    }

    /** 番組の行が消えた（「この番組を削除」など）画像のファイルは、次の全走査で消える。 */
    @Test
    fun imagesOfRemovedProgramsArePrunedByTheNextScan() = runTest {
        signIn()
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        folder.put("TBSラジオ/A/folder.jpg", jpeg)
        folder.put("TBSラジオ/B/2026-09-18.m4a")
        folder.put("TBSラジオ/B/folder.jpg", newer)
        repo.refresh()
        val a = assertNotNull(program("A").image)
        val b = assertNotNull(program("B").image)
        folder.remove("TBSラジオ/B/2026-09-18.m4a")
        folder.remove("TBSラジオ/B/folder.jpg")
        downloads.removeProgram(program("B").id)
        assertTrue(File(b.path).exists())

        repo.refresh()

        assertTrue(File(a.path).exists())
        assertFalse(File(b.path).exists())
    }

    /** 取得元を変えてやり直す（ADR 0010 の決定 2）と、画像のファイルも消える。 */
    @Test
    fun resetAllDeletesImages() = runTest {
        signIn()
        folder.put("TBSラジオ/A/2026-09-18.m4a")
        folder.put("TBSラジオ/A/folder.jpg", jpeg)
        repo.refresh()
        val image = assertNotNull(program("A").image)

        RoomLocalDataReset(db, store, directory, images).resetAll()

        assertFalse(File(image.path).exists())
        assertTrue(images.root.listFiles().isNullOrEmpty())
    }
}
