package dev.tseki.jellyfinradio.data.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手元の音声ファイルの置き場 `getExternalFilesDir("episodes")`。
 * `filesDir` に変えないこと — 外部ストレージなら `adb pull` でファイルを取り出せる（root 無し）。
 */
@Singleton
class EpisodesDirectory @Inject constructor(@ApplicationContext private val context: Context) {
    val root: File? get() = context.getExternalFilesDir(DIR_NAME)

    fun resolve(relativePath: String): File = File(requireNotNull(root) { "external files dir unavailable" }, relativePath)

    companion object {
        const val DIR_NAME = "episodes"
    }
}
