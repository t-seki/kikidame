package dev.tseki.jellyfinradio.seed
import dev.tseki.jellyfinradio.data.files.EpisodesDirectory
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
    private val directory: EpisodesDirectory,
    private val importRepository: LocalImportRepository,
) {
    val root: File?
        get() = directory.root
    suspend fun run(): ImportResult = withContext(Dispatchers.IO) {
        val root = root ?: return@withContext ImportResult(0, 0, 0)
        val scanned = LocalLibraryScanner(MediaMetadataRetrieverReader()).scan(root)
        importRepository.import(scanned)
    }
}
