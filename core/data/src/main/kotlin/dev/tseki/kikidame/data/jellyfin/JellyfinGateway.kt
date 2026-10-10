package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.Session

/** 認証済みの呼び出しに必要な最小限。 */
data class ServerCredentials(val serverUrl: String, val accessToken: String, val userId: String)

fun Session.credentials(): ServerCredentials = ServerCredentials(serverUrl, accessToken, userId)

/**
 * Jellyfin サーバとの境界。SDK への依存はこの実装にだけ閉じ込め、Repository のテストはフェイクで行う。
 * 失敗はすべて [ServerException] に正規化する。同期とダウンロードはこれを直接使わず、
 * 取得元の境界（[dev.tseki.kikidame.data.source.SourceGateway]）の Jellyfin の実装（[JellyfinSource]）を通す（ADR 0010）。
 */
interface JellyfinGateway {
    suspend fun signIn(serverUrl: String, userName: String, password: String): Session

    suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView>

    /** ライブラリ全体を取得する。番組に属さない各回は含めない。 */
    suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: SourceItemId): SourceSnapshot

    /** 1 番組（MusicAlbum）の各回だけを取得する（#12）。他の番組に属する各回は含めない。 */
    suspend fun fetchProgramEpisodes(credentials: ServerCredentials, programSourceId: SourceItemId): List<SourceEpisode>

    /**
     * 番組そのものを取得する。サーバに無ければ null（消失）。
     * `ParentId` での各回取得は番組が無くても空の一覧を返すので、1 番組の同期はこれで存在を確かめてから行う。
     */
    suspend fun fetchProgram(credentials: ServerCredentials, programSourceId: SourceItemId): SourceProgram?
    /**
     * 原本のストリームを開く。[rangeStart] > 0 なら `Range` で続きを要求するが、サーバが無視して
     * 全体を返すこともある（[DownloadStream.resumedFrom] で判別）。使い終わったら [DownloadStream.close]。
     */
    suspend fun openDownload(credentials: ServerCredentials, episodeSourceId: SourceItemId, rangeStart: Long): DownloadStream
}
