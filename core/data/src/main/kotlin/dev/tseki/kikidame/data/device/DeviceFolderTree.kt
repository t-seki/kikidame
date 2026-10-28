package dev.tseki.kikidame.data.device

import dev.tseki.kikidame.data.sharedfolder.CloseableFolderTree
import dev.tseki.kikidame.data.sharedfolder.FolderEntry
import dev.tseki.kikidame.data.sharedfolder.FolderFileStream
import dev.tseki.kikidame.data.sharedfolder.FolderTree
import dev.tseki.kikidame.data.sharedfolder.LastOpened
import dev.tseki.kikidame.data.sharedfolder.PositionalInputStream
import dev.tseki.kikidame.data.sharedfolder.SharedFolderSource
import dev.tseki.kikidame.domain.SourceException
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Instant

/**
 * 端末のフォルダ（SAF、#199）を [FolderTree] として見せる。[treeUri] は `ACTION_OPEN_DOCUMENT_TREE` で選んだツリー。
 * 読むだけで、書き込み・削除はしない（ADR 0010 の決定 6）。
 *
 * - 相対パスから文書 ID への対応は、親のフォルダを一覧して名前で探し、この木の中に持つ（木は 1 回の操作ごとに作って捨てる）。
 *   文書 ID の形（`primary:Radio/...` など）を組み立てる前提にはしない
 * - 最初の操作で、永続的な読み取りの権限があることと、選んだフォルダ（根）があることを確かめる。権限が無い・取り消された、
 *   または根が無いと**分かった**ときだけ [SourceException.Unauthorized]（ログアウトにして選び直させる。#199 の決定 5）。分かったとするのは次のとき:
 *   - 持っている永続権限（`persistedUriPermissions`）に、このツリーの読み取りが無い。Android Developers の documents-files のページは、
 *     選んだフォルダの移動・削除で永続権限が失われるとしているので、フォルダが移された・消されたときも多くはここで分かる
 *   - provider が [SecurityException] を投げた（`ContentResolver` は URI の権限の無いアクセスを [SecurityException] で断る）
 *   - 根の文書を問い合わせて、provider が「無い」と答えた（[DeviceDocuments.query] の null。行の無い結果）、または根がフォルダでない
 *
 *   それ以外の失敗（cursor が null、[IllegalArgumentException] などの例外）は、無いのか一時的な失敗（provider が落ちた、SD カードが
 *   一時的に外れたなど）なのかを見分けられないので、そのまま投げて通常の失敗に倒す（全走査は [SharedFolderSource] がやり直し、
 *   続けば到達不能。手元のデータを守るため、SMB と同じ扱い）。根が消えたときに provider がどれを返すかは実機で確かめていない（未確認。
 *   `docs/device-checklists.md` の「実機チェックリスト（#199）」）
 * - 根が無いのを [list] の null で返さない（全走査が到達不能で終わるか、`fetchProgram` が全番組を消失と判断するため）。
 *   [list] が null を返すのは、根はあって、そのパスのフォルダが無い（またはフォルダでない）ときだけ
 * - [list]・[read]・[open] の呼び出しの中で出た [SecurityException] は [SourceException.Unauthorized] に変える。そのほかの失敗はそのまま投げる。
 *   [open] が返したストリーム（ダウンロードの本体）を読む途中の [SecurityException] は、[IOException] に変える（ストリームの読み出しは
 *   [IOException] を前提にしている）。そのダウンロードは失敗として扱われて後でやり直され、次の同期で根の確認がログアウトに導く
 * - 更新日時を provider が返さなければ 1970-01-01（エポック）で補う（毎回同じ値になるので、タグを読み直さない）。
 *   そのときファイルの版（大きさと更新日時の組、ADR 0014）は大きさだけで決まるので、大きさの変わらない書き換えには気づけない（差し替えない）
 * - 接続を持たないので [reset] は何もしない。[read] は同じファイルを続けて読むことが多いので、最後に開いたファイルを持ち回し（[LastOpened]）、[close] で閉じる
 */
class DeviceFolderTree(
    private val documents: DeviceDocuments,
    private val treeUri: String,
) : CloseableFolderTree {
    /** 相対パス → 文書。根は空文字列。 */
    private val known = HashMap<String, DeviceDocument>()
    private var rootChecked = false
    private val lastOpened = LastOpened<DeviceFile>()

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
        val file = lastOpened.get(path, ::openFile)
        file.read(position, buffer, offset, size)
    }

    @Synchronized
    override fun open(path: String, offset: Long): FolderFileStream = guard {
        val document = resolve(path)
        val file = openFile(path)
        val size = file.sizeBytes.takeIf { it >= 0 } ?: document?.sizeBytes ?: -1L
        FolderFileStream(size, fileInputStream(file, offset))
    }

    @Synchronized
    override fun close() {
        lastOpened.close()
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
        if (!documents.hasReadPermission(treeUri)) throw SourceException.Unauthorized(IOException("no persisted permission: $treeUri"))
        // SecurityException は guard が Unauthorized にする。それ以外の例外は、無いと分かったわけではないので通常の失敗としてそのまま投げる
        val root = documents.query(treeUri, documents.rootDocumentId(treeUri))
        if (root == null || !root.isDirectory) throw SourceException.Unauthorized(IOException("folder not found: $treeUri"))
        known[ROOT] = root
        rootChecked = true
        return root
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw SourceException.Unauthorized(e)
    }

    private companion object {
        const val ROOT = ""
    }
}

/**
 * [DeviceFile] を [offset] から読む [java.io.InputStream]。閉じるとファイルも閉じる。
 * 読み取り中の [SecurityException] は [IOException] に変え、それ以外の例外はそのまま投げる。
 */
private fun fileInputStream(file: DeviceFile, offset: Long) = PositionalInputStream(
    offset = offset,
    readAt = { position, buffer, off, len -> file.read(position, buffer, off, len) },
    closeSource = { file.close() },
    mapError = { e ->
        // ストリームの読み出しは IOException を前提にしている。ダウンロードは失敗として後でやり直され、次の同期で根の確認がログアウトに導く
        if (e is SecurityException) IOException("device folder read failed: ${e.message}", e) else e
    },
)
