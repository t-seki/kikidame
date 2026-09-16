package dev.tseki.jellyfinradio.seed
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.jellyfinradio.domain.ImportResult
import dev.tseki.jellyfinradio.domain.LocalImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
/**
 * デバッグ用シード。`getExternalFilesDir("episodes")` を走査して取り込む。
 * 置き場所を `filesDir` に変えないこと — root 無しの `adb push` が通らない。
 */
class SeedLocalLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: LocalImportRepository,
) {
    val root: File?
        get() = context.getExternalFilesDir(EPISODES_DIR)
    suspend fun run(): ImportResult = withContext(Dispatchers.IO) {
        val root = root ?: return@withContext ImportResult(0, 0, 0)
        val scanned = LocalLibraryScanner(MediaMetadataRetrieverReader()).scan(root)
        importRepository.import(scanned)
    }
    companion object {
        const val EPISODES_DIR = "episodes"
    }
}
