package dev.tseki.kikidame.domain

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/*
 * 手元のファイルの内訳（#335）。設定の「手元のファイル」の行から開く画面が使う。
 * 手元の回は `DONE` でパスがある回（[EpisodeWithState.isPlayable]、[LocalStorageUsage] と同じ定義）で、ダウンロード中の回は入らない。
 * 容量は各回の `sizeBytes` の合計（[LocalStorageUsage] と同じ。ストレージ上の実際のファイルサイズは測らない）。
 */

/**
 * 番組ごとの手元のファイル。内訳は重ならない 3 つで、[pinnedCount] + [playedUnpinnedCount] + [unplayedUnpinnedCount] = [episodeCount]。
 * 固定は再生済みかを問わず固定に数え、「ほか再生済み」「ほか未再生」は固定でない回だけを数える。
 */
data class ProgramStorage(
    val programId: ProgramId,
    val programName: String,
    val totalBytes: Long,
    val episodeCount: Int,
    val pinnedCount: Int,
    val playedUnpinnedCount: Int,
    val unplayedUnpinnedCount: Int,
)

/** 手元の回 1 つ。内訳の画面の「すべての回」と番組ごとの一覧の行。 */
data class LocalEpisode(
    val episodeId: EpisodeId,
    val programId: ProgramId,
    val programName: String,
    val title: String,
    val publishedAt: Instant,
    val sizeBytes: Long,
    val pinned: Boolean,
    val played: Boolean,
    /** 取得元 ID を持つか。持たない回は消すと各回ごと消え、戻せない（[deletionScopeFor]）。 */
    val hasSourceItemId: Boolean,
    /** 番組が同期対象か。同期対象の番組の回は、保持すべき回なら次の同期で落とし直される。 */
    val programSyncEnabled: Boolean,
)

/** [LocalStorageRepository.deleteLocalEpisodes] の結果。[kept] は聴いている回なので消さずに残した数。 */
data class DeletedLocalEpisodes(val deleted: Int, val kept: Int)

/** 手元のファイルの内訳を見て、選んだ回を消す（#335）。 */
interface LocalStorageRepository {
    /** 手元の回のある番組だけを、容量の大きい順に。変わるたびに流す。 */
    fun observePrograms(): Flow<List<ProgramStorage>>

    /** 全番組の手元の回を、容量の大きい順に。変わるたびに流す。 */
    fun observeEpisodes(): Flow<List<LocalEpisode>>

    /** [programId] の番組の手元の回を、公開日の新しい順（[EpisodeOrder.newestFirst] と同じ順）に。変わるたびに流す。 */
    fun observeEpisodes(programId: ProgramId): Flow<List<LocalEpisode>>

    /**
     * [episodeIds] の回のうち、今も手元にある回を 1 回ずつ [DownloadRepository.deleteLocal] と同じ規則（[deletionScopeFor]）で消す。
     * [except] の回（聴いている回）は消さずに残し、[DeletedLocalEpisodes.kept] に数える。
     */
    suspend fun deleteLocalEpisodes(episodeIds: List<EpisodeId>, except: EpisodeId?): DeletedLocalEpisodes
}
