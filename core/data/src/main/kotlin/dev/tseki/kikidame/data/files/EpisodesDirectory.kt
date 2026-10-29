package dev.tseki.kikidame.data.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手元の音声ファイルの置き場 `getExternalFilesDir("episodes")`。
 * 外部ストレージに置く必須の理由は無いが、デバッグで `adb push` / `adb pull` できる利点があり、
 * 変えると既存のファイルの場所が変わるので据え置く。
 */
@Singleton
class EpisodesDirectory @Inject constructor(@ApplicationContext private val context: Context) {
    val root: File? get() = context.getExternalFilesDir(DIR_NAME)

    fun resolve(relativePath: String): File = File(requireNotNull(root) { "external files dir unavailable" }, relativePath)

    companion object {
        const val DIR_NAME = "episodes"
    }
}
