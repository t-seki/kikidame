package dev.tseki.kikidame.data.sharedfolder

/**
 * タグの読み取り用に、1 ファイルを [blockSize] ずつまとめて読む（#209 の決定 5）。[MediaDataSource][android.media.MediaDataSource] の
 * 小さな読み出し（m4a で 2,048 バイトずつ 150〜191 回。#209 の計測）を、まとめた塊から返して、木への往復を減らす。
 *
 * - 塊は位置で決まる（`position / blockSize` 番目）。先頭から順に読む前提にしない。m4a には末尾の近く（約 10.67 MB の位置）まで
 *   読むファイルがあった（#209 の計測）ので、離れた位置の塊も [maxBlocks] 個まで持つ。溢れたら最も長く使っていない塊を捨てる
 * - 塊を埋めるときは、塊が埋まるか木が末尾（-1 か 0）を返すまで読み続ける（木は求めたより少なく返すことがある）
 * - 長さは一覧の大きさ（[sizeBytes]）で区切る。それより先は末尾として -1 を返す
 * - 木の読み取りの失敗は、そのまま投げる
 *
 * スレッドセーフではない。1 ファイルのタグを読む間だけ使う。
 */
class BlockReader(
    private val tree: FolderTree,
    private val path: String,
    private val sizeBytes: Long,
    private val blockSize: Int = BLOCK_BYTES,
    private val maxBlocks: Int = MAX_BLOCKS,
) {
    init {
        require(blockSize > 0 && maxBlocks > 0)
    }

    /** 塊の番号 → 読めた中身（末尾の塊と、木が途中で末尾を返した塊は短い）。アクセス順で、溢れたら最も古いものを捨てる。 */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(maxBlocks + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > maxBlocks
    }

    /** [position] から最大 [size] バイトを [buffer] の [offset] 以降に写し、写したバイト数を返す。[position] が末尾以降なら -1。 */
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= sizeBytes) return -1
        if (size <= 0) return 0
        var copied = 0
        var pos = position
        while (copied < size && pos < sizeBytes) {
            val index = pos / blockSize
            val block = block(index)
            val within = (pos - index * blockSize).toInt()
            // 木が一覧の大きさより手前で末尾を返した（一覧の後にファイルが縮んだ）
            if (within >= block.size) break
            val n = minOf(size - copied, block.size - within)
            System.arraycopy(block, within, buffer, offset + copied, n)
            copied += n
            pos += n
        }
        return if (copied == 0) -1 else copied
    }

    private fun block(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start = index * blockSize
        val length = minOf(blockSize.toLong(), sizeBytes - start).toInt()
        val bytes = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = tree.read(path, start + filled, bytes, filled, length - filled)
            if (n <= 0) break
            filled += n
        }
        val block = if (filled == length) bytes else bytes.copyOf(filled)
        blocks[index] = block
        return block
    }

    companion object {
        /** まとめて読む大きさ（#209 の決定 5 の「256 KB ほど」）。 */
        const val BLOCK_BYTES: Int = 256 * 1024

        /**
         * 持っておく塊の数。m4a は先頭から約 400 KB まで（2 塊）と、`moov` が末尾にあるときは末尾の近く（1 塊）を読んだ
         * （#209 の計測。`moov` が末尾にあるというのは推測で未確認）。それに 1 つ余裕を持たせる。1 ファイルあたり最大 1 MB。
         */
        const val MAX_BLOCKS: Int = 4
    }
}
