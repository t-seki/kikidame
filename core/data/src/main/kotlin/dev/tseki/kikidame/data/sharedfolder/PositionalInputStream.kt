package dev.tseki.kikidame.data.sharedfolder

import java.io.InputStream

/**
 * 位置を指定して読む関数（[readAt]）を、[offset] から読み進める [InputStream] にする（#233）。
 * [FolderTree.open] が返すストリーム（ダウンロードの本体）に、SMB と端末のフォルダの木が使う。
 *
 * - [readAt] は `(position, buffer, offset, size)` を受け、読んだバイト数を返す（末尾以降なら 0 以下）。
 *   取得元ごとの引数の順や、1 回に読む長さの上限は、[readAt] の中で吸収する
 * - [readAt] が投げた [Exception] は [mapError] に渡し、その戻り値を投げる（そのまま投げるなら受けた例外を返す）。
 *   ストリームの読み出しは [java.io.IOException] を前提にしているので、取得元の例外をそれに変えるのに使う。[Error] は渡さない
 * - 閉じると [closeSource] を呼ぶ（開いたファイルのハンドルを閉じる）
 */
internal class PositionalInputStream(
    offset: Long,
    private val readAt: (position: Long, buffer: ByteArray, offset: Int, size: Int) -> Int,
    private val closeSource: () -> Unit,
    private val mapError: (Exception) -> Exception,
) : InputStream() {
    private var position = offset

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = try {
            readAt(position, b, off, len)
        } catch (e: Exception) {
            throw mapError(e)
        }
        if (n <= 0) return -1
        position += n
        return n
    }

    override fun close() {
        closeSource()
    }
}
