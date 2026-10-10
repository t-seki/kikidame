package dev.tseki.kikidame.data.sharedfolder

import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.ScanListener
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 共有フォルダ（CONTEXT.md）を取得元（[SourceGateway]）として見せる（ADR 0010、#197）。[FolderTree] の上で走査する。
 *
 * - 構成はちょうど `<配信元>/<番組>/<各回のファイル>` の 3 段（epic #195 の決定 5）。2 段目のフォルダはすべて番組
 *   （音声ファイルが 0 本でも番組）。3 段目の、拡張子が [AUDIO_EXTENSIONS] のファイルだけを各回とし、
 *   それ以外（1・2 段目のファイル、4 段目以下、音声でないファイル）は黙って無視する。配信元と番組はフォルダの名前で決める
 * - 名前が `.`・`@`・`#` で始まるフォルダとファイル（NAS や OS が作るもの）は、どの段でも無視する（[isIgnoredName]、#197 の追加の決定）。
 *   [fetchProgram]・[fetchProgramEpisodes] も同じ規則で、そういう名前の番組は無いものとして扱う
 * - 取得元 ID は相対パス（決定 7）。番組は `<配信元>/<番組>`、各回は `<配信元>/<番組>/<ファイル名>`
 * - 回の情報はタグから読む（決定 8・9）。取得元 ID・サイズ・更新日時がどれも前の走査（[ScannedFiles]）と同じ回は、
 *   タグを読み直さずに前の値を使う
 * - 全走査は一覧を先に全部取ってから番組ごとにタグを読み、読み終えた番組を [ScanListener] に流す（#209 の決定 1。epic #195 の決定 8 の
 *   「全部読んでからまとめて取り込む」を改めた）。取り込み済みの回は DB に大きさと更新日時を持つので、途中で止まっても次の走査は
 *   その回のタグを読まない（#209 の決定 2）。木の操作が失敗したら接続を張り直してやり直し、続けて 3 回失敗したら到達不能（#209 の決定 4）
 * - 木の操作が例外を投げたら（全走査ではやり直しても失敗したら）[ServerException.Unreachable]（今の Jellyfin と同じく到達不能、判断保留）。
 *   番組のフォルダが無ければ [fetchProgram] が null（消失）
 *
 * 接続の情報は [tree] の側で持つ。取得元の種類による切り替えは `SessionSourceGateway`（#198）が行う。
 */
