package dev.tseki.jellyfinradio.data.jellyfin

import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.ServerEpisode
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.Session
import java.io.InputStream

/** 認証済みの呼び出しに必要な最小限。 */
data class ServerCredentials(val serverUrl: String, val accessToken: String, val userId: String)

fun Session.credentials(): ServerCredentials = ServerCredentials(serverUrl, accessToken, userId)

/**
 * Jellyfin サーバとの境界。SDK への依存はこの実装にだけ閉じ込め、Repository のテストはフェイクで行う。
 * 失敗はすべて [ServerException] に正規化する。
 */
interface JellyfinGateway {
    suspend fun signIn(serverUrl: String, userName: String, password: String): Session

    suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView>

    /** ライブラリ全体を取得する。番組に属さない各回は含めない。 */
    suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: ServerItemId): ServerSnapshot

    /** 1 番組（MusicAlbum）の各回だけを取得する（#12）。他の番組に属する各回は含めない。 */
    suspend fun fetchProgramEpisodes(credentials: ServerCredentials, programServerId: ServerItemId): List<ServerEpisode>
    /**
     * 原本のストリームを開く。[rangeStart] > 0 なら `Range` で続きを要求するが、サーバが無視して
     * 全体を返すこともある（[DownloadStream.resumedFrom] で判別）。使い終わったら [DownloadStream.close]。
     */
    suspend fun openDownload(credentials: ServerCredentials, episodeServerId: ServerItemId, rangeStart: Long): DownloadStream
}
class DownloadStream(
    /** `Range` が効いた（206）ならその開始位置。全体が返った（200）なら null。 */
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
