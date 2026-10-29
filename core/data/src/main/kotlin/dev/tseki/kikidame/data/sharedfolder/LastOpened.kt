package dev.tseki.kikidame.data.sharedfolder

/**
 * 最後に開いたファイルを、パスと組にして 1 つだけ持ち回す。[FolderTree.read] は同じファイルを続けて読むことが多いので、
 * SMB（`SmbFolderTree`）と端末のフォルダ（`DeviceFolderTree`）の木が、開いたハンドルを使い回すのに使う。
 *
 * - [get] は、持っているもののパスが同じならそれを返す。違えば先に新しいものを開き、開けてから古いものを静かに閉じて付け替える。
 *   開くのに失敗したら、古いものは閉じずに持ったまま
 * - [close] で持っているものを静かに閉じ、空に戻す。その後も [get] で使える
 * - スレッド安全ではない。呼ぶ側（木の `@Synchronized` のメソッド）が 1 つずつ呼ぶ
 */
internal class LastOpened<T : AutoCloseable> : AutoCloseable {
    private var last: Pair<String, T>? = null

    fun get(path: String, open: (String) -> T): T {
        last?.takeIf { it.first == path }?.let { return it.second }
        val opened = open(path)
        closeQuietly { last?.second?.close() }
        last = path to opened
        return opened
    }

    override fun close() {
        closeQuietly { last?.second?.close() }
        last = null
    }
}

/** [block] を流し、投げた [Exception] を捨てる（閉じるときの失敗で、本来の結果や例外を隠さないため）。 */
internal inline fun closeQuietly(block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
    }
}
