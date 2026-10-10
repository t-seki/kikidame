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

    /**
     * [fetchAll] と同じ全走査で、途中経過を [listener] に流す（#209）。返す一覧は [fetchAll] と同じく完全な一覧。
     * 既定は [fetchAll] をそのまま呼び、途中経過は流さない（Jellyfin。一覧が一度に返るので番組ごとに分けない）。
     * 共有フォルダ（`SharedFolderSource`）は、タグを読んだ番組をその番組を読み終えるたびに流す。
     */
    suspend fun fetchAll(listener: ScanListener): SourceSnapshot = fetchAll()

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

/**
 * 全走査の途中経過の受け手（#209）。走査はここで投げた例外を包まずにそのまま [SourceGateway.fetchAll] から投げる。
 */
interface ScanListener {
    /**
     * タグを読む回が [total] 本あり、そのうち [read] 本を読み終えた。走査の始めに `read = 0` で 1 回、
     * その後は 1 本読むごとに呼ぶ。タグを読む回が無い走査（[total] が 0）では呼ばない。
     */
    fun onTagProgress(read: Int, total: Int) {}

    /**
     * 1 番組の各回を読み終えた。[episodes] はその番組について完全（[dev.tseki.kikidame.domain.SnapshotScope.Program] として取り込める）。
     * タグを読んだ回がある番組だけを流す（タグを読まなかった番組は、前の同期から変わっていない回か、消えた回しか持たないので、
     * 全走査の終わりの取り込みに任せる）。
     */
    suspend fun onProgramScanned(program: SourceProgram, episodes: List<SourceEpisode>) {}

    companion object {
        /** 何もしない受け手。 */
        val None: ScanListener = object : ScanListener {}
    }
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
