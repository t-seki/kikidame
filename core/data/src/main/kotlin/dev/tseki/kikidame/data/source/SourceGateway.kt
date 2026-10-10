package dev.tseki.kikidame.data.source

import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import java.io.InputStream

/**
 * 取得元（CONTEXT.md）との境界（ADR 0010）。同期とダウンロードは、これだけを通して取得元に触る。
 * 取得元の種類（Jellyfin など）に依存しない。認証などの接続の情報は実装の側で持つ。
 * 失敗はすべて [ServerException] に正規化する。接続していなければ [ServerException.Unauthorized]。
 */
interface SourceGateway {
    /**
     * 取得元の全体を取得する（全走査）。番組に属さない各回は含めない。
     * 返す一覧は完全な一覧（[dev.tseki.kikidame.domain.SnapshotScope.Library]）。
     */
    suspend fun fetchAll(): SourceSnapshot

    /** 1 番組の各回だけを取得する。他の番組に属する各回は含めない。 */
    suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode>

    /**
     * 番組そのものを取得する。取得元に無ければ null（消失）。
     * 1 番組の各回の取得は番組が無くても空の一覧を返しうるので、1 番組の同期はこれで存在を確かめてから行う。
     */
    suspend fun fetchProgram(programId: SourceItemId): SourceProgram?

    /**
     * 各回の原本のストリームを開く。[rangeStart] > 0 なら続きを要求するが、取得元が応じずに
     * 全体を返すこともある（[DownloadStream.resumedFrom] で判別）。使い終わったら [DownloadStream.close]。
     */
    suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream
}

class DownloadStream(
    /** 続きから返ったならその開始位置。全体が返ったなら null。 */
    val resumedFrom: Long?,
    /** ファイル全体のサイズ。不明なら null。 */
    val totalBytes: Long?,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        body.close()
        onClose()
    }
}