class SharedFolderSource(
    private val tree: FolderTree,
    private val tags: TagReader,
    private val scanned: ScannedFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelay: Duration = RETRY_DELAY,
) : SourceGateway {

    /** 全走査。途中経過は流さない（[fetchAll] の `listener` 版を [ScanListener.None] で呼ぶ）。 */
    override suspend fun fetchAll(): SourceSnapshot = fetchAll(ScanListener.None)

    /**
     * 全走査（#209 の決定 1・4）。返す一覧は完全な一覧（ADR 0004）。共有フォルダの根が無ければ到達不能。
     *
     * 1. 一覧（名前・大きさ・更新日時）を先に全部取り、タグを読む回の数を数える
     * 2. 番組ごとにタグを読む（前の走査と同じ回は読まない）。1 本読むごとに [ScanListener.onTagProgress]、
     *    タグを読んだ番組は読み終えるたびに [ScanListener.onProgramScanned] に流す
     *
     * 木の操作（一覧・タグの読み取り）が失敗したら、[FolderTree.reset] で接続を捨ててから同じ操作をやり直す（タグは同じファイルの頭から）。
     * 続けて [MAX_CONSECUTIVE_FAILURES] 回失敗したら到達不能で終わる。成功を挟めば数え直す。
     * [ServerException]（認証・権限など、やり直しても変わらない失敗）はやり直さずにそのまま投げる。ファイルが無い（[FileNotFoundException]）も
     * やり直さず、今までどおり [ServerException.Unreachable] に包んで投げる。
     */
    override suspend fun fetchAll(listener: ScanListener): SourceSnapshot {
        val previous = scanned.load()
        val retry = Retry()
        val listed = listLibrary(retry)
        val total = listed.sumOf { (program, files) -> files.count { cachedValues(program.sourceId, it, previous) == null } }
        var read = 0
        if (total > 0) listener.onTagProgress(0, total)
        val episodes = ArrayList<SourceEpisode>()
        for ((program, files) in listed) {
            val programEpisodes = ArrayList<SourceEpisode>(files.size)
            var readHere = false
            for (file in files) {
                // タグの読み取り（まとめ読みの前の計測で m4a は 1 本 0.9〜1.3 秒。#209）の合間に、取り消されていれば止まる
                currentCoroutineContext().ensureActive()
                val id = episodeIdOf(program.sourceId, file)
                val values = cachedValues(program.sourceId, file, previous) ?: run {
                    val tags = retry { tags.read(tree, id.value, file.sizeBytes) }
                    read++
                    readHere = true
                    listener.onTagProgress(read, total)
                    tagValues(tags, file.name, file.modifiedAt.truncatedToMillis())
                }
                programEpisodes += episodeOf(program.sourceId, file, values)
            }
            if (readHere) listener.onProgramScanned(program, programEpisodes)
            episodes += programEpisodes
        }
        return SourceSnapshot(listed.map { it.first }, episodes)
    }

    /** 全走査の 1 段目: 番組と、その各回のファイル（並べ替え・絞り込み済み）の一覧。 */
    private suspend fun listLibrary(retry: Retry): List<Pair<SourceProgram, List<FolderEntry>>> {
        val root = retry { tree.list(ROOT) }
            ?: throw ServerException.Unreachable(IOException("shared folder root not found"))
        val listed = ArrayList<Pair<SourceProgram, List<FolderEntry>>>()
        for (publisher in root.directories()) {
            currentCoroutineContext().ensureActive()
            val publisherPath = publisher.name
            // 走査の途中で消えたフォルダは、無いものとして扱う
            val programDirs = retry { tree.list(publisherPath) }?.directories() ?: continue
            for (program in programDirs) {
                currentCoroutineContext().ensureActive()
                val programPath = "$publisherPath/${program.name}"
                val files = retry { tree.list(programPath) } ?: continue
                val sourceProgram = SourceProgram(sourceId = SourceItemId(programPath), name = program.name, publisherName = publisher.name)
                listed += sourceProgram to files.audioFiles()
            }
        }
        return listed
    }

    /**
     * 全走査の 1 回分の、木の操作のやり直し（#209 の決定 4）。続けて失敗した数を持つ。
     * [op] は blocking なので IO のスレッドで呼ぶ。
     */
    private inner class Retry {
        private var failures = 0

        suspend operator fun <T> invoke(op: () -> T): T {
            while (true) {
                val error = try {
                    val result = withContext(io) { op() }
                    failures = 0
                    return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ServerException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    throw ServerException.Unreachable(e)
                } catch (e: Exception) {
                    e
                }
                failures++
                if (failures >= MAX_CONSECUTIVE_FAILURES) throw ServerException.Unreachable(error)
                withContext(io) { tree.reset() }
                delay(retryDelay)
            }
        }
    }

    /** その番組のフォルダの中身だけで答える。フォルダが無ければ空。 */
    override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> {
        if (programSegments(programId) == null) return emptyList()
        val previous = scanned.load()
        return onTree { checkActive ->
            episodesIn(programId, tree.list(programId.value).orEmpty(), previous, checkActive)
        }
    }

    /** 番組のフォルダが無ければ null（消失）。取得元 ID が `<配信元>/<番組>` の形でない（無視する名前を含む）ときも null。 */
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

    private fun episodesIn(
        programId: SourceItemId,
        entries: List<FolderEntry>,
        previous: Map<SourceItemId, ScannedFile>,
        checkActive: () -> Unit,
    ): List<SourceEpisode> =
        entries.audioFiles().map {
            // タグの読み取り（ファイルごとに数秒）の合間に、取り消されていれば止まる
            checkActive()
            val values = cachedValues(programId, it, previous)
                ?: tagValues(tags.read(tree, episodeIdOf(programId, it).value, it.sizeBytes), it.name, it.modifiedAt.truncatedToMillis())
            episodeOf(programId, it, values)
        }

    private fun episodeIdOf(programId: SourceItemId, file: FolderEntry) = SourceItemId("${programId.value}/${file.name}")

    /**
     * 取得元 ID・サイズ・更新日時がどれも前の走査と同じなら、その回の前の値（タグを読み直さない。epic #195 の決定 8）。違えば null。
     * Room には ms で入るので、ms に揃えてから比べる（ms より細かい更新日時が来ても同じとみなす。SMB の実装（smbj）が実機で何の精度を返すかは未確認。docs/development.md の実機の確認項目）
     */
    private fun cachedValues(programId: SourceItemId, file: FolderEntry, previous: Map<SourceItemId, ScannedFile>): TagValues? {
        val modifiedAt = file.modifiedAt.truncatedToMillis()
        val cached = previous[episodeIdOf(programId, file)]?.takeIf { it.sizeBytes == file.sizeBytes && it.modifiedAt == modifiedAt }
        return cached?.let { TagValues(it.title, it.publishedAt, it.runtime, it.performers) }
    }

    private fun episodeOf(programId: SourceItemId, file: FolderEntry, values: TagValues): SourceEpisode {
        val modifiedAt = file.modifiedAt.truncatedToMillis()
        return SourceEpisode(
            sourceId = episodeIdOf(programId, file),
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

    /**
     * 木の操作の失敗を到達不能に正規化する。木の操作は blocking でコルーチンの取り消しに気づかないので、
     * [block] には取り消されていれば [CancellationException] を投げる関数を渡す。1 番組の取得（[fetchProgramEpisodes]）はファイルごとにこれを呼び、
     * 取り消されたら残りを読まない（#198）。全走査（[fetchAll]）はこれを通らず、[Retry] でやり直し、ファイルごと・フォルダごとに取り消しを見る（#209）。
     */
    private suspend fun <T> onTree(block: (checkActive: () -> Unit) -> T): T = withContext(io) {
        val context = coroutineContext
        try {
            block { context.ensureActive() }
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

        /** 全走査で、木の操作が続けてこの回数だけ失敗したら到達不能で終わる（#209 の決定 4）。 */
        const val MAX_CONSECUTIVE_FAILURES: Int = 3

        /** やり直す前に待つ時間（#209）。切れた直後にすぐ張り直して同じ失敗を重ねないため。長さに根拠は無い（測っていない）。 */
        val RETRY_DELAY: Duration = 2.seconds

        /** 各回とみなす拡張子（小文字で比べる）。 */
        val AUDIO_EXTENSIONS: Set<String> = setOf("m4a", "mp3", "aac", "ogg", "opus", "flac", "wav")

        private fun extensionOf(name: String): String =
            name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

        /** 無視する名前の頭文字。NAS や OS が作るフォルダ・ファイル（`@eaDir`・`#recycle`・`._<名前>.m4a`・`.DS_Store` など）。 */
        private val IGNORED_PREFIXES = charArrayOf('.', '@', '#')

        /** 名前が `.`・`@`・`#` で始まるフォルダとファイルは、どの段でも無視する（#197 の追加の決定）。 */
        fun isIgnoredName(name: String): Boolean = name.firstOrNull()?.let { it in IGNORED_PREFIXES } ?: true

        /** 各回とみなすファイル（3 段目の音声ファイル）を名前の順に。 */
        private fun List<FolderEntry>.audioFiles(): List<FolderEntry> =
            filter { !it.isDirectory && !isIgnoredName(it.name) && extensionOf(it.name) in AUDIO_EXTENSIONS }.sortedBy { it.name }

        private fun List<FolderEntry>.directories(): List<FolderEntry> =
            filter { it.isDirectory && !isIgnoredName(it.name) }.sortedBy { it.name }

        /** `<配信元>/<番組>` を 2 つに分ける。形が違う、または無視する名前を含むなら null（[fetchAll] に現れない番組）。 */
        private fun programSegments(id: SourceItemId): Pair<String, String>? {
            val parts = id.value.split('/')
            return if (parts.size == 2 && parts.none(::isIgnoredName)) parts[0] to parts[1] else null
        }

        private fun Instant.truncatedToMillis(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())
    }
}
