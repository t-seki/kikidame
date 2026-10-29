package dev.tseki.kikidame.data.device

import dev.tseki.kikidame.domain.SourceException
import java.io.FileNotFoundException

/**
 * 端末のフォルダ（SAF）の文書を引く窓口。[DeviceFolderTree] が使い、実装（[ContentResolverDeviceDocuments]）は
 * `ContentResolver` と `DocumentsContract` で引く。テストではメモリ上のものに差し替える。
 *
 * どのメソッドも blocking。権限が無い・取り消されたときは [SecurityException] を投げてよい（[DeviceFolderTree] が
 * [SourceException.Unauthorized] に変える）。
 */
interface DeviceDocuments {
    /** [treeUri] の永続的な読み取りの権限を持っているか。 */
    fun hasReadPermission(treeUri: String): Boolean

    /** 選んだフォルダ（根）の文書 ID。 */
    fun rootDocumentId(treeUri: String): String

    /** 文書の情報。provider が「その文書は無い」と答えたら（行の無い結果）null。無いのか失敗なのか分からないときは例外を投げる。 */
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
