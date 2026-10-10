package dev.tseki.kikidame.data.sharedfolder

import dev.tseki.kikidame.data.smb.CloseableFolderTree
import dev.tseki.kikidame.domain.ServerException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.time.Instant

/**
 * 端末のフォルダ（SAF、#199）の文書を引く窓口。[DeviceFolderTree] が使い、実装（[ContentResolverDeviceDocuments]）は
 * `ContentResolver` と `DocumentsContract` で引く。テストではメモリ上のものに差し替える。
 *
 * どのメソッドも blocking。権限が無い・取り消されたときは [SecurityException] を投げてよい（[DeviceFolderTree] が
 * [ServerException.Unauthorized] に変える）。
 */
interface DeviceDocuments {
    /** [treeUri] の永続的な読み取りの権限を持っているか。 */
    fun hasReadPermission(treeUri: String): Boolean

    /** 選んだフォルダ（根）の文書 ID。 */
    fun rootDocumentId(treeUri: String): String

    /** 文書の情報。無ければ null。 */
    fun query(treeUri: String, documentId: String): DeviceDocument?

    /** フォルダの直下の文書。 */
    fun children(treeUri: String, documentId: String): List<DeviceDocument>

    /** ファイルを読むために開く。無ければ [FileNotFoundException]。 */
    fun openFile(treeUri: String, documentId: String): DeviceFile

    /** [treeUri] の永続的な読み取りの権限を取る（`takePersistableUriPermission`）。 */
    fun takePermission(treeUri: String)

    /** [treeUri] の永続的な権限を手放す（`releasePersistableUriPermission`）。持っていなければ何もしない。 */
    fun releasePermission(treeUri: String)

    /** 持っている永続的な権限のツリーの URI。 */
    fun persistedTreeUris(): List<String>
}

/** 文書の 1 件。[lastModifiedMillis] は provider が返さなければ null。 */
data class DeviceDocument(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long?,
)

/** 開いたファイル。位置を指定して読む。 */
interface DeviceFile : AutoCloseable {
    /** ファイル全体のサイズ。分からなければ -1。 */
    val sizeBytes: Long

    /** [position] から最大 [size] バイトを [buffer] の [offset] 以降に読み、読んだバイト数を返す。末尾以降なら -1。 */
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int

    override fun close()
}

/**
 * 端末のフォルダ（SAF、#199）を [FolderTree] として見せる。[treeUri] は `ACTION_OPEN_DOCUMENT_TREE` で選んだツリー。
 * 読むだけで、書き込み・削除はしない（ADR 0010 の決定 6）。
 *
 * - 相対パスから文書 ID への対応は、親のフォルダを一覧して名前で探し、この木の中に持つ（木は 1 回の操作ごとに作って捨てる）。
 *   文書 ID の形（`primary:Radio/...` など）を組み立てる前提にはしない
 * - 最初の操作で、永続的な読み取りの権限があることと、選んだフォルダ（根）があることを確かめる。どちらかが無ければ
 *   [ServerException.Unauthorized]（ログアウトにして選び直させる。#199 の決定 5）。根が無いのを [list] の null で返すと、
 *   全走査が到達不能で終わるか、`fetchProgram` が全番組を消失と判断するため、null にしない
 * - [list] が null を返すのは、根はあって、そのパスのフォルダが無い（またはフォルダでない）ときだけ
 * - 権限の失敗（[SecurityException]）は [ServerException.Unauthorized] に変える。そのほかの失敗はそのまま投げる
 *   （全走査は [SharedFolderSource] がやり直す）
 * - 更新日時を provider が返さなければ 1970-01-01（エポック）で補う（毎回同じ値になるので、タグを読み直さない）
 * - 接続を持たないので [reset] は何もしない。[read] は同じファイルを続けて読むことが多いので、最後に開いたファイルを持ち回し、[close] で閉じる
 */
class DeviceFolderTree(
    private val documents: DeviceDocuments,
    private val treeUri: String,
) : CloseableFolderTree {
    /** 相対パス → 文書。根は空文字列。 */
    private val known = HashMap<String, DeviceDocument>()
    private var rootChecked = false
    private var readHandle: Pair<String, DeviceFile>? = null

    @Synchronized
    override fun list(path: String): List<FolderEntry>? = guard {
        val folder = resolve(path) ?: return@guard null
        if (!folder.isDirectory) return@guard null
        listChildren(path, folder).map {
            FolderEntry(
                name = it.name,
                isDirectory = it.isDirectory,
                sizeBytes = it.sizeBytes,
                modifiedAt = Instant.fromEpochMilliseconds(it.lastModifiedMillis ?: 0L),
            )
        }
    }

    @Synchronized
    override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int = guard {
        val file = readHandle?.takeIf { it.first == path }?.second ?: openFile(path).also { opened ->
            closeQuietly { readHandle?.second?.close() }
            readHandle = path to opened
        }
        file.read(position, buffer, offset, size)
    }

    @Synchronized
    override fun open(path: String, offset: Long): FolderFileStream = guard {
        val document = resolve(path)
        val file = openFile(path)
        val size = file.sizeBytes.takeIf { it >= 0 } ?: document?.sizeBytes ?: -1L
        FolderFileStream(size, DeviceFileInputStream(file, offset))
    }

    @Synchronized
    override fun close() {
        closeQuietly { readHandle?.second?.close() }
        readHandle = null
    }

    private fun openFile(path: String): DeviceFile {
        val document = resolve(path)?.takeIf { !it.isDirectory } ?: throw FileNotFoundException(path)
        return documents.openFile(treeUri, document.documentId)
    }

    /** [path] の文書。根はあって、そのパスが無ければ null。 */
    private fun resolve(path: String): DeviceDocument? {
        val root = root()
        if (path.isEmpty()) return root
        known[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        val parent = resolve(parentPath)?.takeIf { it.isDirectory } ?: return null
        listChildren(parentPath, parent)
        return known[path]
    }

    private fun listChildren(path: String, folder: DeviceDocument): List<DeviceDocument> {
        val children = documents.children(treeUri, folder.documentId)
        for (child in children) known[if (path.isEmpty()) child.name else "$path/${child.name}"] = child
        return children
    }

    /** 根の文書。最初に呼ばれたときに、権限と根があることを確かめる。 */
    private fun root(): DeviceDocument {
        known[ROOT]?.takeIf { rootChecked }?.let { return it }
        if (!documents.hasReadPermission(treeUri)) throw ServerException.Unauthorized(IOException("no persisted permission: $treeUri"))
        val root = try {
            documents.query(treeUri, documents.rootDocumentId(treeUri))
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // 根が消えた・SD カードが外れたときに provider が投げる（null で返すかは provider による）
            throw ServerException.Unauthorized(e)
        }
        if (root == null || !root.isDirectory) throw ServerException.Unauthorized(IOException("folder not found: $treeUri"))
        known[ROOT] = root
        rootChecked = true
        return root
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw ServerException.Unauthorized(e)
    }

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val ROOT = ""
    }
}

/** [DeviceFile] を [offset] から読む [InputStream]。閉じるとファイルも閉じる。 */
private class DeviceFileInputStream(private val file: DeviceFile, offset: Long) : InputStream() {
    private var position = offset

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = try {
            file.read(position, b, off, len)
        } catch (e: SecurityException) {
            throw IOException("device folder read failed: ${e.message}", e)
        }
        if (n <= 0) return -1
        position += n
        return n
    }

    override fun close() {
        file.close()
    }
}
