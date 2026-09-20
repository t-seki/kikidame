package dev.tseki.kikidame.domain


/** 手元のファイルを消すときの範囲。 */
enum class LocalDeletionScope {
    /** ファイルと手元の記録だけ消す。各回と再生位置は残る（サーバから落とし直せる）。 */
    FILE_ONLY,

    /** 各回ごと消す（再生位置も）。サーバに対応が無く、二度と手に入らない回。 */
    EPISODE,
}

/** 削除の規則: サーバ ID がある回はファイルだけ、無い回（サーバを経由していない、落とし直せない回）は各回ごと。 */
fun deletionScopeFor(episode: Episode): LocalDeletionScope =
    if (episode.serverItemId != null) LocalDeletionScope.FILE_ONLY else LocalDeletionScope.EPISODE

/** 保存先のファイル名。フォルダ構成は radirec-tool の出力と同じ `<放送局>/<番組>/<タイトル>.<container>`。 */
object EpisodeFileName {
    private val FORBIDDEN = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
    const val DEFAULT_CONTAINER = "m4a"
    const val UNKNOWN_STATION = "_"
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
    fun relativePath(stationName: String?, programName: String, title: String, container: String): String {
        val ext = extensionFor(container)
        return listOf(
            sanitize(stationName ?: UNKNOWN_STATION),
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

/** ダウンロードの操作（利用者側）。 */
interface DownloadRepository {
    /** 手動ダウンロード = 固定。既に行がある回は固定にするだけ（同期分の予約を手動に格上げする）。失敗は [retry]。 */
    suspend fun enqueue(episodeId: EpisodeId)

    /** 同期による予約（`pinned = false`）。渡した順にキューへ入れる。既に行がある回は飛ばし、実際に予約した数を返す。 */
    suspend fun enqueueForSync(episodeIds: List<EpisodeId>): Int

    /**
     * サーバの一覧から消えた各回を `Episode` 行ごと消す（`LocalFile` / `PlaybackState` は cascade、ファイルも消す）。
     * [deletionScopeFor] は「落とし直せる」前提なので、ここには当てはまらない。
     */
    suspend fun removeEpisode(episodeId: EpisodeId)

    /**
     * 番組を手元から消す（番組・各回・ファイル・再生位置のすべて）。消失した番組とサーバ ID の無い番組の「この番組を手元から消す」用。
     * サーバに在る番組を消しても次の同期で戻ってくる（再生位置だけ失う）ので、呼び出し側で出し分ける。
     */
    suspend fun removeProgram(programId: ProgramId)

    /** DONE 以外（PENDING / RUNNING / FAILED）の行を取り消す。書きかけのファイルも消す。 */
    suspend fun cancel(episodeId: EpisodeId)

    /** FAILED を PENDING に戻す（手動なので試行回数は見ない）。 */
    suspend fun retry(episodeId: EpisodeId)

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
    /** 次に落とす行（キューに入れた順 = FIFO）。無ければ null。 */
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
}
