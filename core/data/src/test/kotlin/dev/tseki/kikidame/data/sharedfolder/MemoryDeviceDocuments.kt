package dev.tseki.kikidame.data.sharedfolder

import java.io.FileNotFoundException

/**
 * メモリ上の端末のフォルダ（SAF、#199）。1 つのツリー [treeUri] だけを持つ。文書 ID は `doc:<根からの相対パス>` の形にして、
 * 木（[DeviceFolderTree]）が ID の形に頼らないことを見えるようにする。
 *
 * [folder] / [file] で中身を作る。[rootGone] で根を消し、[securityFailure] で権限の失敗を投げさせる。
 * 権限は [takePermission] で取り、[releasePermission] で手放す（[granted] で初めから持たせる）。
 */
class MemoryDeviceDocuments(
    val treeUri: String = TREE_URI,
    granted: Boolean = true,
) : DeviceDocuments {
    private val documents = LinkedHashMap<String, Pair<DeviceDocument, ByteArray?>>()
    private val permissions = LinkedHashSet<String>()
    var rootGone = false
    var securityFailure = false

    /** 呼ばれた [children] の文書 ID（同じフォルダを何度も一覧していないかを見る）。 */
    val listed = ArrayList<String>()
    val opened = ArrayList<String>()
    val released = ArrayList<String>()

    init {
        if (granted) permissions += treeUri
        documents[ROOT_ID] = DeviceDocument(ROOT_ID, "Radio", isDirectory = true, sizeBytes = 0, lastModifiedMillis = 0) to null
    }

    fun folder(path: String, modified: Long? = 1_000L) {
        documents[id(path)] = DeviceDocument(id(path), path.substringAfterLast('/'), isDirectory = true, sizeBytes = 0, lastModifiedMillis = modified) to null
    }

    fun file(path: String, bytes: ByteArray, modified: Long? = 2_000L) {
        documents[id(path)] = DeviceDocument(id(path), path.substringAfterLast('/'), isDirectory = false, sizeBytes = bytes.size.toLong(), lastModifiedMillis = modified) to bytes
    }

    fun remove(path: String) {
        documents.keys.removeAll { it == id(path) || it.startsWith(id(path) + "/") }
    }

    /** ほかのツリーの権限も持たせる（取得元を変えたとき・選び直したときに手放すかを見る）。 */
    fun grant(uri: String) {
        permissions += uri
    }

    override fun hasReadPermission(treeUri: String): Boolean = treeUri in permissions

    override fun rootDocumentId(treeUri: String): String = ROOT_ID

    override fun query(treeUri: String, documentId: String): DeviceDocument? {
        check()
        if (rootGone) return null
        return documents[documentId]?.first
    }

    override fun children(treeUri: String, documentId: String): List<DeviceDocument> {
        check()
        if (rootGone) throw IllegalArgumentException("Failed to determine if $documentId is child of the tree")
        listed += documentId
        val prefix = "$documentId/"
        return documents.values.map { it.first }.filter {
            it.documentId.startsWith(prefix) && '/' !in it.documentId.removePrefix(prefix)
        }
    }

    override fun openFile(treeUri: String, documentId: String): DeviceFile {
        check()
        val bytes = documents[documentId]?.second ?: throw FileNotFoundException(documentId)
        opened += documentId
        return object : DeviceFile {
            override val sizeBytes: Long = bytes.size.toLong()

            override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(size, bytes.size - position.toInt())
                System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                return n
            }

            override fun close() {}
        }
    }

    override fun takePermission(treeUri: String) {
        check()
        permissions += treeUri
    }

    override fun releasePermission(treeUri: String) {
        released += treeUri
        permissions -= treeUri
    }

    override fun persistedTreeUris(): List<String> = permissions.toList()

    private fun check() {
        if (securityFailure) throw SecurityException("permission revoked")
    }

    private fun id(path: String) = "$ROOT_ID/$path"

    companion object {
        const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3ARadio"
        private const val ROOT_ID = "doc:"
    }
}
