package dev.tseki.kikidame.domain

import kotlinx.coroutines.flow.Flow

/** 手元のファイルを消すときの範囲。 */
enum class LocalDeletionScope {
    /** ファイルと手元の記録だけ消す。各回と再生位置は残る（取得元から落とし直せる）。 */
    FILE_ONLY,

    /** 各回ごと消す（再生位置も）。取得元に対応が無く、二度と手に入らない回。 */
    EPISODE,
}

/** 削除の規則: 取得元 ID がある回はファイルだけ、無い回（取得元を経由していない、落とし直せない回）は各回ごと。 */
fun deletionScopeFor(episode: Episode): LocalDeletionScope =
    if (episode.sourceItemId != null) LocalDeletionScope.FILE_ONLY else LocalDeletionScope.EPISODE

/** 保存先のファイル名。フォルダ構成は rajikura の Jellyfin プリセットの出力と同じ `<配信元>/<番組>/<タイトル>.<container>`。 */
object EpisodeFileName {
    private val FORBIDDEN = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
    const val DEFAULT_CONTAINER = "m4a"
    const val UNKNOWN_PUBLISHER = "_"
    private val PREFERRED_EXTENSIONS = listOf("m4a", "mp3", "aac", "ogg", "opus", "flac", "wav", "mp4")

    /** OS で使えない文字を `_` に。空になったら `_`。 */
    fun sanitize(segment: String): String =
        segment.replace(FORBIDDEN, "_").trim().trimEnd('.').ifEmpty { "_" }

    /**
     * Jellyfin の `Container` は `mov,mp4,m4a,3gp,3g2,mj2` のようなカンマ区切りで来ることがある（ffmpeg の
     * フォーマット名一覧）。既知の音声拡張子があればそれを、無ければ先頭を、空なら `m4a` を使う。
     */
    fun extensionFor(container: String): String {
        val candidates = container.lowercase().split(',').map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
        return PREFERRED_EXTENSIONS.firstOrNull { it in candidates } ?: candidates.firstOrNull() ?: DEFAULT_CONTAINER
    }
    fun relativePath(publisherName: String?, programName: String, title: String, container: String): String {
        val ext = extensionFor(container)
        return listOf(
            sanitize(publisherName ?: UNKNOWN_PUBLISHER),
            sanitize(programName),
            "${sanitize(title)}.$ext",
        ).joinToString("/")
    }

    /** 同名衝突のときに ` (2)`, ` (3)` … を付ける。 */
    fun withSuffix(relativePath: String, n: Int): String {
        val dot = relativePath.lastIndexOf('.')
        val slash = relativePath.lastIndexOf('/')
        return if (dot > slash) "${relativePath.substring(0, dot)} ($n)${relativePath.substring(dot)}" else "$relativePath ($n)"
    }
}

/**
 * 「取得元に見つからない番組」（消失した番組と取得元 ID の無い番組。1 つずつ消す「この番組を手元から消す」と同じ範囲）の件数。
 * 手元の回は `DONE` でパスがある回（[LocalStorageUsage] と同じ定義）で、固定はそのうちの固定した回。
 */
data class UnmatchedPrograms(val programCount: Int, val localEpisodeCount: Int, val pinnedEpisodeCount: Int)

/** 確認のダイアログに出す件数 [counts] と、その時点の対象の番組 [programIds]（確定ではこの中から消す）。 */
data class UnmatchedPreview(val counts: UnmatchedPrograms, val programIds: List<ProgramId>)

/** [DownloadRepository.removeUnmatchedPrograms] の結果。[kept] は聴いている回の番組なので消さずに残した数。 */
data class RemovedUnmatchedPrograms(val removed: Int, val kept: Int)

/** ダウンロードの操作（利用者側）。 */
interface DownloadRepository {
    /** 手動ダウンロード = 固定。既に行がある回は固定にするだけ（同期分の予約を手動に格上げする）。失敗は [retry]。 */
    suspend fun enqueue(episodeId: EpisodeId)

    /**
     * 同期による予約（`pinned = false`）。渡した順にキューへ入れる。既に行がある回は飛ばし、実際に予約した数を返す。
     * 予約した回には、その時点の一覧の版（ADR 0014）を記録する（[enqueue] も同じ）。
     */
    suspend fun enqueueForSync(episodeIds: List<EpisodeId>): Int

    /**
     * **差し替え**（ADR 0014、#371）の予約。手元にある（`DONE`）回だけを、同じ待ち行列に入れる。行は `DONE` のまま（今のファイルで聴ける）。
     * 新しいファイルの版として、その時点の一覧の版を覚える。残っている書きかけのファイル（`.part`）は捨てる。
     * 手元に無い・もう差し替えを予約している回は飛ばし、実際に予約した数を返す。
     */
    suspend fun enqueueReplacements(episodeIds: List<EpisodeId>): Int

    /**
     * 取得元の一覧から消えた各回を `Episode` 行ごと消す（`LocalFile` / `PlaybackState` は cascade、ファイルも消す）。
     * [deletionScopeFor] は「落とし直せる」前提なので、ここには当てはまらない。
     */
    suspend fun removeEpisode(episodeId: EpisodeId)

