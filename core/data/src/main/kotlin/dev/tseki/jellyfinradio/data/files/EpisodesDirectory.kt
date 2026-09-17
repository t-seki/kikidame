package dev.tseki.jellyfinradio.data.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手元の音声ファイルの置き場 `getExternalFilesDir("episodes")`。
 * `filesDir` に変えないこと — root 無しの `adb push` が通らない。シードもダウンロードも同じ木を使う。
 */
@Singleton
class EpisodesDirectory @Inject constructor(@ApplicationContext private val context: Context) {
    val root: File? get() = context.getExternalFilesDir(DIR_NAME)

    fun resolve(relativePath: String): File = File(requireNotNull(root) { "external files dir unavailable" }, relativePath)

    companion object {
        const val DIR_NAME = "episodes"
    }
}
