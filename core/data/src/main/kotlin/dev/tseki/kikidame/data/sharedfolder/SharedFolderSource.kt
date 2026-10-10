package dev.tseki.kikidame.data.sharedfolder

import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * 共有フォルダ（CONTEXT.md）を取得元（[SourceGateway]）として見せる（ADR 0010、#197）。[FolderTree] の上で走査する。
 *
 * - 構成はちょうど `<配信元>/<番組>/<各回のファイル>` の 3 段（epic #195 の決定 5）。2 段目のフォルダはすべて番組
 *   （音声ファイルが 0 本でも番組）。3 段目の、拡張子が [AUDIO_EXTENSIONS] のファイルだけを各回とし、
 *   それ以外（1・2 段目のファイル、4 段目以下、音声でないファイル）は黙って無視する。配信元と番組はフォルダの名前で決める
 * - 取得元 ID は相対パス（決定 7）。番組は `<配信元>/<番組>`、各回は `<配信元>/<番組>/<ファイル名>`
 * - 回の情報はタグから読む（決定 8・9）。取得元 ID・サイズ・更新日時がどれも前の走査（[ScannedFiles]）と同じ回は、
 *   タグを読み直さずに前の値を使う
 * - 木の操作が例外を投げたら [ServerException.Unreachable]（今の Jellyfin と同じく到達不能、判断保留）。
 *   番組のフォルダが無ければ [fetchProgram] が null（消失）
 *
 * 接続の情報は [tree] の側で持つ。Hilt の bind はまだしない（取得元を選ぶ画面と合わせて #198 で行う）。
 */
class SharedFolderSource(
    private val tree: FolderTree,
    private val tags: TagReader,
    private val scanned: ScannedFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SourceGateway {

    /** 全走査。返す一覧は完全な一覧（ADR 0004）。共有フォルダの根が無ければ到達不能。 */
    override suspend fun fetchAll(): SourceSnapshot {
        val previous = scanned.load()
        return onTree {
            val root = tree.list(ROOT) ?: throw IOException("shared folder root not found")
            val programs = ArrayList<SourceProgram>()
            val episodes = ArrayList<SourceEpisode>()
            for (publisher in root.directories()) {
                val publisherPath = publisher.name
                // 走査の途中で消えたフォルダは、無いものとして扱う
                val programDirs = tree.list(publisherPath)?.directories() ?: continue
                for (program in programDirs) {
                    val programPath = "$publisherPath/${program.name}"
                    val files = tree.list(programPath) ?: continue
                    val programId = SourceItemId(programPath)
                    programs += SourceProgram(sourceId = programId, name = program.name, publisherName = publisher.name)
                    episodes += episodesIn(programId, files, previous)
                }
            }
            SourceSnapshot(programs, episodes)
        }
    }

    /** その番組のフォルダの中身だけで答える。フォルダが無ければ空。 */
    override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> {
        if (programSegments(programId) == null) return emptyList()
        val previous = scanned.load()
        return onTree {
            episodesIn(programId, tree.list(programId.value).orEmpty(), previous)
        }
    }

    /** 番組のフォルダが無ければ null（消失）。取得元 ID が `<配信元>/<番組>` の形でなければ null。 */
    override suspend fun fetchProgram(programId: SourceItemId): SourceProgram? {
        val (publisher, program) = programSegments(programId) ?: return null
        return onTree {
            tree.list(programId.value)?.let { SourceProgram(sourceId = programId, name = program, publisherName = publisher) }
        }
    }

    /**
     * 各回のファイルを [rangeStart] から開く。木はオフセットから読めるので、[rangeStart] > 0 なら常に続きから返す。
     * ファイルが無ければ [ServerException.Failed]、それ以外の失敗は [ServerException.Unreachable]。
     */
    override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream {
        val file = onTree {
            try {
                tree.open(episodeId.value, rangeStart)
            } catch (e: FileNotFoundException) {
                throw ServerException.Failed("not found: ${episodeId.value}", e)
            }
        }
        return DownloadStream(
            resumedFrom = rangeStart.takeIf { it > 0 },
            totalBytes = file.sizeBytes,
            body = file.body,
        )
    }

    private fun episodesIn(programId: SourceItemId, entries: List<FolderEntry>, previous: Map<SourceItemId, ScannedFile>): List<SourceEpisode> =
        entries.filter { !it.isDirectory && extensionOf(it.name) in AUDIO_EXTENSIONS }
            .sortedBy { it.name }
            .map { episodeOf(programId, it, previous) }

    private fun episodeOf(programId: SourceItemId, file: FolderEntry, previous: Map<SourceItemId, ScannedFile>): SourceEpisode {
        val id = SourceItemId("${programId.value}/${file.name}")
        // Room には ms で入るので、ms に揃えてから比べる（ms より細かい更新日時が来ても同じとみなす。SMB の実装から何が来るかは #198 で確かめる。未確認）
        val modifiedAt = file.modifiedAt.truncatedToMillis()
        val cached = previous[id]?.takeIf { it.sizeBytes == file.sizeBytes && it.modifiedAt == modifiedAt }
        val values = cached?.let { TagValues(it.title, it.publishedAt, it.runtime, it.performers) }
            ?: tagValues(tags.read(tree, id.value, file.sizeBytes), file.name, modifiedAt)
        return SourceEpisode(
            sourceId = id,
            programSourceId = programId,
            title = values.title,
            publishedAt = values.publishedAt,
            addedAt = modifiedAt,
            runtime = values.runtime,
            sizeBytes = file.sizeBytes,
            container = extensionOf(file.name),
            performers = values.performers,
            sourceFileSize = file.sizeBytes,
            sourceModifiedAt = modifiedAt,
        )
    }

    private data class TagValues(val title: String, val publishedAt: Instant, val runtime: Duration, val performers: List<String>)

    /** タグが無いときの補い方（epic #195 の決定 9）。取り込み日時はファイルの更新日時。 */
    private fun tagValues(tags: AudioTags, fileName: String, modifiedAt: Instant): TagValues {
        val date = TagDate.parse(tags.date) ?: modifiedAt.toLocalDateTime(PublishedAt.ZONE).date
        return TagValues(
            title = tags.title?.trim()?.takeIf { it.isNotEmpty() } ?: fileName.substringBeforeLast('.'),
            publishedAt = date.atStartOfDayIn(PublishedAt.ZONE),
            runtime = tags.duration?.takeIf { it.isPositive() } ?: Duration.ZERO,
            performers = listOfNotNull(tags.artist?.trim()?.takeIf { it.isNotEmpty() }),
        )
    }

    /** 木の操作の失敗を到達不能に正規化する。 */
    private suspend fun <T> onTree(block: () -> T): T = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerException) {
            throw e
        } catch (e: Exception) {
            throw ServerException.Unreachable(e)
        }
    }

    companion object {
        private const val ROOT = ""

        /** 各回とみなす拡張子（小文字で比べる）。 */
        val AUDIO_EXTENSIONS: Set<String> = setOf("m4a", "mp3", "aac", "ogg", "opus", "flac", "wav")

        private fun extensionOf(name: String): String =
            name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

        private fun List<FolderEntry>.directories(): List<FolderEntry> = filter { it.isDirectory }.sortedBy { it.name }

        /** `<配信元>/<番組>` を 2 つに分ける。形が違えば null。 */
        private fun programSegments(id: SourceItemId): Pair<String, String>? {
            val parts = id.value.split('/')
            return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
        }

        private fun Instant.truncatedToMillis(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())
    }
}