    /**
     * 番組を手元から消す（番組・各回・ファイル・再生位置のすべて）。消失した番組と取得元 ID の無い番組の「この番組を手元から消す」用。
     * 取得元に在る番組を消しても次の同期で戻ってくる（再生位置だけ失う）ので、呼び出し側で出し分ける。
     * 設定からまとめて消す操作（[removeUnmatchedPrograms]）からも使う。
     */
    suspend fun removeProgram(programId: ProgramId)

    /** 取得元に見つからない番組の件数と id。[except] の回の番組は入れない（聴いている回。null なら除かない）。 */
    suspend fun unmatchedPrograms(except: EpisodeId?): UnmatchedPreview

    /** [unmatchedPrograms] を全部（除かずに）数えて、変わるたびに流す。設定の行の出し分けと件数に使う。 */
    fun observeUnmatchedPrograms(): Flow<UnmatchedPrograms>

    /**
     * [programIds]（確認を出した時点の対象）のうち、今も取得元に見つからない番組をまとめて [removeProgram] で消す
     * （確認の後に新しく見つからなくなった番組は消さない）。[except] の回（聴いている回）の番組だけは消さずに残し、
     * [RemovedUnmatchedPrograms.kept] に数える。
     */
    suspend fun removeUnmatchedPrograms(programIds: List<ProgramId>, except: EpisodeId?): RemovedUnmatchedPrograms

    /** DONE 以外（PENDING / RUNNING / FAILED）の行を取り消す。書きかけのファイルも消す。 */
    suspend fun cancel(episodeId: EpisodeId)

    /** FAILED を PENDING に戻す（手動なので試行回数は見ない）。 */
    suspend fun retry(episodeId: EpisodeId)

    /** 手元にある（`DONE`）固定していない回を固定にする。ファイルは落とし直さない。手元に無い・取り込み中の回には何もしない。 */
    suspend fun pin(episodeId: EpisodeId)

    suspend fun unpin(episodeId: EpisodeId)

    /** [deletionScopeFor] の規則で消す。 */
    suspend fun deleteLocal(episodeId: EpisodeId): LocalDeletionScope

    /** `DONE` なのにファイルが無い行を整合する。直した件数を返す。 */
    suspend fun reconcileMissingFiles(): Int

    /** 再生直前の確認。ファイルが無ければ整合して false。 */
    suspend fun ensureFilePresent(episodeId: EpisodeId): Boolean
}

/** ダウンロードの実行側（Worker）が使う。 */
interface DownloadQueue {
    /**
     * 次に落とす行（キューに入れた順 = FIFO）。無ければ null。差し替えの予約（ADR 0014）も同じ待ち行列から返る
     * （[LocalFile.replacing] が true。行は DONE のまま）。
     */
    suspend fun nextPending(): EpisodeWithState?

    /** 次の実行に備えて、試行回数が上限未満の FAILED を PENDING に戻す。 */
    suspend fun requeueFailed(maxAttempts: Int)

    /** Worker が途中で殺されて RUNNING のまま残った行を PENDING に戻す（実行開始時に呼ぶ）。 */
    suspend fun resetRunning()

    /** 認証切れなどで中断した行を、試行回数を増やさずに PENDING へ戻す。 */
    suspend fun resetToPending(episodeId: EpisodeId)

    suspend fun markRunning(episodeId: EpisodeId)

    suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long)

    suspend fun markFailed(episodeId: EpisodeId)

    /** キャンセル済みなら false（Worker は約 1 MB ごとに見る）。 */
    suspend fun isStillWanted(episodeId: EpisodeId): Boolean

    // --- 差し替え（ADR 0014、#371）。行は DONE のままなので、上の口（状態を RUNNING / DONE / FAILED にする）は使わない ---

    /** 差し替えを落とし始める。行の状態（DONE）は変えない。 */
    suspend fun markReplaceRunning(episodeId: EpisodeId)

    /**
     * 差し替えを落とし終え、新しいファイルを今のファイルの場所に移した。落としたときの版を予約のときに覚えた版に、
     * 大きさを [sizeBytes] に、落とした日時を今にし、差し替えの印を下ろす。
     */
    suspend fun markReplaced(episodeId: EpisodeId, sizeBytes: Long)

    /**
     * 差し替えをやめる（失敗した・落とし終えたときに聴いている回だった・認証切れ）。差し替えの印を下ろすだけで、今のファイルと
     * 落としたときの版はそのまま残る。試行回数は増やさない。次の同期でまた見分けて予約する。
     */
    suspend fun abandonReplace(episodeId: EpisodeId)

    /** 差し替えがまだ要るか。手元の回ごと消された・差し替えが取り消されたなら false（Worker は約 1 MB ごとに見る）。 */
    suspend fun isReplaceStillWanted(episodeId: EpisodeId): Boolean

    /** Worker が途中で殺されて落としている最中のまま残った差し替えを、予約に戻す（実行開始時に呼ぶ）。 */
    suspend fun resetRunningReplacements()
}
