package dev.tseki.kikidame.data.sharedfolder

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** タグの読み取り用のまとめ読み（#209 の決定 5）。木への往復（[MemoryFolderTree.reads]）を数える。 */
class BlockReaderTest {
    private val folder = MemoryFolderTree()
    private val path = "P/番組/a.m4a"

    private fun file(size: Int): ByteArray = Random(209).nextBytes(size).also { folder.put(path, it) }

    private fun BlockReader.readBytes(position: Long, size: Int): ByteArray {
        val buffer = ByteArray(size)
        val n = read(position, buffer, 0, size)
        return buffer.copyOf(n.coerceAtLeast(0))
    }

    @Test
    fun theBlockIs256KbAndFourBlocksAreKept() {
        assertEquals(256 * 1024, BlockReader.BLOCK_BYTES)
        assertEquals(4, BlockReader.MAX_BLOCKS)
    }

    /** 小さな読み出しを先頭から続けても、塊 1 つにつき 1 往復（m4a の 2,048 バイト × 150 回。#209 の計測）。 */
    @Test
    fun smallSequentialReadsShareOneRoundTripPerBlock() {
        val bytes = file(11 * 1024 * 1024)
        val reader = BlockReader(folder.tree, path, bytes.size.toLong())

        for (i in 0 until 150) {
            val position = i * 2048L
            assertContentEquals(bytes.copyOfRange(position.toInt(), position.toInt() + 2048), reader.readBytes(position, 2048))
        }

        // 150 × 2,048 = 307,200 バイトは、256 KB の塊 2 つにまたがる
        assertEquals(2, folder.reads.size)
    }

    /**
     * 先頭と末尾の近くを交互に読んでも、塊を捨て合わずに往復が減る（m4a には末尾の近く（約 10.67 MB の位置）まで読むファイルがあった。
     * #209 の計測）。
     */
    @Test
    fun alternatingReadsAtTheHeadAndNearTheEndStayCached() {
        val bytes = file(11 * 1024 * 1024)
        val size = bytes.size.toLong()
        val reader = BlockReader(folder.tree, path, size)

        var readAts = 0
        for (i in 0 until 50) {
            val head = i * 2048L
            val tail = size - 300_000 + i * 2048L
            assertContentEquals(bytes.copyOfRange(head.toInt(), head.toInt() + 2048), reader.readBytes(head, 2048))
            assertContentEquals(bytes.copyOfRange(tail.toInt(), tail.toInt() + 2048), reader.readBytes(tail, 2048))
            readAts += 2
        }

        assertEquals(100, readAts)
        // 先頭の塊 1 つと、末尾の近くの塊（境目をまたぐので 2 つ）
        assertEquals(3, folder.reads.size, "reads=${folder.reads.size}")
    }

    @Test
    fun aReadAcrossTheBlockBoundaryIsCorrect() {
        val bytes = file(1024)
        val reader = BlockReader(folder.tree, path, bytes.size.toLong(), blockSize = 100, maxBlocks = 2)

        assertContentEquals(bytes.copyOfRange(95, 305), reader.readBytes(95, 210))
    }

    @Test
    fun theLeastRecentlyUsedBlockIsDroppedWhenFull() {
        val bytes = file(1000)
        val reader = BlockReader(folder.tree, path, bytes.size.toLong(), blockSize = 100, maxBlocks = 2)

        reader.readBytes(0, 10) // 塊 0
        reader.readBytes(500, 10) // 塊 5
        reader.readBytes(0, 10) // 塊 0（使った）
        reader.readBytes(900, 10) // 塊 9。塊 5 を捨てる
        assertEquals(3, folder.reads.size)
        reader.readBytes(0, 10)
        assertEquals(3, folder.reads.size, "block 0 is still kept")
        reader.readBytes(500, 10)
        assertEquals(4, folder.reads.size, "block 5 was dropped and is read again")
    }

    /** 木が求めたより少なく返しても、塊を埋めるまで読み続ける（短い読みを末尾と取り違えない）。 */
    @Test
    fun shortReadsFromTheTreeDoNotCorruptTheData() {
        val bytes = file(1000)
        var calls = 0
        val stingy = object : FolderTree by folder.tree {
            override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                calls++
                return folder.tree.read(path, position, buffer, offset, minOf(size, 7))
            }
        }
        val reader = BlockReader(stingy, path, bytes.size.toLong(), blockSize = 100, maxBlocks = 4)

        assertContentEquals(bytes.copyOfRange(0, 250), reader.readBytes(0, 250))
        assertContentEquals(bytes.copyOfRange(990, 1000), reader.readBytes(990, 100))
        assertTrue(calls > 3)
    }

    @Test
    fun theEndOfTheFileIsMinusOne() {
        val bytes = file(1000)
        val reader = BlockReader(folder.tree, path, bytes.size.toLong(), blockSize = 256, maxBlocks = 2)

        assertEquals(-1, reader.read(1000, ByteArray(10), 0, 10))
        assertEquals(-1, reader.read(5000, ByteArray(10), 0, 10))
        val buffer = ByteArray(100)
        assertEquals(10, reader.read(990, buffer, 0, 100), "the last bytes are returned short")
        assertContentEquals(bytes.copyOfRange(990, 1000), buffer.copyOf(10))
        assertEquals(0, reader.read(0, buffer, 0, 0))
    }

    @Test
    fun bytesAreWrittenAtTheOffset() {
        val bytes = file(300)
        val reader = BlockReader(folder.tree, path, bytes.size.toLong(), blockSize = 128, maxBlocks = 2)
        val buffer = ByteArray(20)

        assertEquals(10, reader.read(120, buffer, 5, 10))
        assertContentEquals(bytes.copyOfRange(120, 130), buffer.copyOfRange(5, 15))
        assertContentEquals(ByteArray(5), buffer.copyOfRange(0, 5))
    }
}
