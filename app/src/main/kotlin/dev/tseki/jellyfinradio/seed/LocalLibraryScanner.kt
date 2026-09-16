package dev.tseki.jellyfinradio.seed
import dev.tseki.jellyfinradio.domain.AiredAt
import dev.tseki.jellyfinradio.domain.ScannedEpisode
import java.io.File
import kotlin.time.Duration
import kotlin.time.Instant
/**
 * `<root>/<放送局>/<番組>/<ファイル>.m4a|.mp3` を走査して [ScannedEpisode] にする。
 * radirec-tool の出力ツリーをそのまま `adb push` できる階層。
 */
class LocalLibraryScanner(private val metadataReader: AudioMetadataReader) {
    fun scan(root: File): List<ScannedEpisode> {
        val stations = root.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }
        return stations.flatMap { station ->
            val programs = station.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }
            programs.flatMap { program ->
                program.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                    .orEmpty()
                    .sortedBy { it.nameWithoutExtension }
                    .map { file -> toScanned(station.name, program.name, file) }
            }
        }
    }
    private fun toScanned(stationName: String, programName: String, file: File): ScannedEpisode {
        val metadata = metadataReader.read(file)
        val title = file.nameWithoutExtension
        return ScannedEpisode(
            stationName = stationName,
            programName = programName,
            title = title,
            path = file.absolutePath,
            airedAt = AiredAt.resolve(
                tagDate = AiredAt.dateFromTag(metadata.dateTag),
                fileNameDate = AiredAt.dateFromFileName(title),
                modifiedAt = Instant.fromEpochMilliseconds(file.lastModified()),
            ),
            runtime = metadata.duration ?: Duration.ZERO,
            sizeBytes = file.length(),
            container = file.extension.lowercase(),
        )
    }
    companion object {
        val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3")
    }
}
