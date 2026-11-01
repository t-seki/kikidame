package dev.tseki.kikidame.data.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 番組の画像（CONTEXT.md、ADR 0009）の置き場 `filesDir/program_images`。同期が取得元から取った画像をここに置き、
 * 表示はここから読む（表示のときに取得元へ行かない）。音声ファイル（[EpisodesDirectory]）と違って `adb push` で入れることは無いので、
 * アプリの内部の領域に置く。
 *
 * ファイル名は `<番組のローカル ID>-<版の SHA-256 の頭 16 桁>.img`。画像が変わると名前も変わるので、表示側（Coil）の
 * メモリのキャッシュがパスで引いても古い画像を出さない。中身の形式（JPEG・PNG など）は名前に出さず、読む側が中身から判別する。
 * どのメソッドも blocking（呼び出し側が IO のスレッドで呼ぶ）。
 */
@Singleton
class ProgramImageFiles(val root: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR_NAME))

    /** [bytes] を番組 [programId] の版 [version] の画像として書き、そのファイルを返す。一時ファイルに書いてから名前を変える。 */
    fun write(programId: Long, version: String, bytes: ByteArray): File {
        root.mkdirs()
        val file = File(root, "$programId-${digest(version)}$SUFFIX")
        val tmp = File(root, file.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not rename ${tmp.path} to ${file.path}")
        }
        return file
    }

    fun exists(path: String): Boolean = File(path).isFile

    fun delete(path: String) {
        File(path).delete()
    }

    /** [keep]（絶対パス）に無いファイルを消す。番組の行が消えた・画像が変わったときに消せずに残ったファイルの掃除。 */
    fun deleteAllExcept(keep: Set<String>) {
        root.listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }

    /** 全部消す（取得元を変えてやり直すとき、ADR 0007 の決定 2）。消せなかったファイルの数を返す。 */
    fun deleteAll(): Int = root.listFiles()?.count { !it.delete() } ?: 0

    private fun digest(version: String): String =
        MessageDigest.getInstance("SHA-256").digest(version.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        const val DIR_NAME = "program_images"
        private const val SUFFIX = ".img"
        private const val TMP_SUFFIX = ".tmp"
    }
}
