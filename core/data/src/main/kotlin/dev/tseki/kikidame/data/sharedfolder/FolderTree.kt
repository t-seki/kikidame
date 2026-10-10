package dev.tseki.kikidame.data.sharedfolder

import java.io.InputStream
import kotlin.time.Instant

/**
 * 共有フォルダ（CONTEXT.md）の中身をたどるための、フォルダの木の最小の抽象（#197）。
 * SMB（#198）と端末のフォルダ（SAF、#199）がこれを実装し、[SharedFolderSource] がこの上で走査する。
 *
 * - パスは共有フォルダの根からの相対パスで、区切りは `/`。根は空文字列
 * - 読むだけで、書き込み・削除はしない（ADR 0010 の決定 6）
 * - どのメソッドも blocking。呼び出し側が IO のスレッドで呼ぶ（[android.media.MediaDataSource.readAt] が同期で呼ぶため）
 * - 失敗（接続・権限・読み取りの失敗）は例外を投げる。「無い」は例外でなく戻り値で表す（[list] の null）
 */
interface FolderTree {
    /** [path] のフォルダの直下の子。フォルダが無い（またはフォルダでない）なら null。 */
    fun list(path: String): List<FolderEntry>?

    /**
     * ファイルの部分読み（タグの読み取り用）。[position] から最大 [size] バイトを [buffer] の [offset] 以降に読み、
     * 読んだバイト数を返す。[position] がファイルの末尾以降なら -1。
     */
    fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int

    /** ファイルを [offset] から読むストリーム（ダウンロードの続きから取る `.part` の再開用）。ファイルが無ければ [java.io.FileNotFoundException]。 */
    fun open(path: String, offset: Long): FolderFileStream
}

/** フォルダの子の 1 件。 */
data class FolderEntry(
    val name: String,
    val isDirectory: Boolean,
    /** ファイルのサイズ（バイト）。フォルダでは意味を持たない。 */
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

/** [FolderTree.open] の結果。[sizeBytes] はファイル全体のサイズ（[body] の残りではない）。 */
class FolderFileStream(
    val sizeBytes: Long,
    val body: InputStream,
)
