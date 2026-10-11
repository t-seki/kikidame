package dev.tseki.kikidame.data.device

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [DeviceDocuments] を `ContentResolver` と `DocumentsContract` で引く（#199）。依存（`androidx.documentfile`）は足さず、SDK の標準だけで書く。
 * 単体テストしない（provider と ParcelFileDescriptor が要るため）。パスの解決と失敗の扱いは [DeviceFolderTree] の側で試す。
 */
class ContentResolverDeviceDocuments(private val resolver: ContentResolver) : DeviceDocuments {
    override fun hasReadPermission(treeUri: String): Boolean {
        val uri = Uri.parse(treeUri)
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    override fun rootDocumentId(treeUri: String): String = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))

    /**
     * 行の無い cursor が返ったら「無い」（null）。cursor そのものが null のときは失敗（[IOException]）にする。
     * `ContentResolver.query` の仕様は、null を「provider が null を返したか、provider が落ちたとき」としており、
     * 無いのか落ちたのかを見分けられないため（AOSP の `DocumentsProvider.query` がファイルの無いときに null を返すかは、
     * ソースを読んで確かめていない。未確認）。
     */
    override fun query(treeUri: String, documentId: String): DeviceDocument? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null) ?: throw IOException("provider returned no cursor: $documentId")
        return cursor.use { if (it.moveToFirst()) it.toDocument() else null }
    }

    override fun children(treeUri: String, documentId: String): List<DeviceDocument> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), documentId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null) ?: throw FileNotFoundException("cannot list: $documentId")
        return cursor.use {
            buildList { while (it.moveToNext()) add(it.toDocument()) }
        }
    }

    override fun openFile(treeUri: String, documentId: String): DeviceFile {
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId)
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("cannot open: $documentId")
        return DescriptorFile(descriptor)
    }

    override fun takePermission(treeUri: String) {
        resolver.takePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releasePermission(treeUri: String) {
        val uri = Uri.parse(treeUri)
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.releasePersistableUriPermission(uri, flags)
    }

    override fun persistedTreeUris(): List<String> = resolver.persistedUriPermissions.map { it.uri.toString() }

    private fun Cursor.toDocument(): DeviceDocument {
        val mime = getString(MIME)
        return DeviceDocument(
            documentId = getString(ID),
            name = getString(NAME).orEmpty(),
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = if (isNull(SIZE)) 0L else getLong(SIZE),
            lastModifiedMillis = if (isNull(MODIFIED)) null else getLong(MODIFIED),
        )
    }

    private companion object {
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        const val ID = 0
        const val NAME = 1
        const val MIME = 2
        const val SIZE = 3
        const val MODIFIED = 4
    }
}

/** 開いたファイルを、位置を指定して読む（`FileChannel` の位置指定の読み出し。チャネルの位置は動かさない）。 */
private class DescriptorFile(private val descriptor: ParcelFileDescriptor) : DeviceFile {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val channel: FileChannel = input.channel

    override val sizeBytes: Long = descriptor.statSize

    override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, offset, size), position)
    }

    override fun close() {
        try {
            input.close()
        } finally {
            descriptor.close()
        }
    }
}
