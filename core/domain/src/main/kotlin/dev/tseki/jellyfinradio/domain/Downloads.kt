package dev.tseki.jellyfinradio.domain

import kotlinx.coroutines.flow.Flow

/** 手元のファイルを消すときの範囲。 */
enum class LocalDeletionScope {
    /** ファイルと手元の記録だけ消す。各回と再生位置は残る（サーバから落とし直せる）。 */
    FILE_ONLY,

    /** 各回ごと消す（再生位置も）。サーバに対応が無く、二度と手に入らない回。 */
    EPISODE,
}

/** 削除の規則: サーバ ID がある回はファイルだけ、無い回（シード由来）は各回ごと。 */
fun deletionScopeFor(episode: Episode): LocalDeletionScope =
    if (episode.serverItemId != null) LocalDeletionScope.FILE_ONLY else LocalDeletionScope.EPISODE

/** 保存先のファイル名。フォルダ構成はシードと同じ `<放送局>/<番組>/<タイトル>.<container>`。 */
object EpisodeFileName {
    private val FORBIDDEN = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
    const val DEFAULT_CONTAINER = "m4a"
    const val UNKNOWN_STATION = "_"

    /** OS で使えない文字を `_` に。空になったら `_`。 */
    fun sanitize(segment: String): String =
        segment.replace(FORBIDDEN, "_").trim().trimEnd('.').ifEmpty { "_" }

    fun relativePath(stationName: String?, programName: String, title: String, container: String): String {
        val ext = container.trim().lowercase().ifEmpty { DEFAULT_CONTAINER }
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
    /** 手動ダウンロード = 固定。既に手元にある／キューにある回は何もしない。 */
    suspend fun enqueue(episodeId: EpisodeId)

    /** PENDING / RUNNING を取り消す。書きかけのファイルも消す。 */
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
    /** 次に落とす行（放送日の新しい順）。無ければ null。 */
    suspend fun nextPending(): EpisodeWithState?

    /** 次の実行に備えて、試行回数が上限未満の FAILED を PENDING に戻す。 */
    suspend fun requeueFailed(maxAttempts: Int)

    suspend fun markRunning(episodeId: EpisodeId)

    suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long)

    suspend fun markFailed(episodeId: EpisodeId)

    /** キャンセル済みなら false（Worker はチャンクごとに見る）。 */
    suspend fun isStillWanted(episodeId: EpisodeId): Boolean
}

interface AppSettingsRepository {
    val wifiOnly: Flow<Boolean>
    suspend fun setWifiOnly(value: Boolean)
}
