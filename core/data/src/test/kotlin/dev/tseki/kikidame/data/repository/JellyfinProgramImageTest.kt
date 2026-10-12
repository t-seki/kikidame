package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceImage
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Jellyfin の番組の画像（#282、ADR 0012）: Primary の ImageTag が版で、同期が手元のファイルにそろえる。
 * ImageTag が変わらなければ取り直さない。取得元は [FakeJellyfinGateway]（`JellyfinSource` 越し）。
 */
@RunWith(AndroidJUnit4::class)
class JellyfinProgramImageTest : JellyfinRefreshTestBase() {
    private val jpeg = byteArrayOf(1, 2, 3)
    private val newer = byteArrayOf(4, 5, 6, 7)

    private fun snapshot(tagA: String?, tagB: String? = null) = SourceSnapshot(
        programs = listOf(
            SourceProgram(SourceItemId("album-1"), "番組 A", "TBSラジオ", tagA?.let(::SourceImage)),
            SourceProgram(SourceItemId("album-2"), "番組 B", "TBSラジオ", tagB?.let(::SourceImage)),
        ),
        episodes = emptyList(),
    )

    private suspend fun program(name: String): Program = library.observePrograms().first().single { it.program.name == name }.program

    @Test
    fun imageIsFetchedOnlyWhenTheImageTagChanges() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot("tag-1")
        gateway.programImages[SourceItemId("album-1")] = jpeg

        repo.refresh()
        val first = assertNotNull(program("番組 A").image)
        assertContentEquals(jpeg, File(first.path).readBytes())
        assertEquals(listOf(SourceItemId("album-1")), gateway.fetchedImages)
        // Primary の無いアルバムは取りに行かない
        assertNull(program("番組 B").image)

        // ImageTag が同じなら取り直さない
        repo.refresh()
        assertEquals(first, program("番組 A").image)
        assertEquals(1, gateway.fetchedImages.size)

        // 変わったら取り直し、前のファイルを消す
        gateway.snapshot = snapshot("tag-2")
        gateway.programImages[SourceItemId("album-1")] = newer
        repo.refresh()
        val second = assertNotNull(program("番組 A").image)
        assertNotEquals(first.path, second.path)
        assertContentEquals(newer, File(second.path).readBytes())
        assertFalse(File(first.path).exists())
        assertEquals(2, gateway.fetchedImages.size)

        // Primary が無くなったら画像なし。ファイルも消す
        gateway.snapshot = snapshot(null)
        repo.refresh()
        assertNull(program("番組 A").image)
        assertFalse(File(second.path).exists())
        assertEquals(2, gateway.fetchedImages.size)
    }

    /** 取得に失敗しても同期は止めず、前の画像を残す。次の同期で取り直す。 */
    @Test
    fun fetchFailureKeepsThePreviousImageAndIsRetried() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot("tag-1")
        gateway.programImages[SourceItemId("album-1")] = jpeg
        repo.refresh()
        val first = assertNotNull(program("番組 A").image)

        gateway.snapshot = snapshot("tag-2")
        gateway.imageFailure = SourceException.Failed("HTTP 500")
        repo.refresh()
        assertEquals(first, program("番組 A").image)
        assertContentEquals(jpeg, File(first.path).readBytes())

        gateway.imageFailure = null
        gateway.programImages[SourceItemId("album-1")] = newer
        repo.refresh()
        assertContentEquals(newer, File(assertNotNull(program("番組 A").image).path).readBytes())
    }
}
