package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SharedPlayState
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.Session
import kotlin.time.Duration

/** 認証済みの呼び出しに必要な最小限。 */
data class ServerCredentials(val serverUrl: String, val accessToken: String, val userId: String)

fun Session.credentials(): ServerCredentials = ServerCredentials(serverUrl, accessToken, userId)

/**
 * Jellyfin サーバとの境界。SDK への依存はこの実装にだけ閉じ込め、Repository のテストはフェイクで行う。
 * 失敗はすべて [SourceException] に正規化する。同期とダウンロードはこれを直接使わず、
 * 取得元の境界（[dev.tseki.kikidame.data.source.SourceGateway]）の Jellyfin の実装（[JellyfinSource]）を通す（ADR 0010）。
 *
 * 番組の組み方はライブラリの種類（[SelectedLibrary.kind]）で変わる（ADR 0013 の決定 2）。
 * 音楽のライブラリは番組が MusicAlbum、各回が Audio で、番組の取得元 ID は MusicAlbum の id。
 * ブックのライブラリは各回が AudioBook で、番組は各回の Album と AlbumArtist のタグでまとめ、番組の取得元 ID は `<AlbumArtist>/<Album>`。
 * そのため番組についての呼び出しは、ライブラリ（id と種類）を受け取る。
 * ブックのライブラリの一覧は、各回の UserData を [SourceEpisode.shared] に載せる（再生の共有、ADR 0013）。各回の UserData の 1 件ずつの読み書きは
 * [fetchPlayState] と [writePlayState]（`PlaybackSharing` が使う。同期とダウンロードの境界 [dev.tseki.kikidame.data.source.SourceGateway] は通らない）。
 */
interface JellyfinGateway {
    suspend fun signIn(serverUrl: String, userName: String, password: String): Session

    suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView>

    /** ライブラリ全体を取得する。番組に属さない各回は含めない。 */
    suspend fun fetchLibrary(credentials: ServerCredentials, library: SelectedLibrary): SourceSnapshot

    /** 1 番組の各回だけを取得する（#12）。他の番組に属する各回は含めない。 */
    suspend fun fetchProgramEpisodes(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): List<SourceEpisode>

    /**
     * 番組そのものを取得する。サーバに無ければ null（消失）。
     * 音楽のライブラリでは `ParentId` での各回取得は番組が無くても空の一覧を返すので、1 番組の同期はこれで存在を確かめてから行う。
     */
    suspend fun fetchProgram(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): SourceProgram?

    /**
     * 番組の画像の中身を取る（#282、ADR 0012）。音楽のライブラリは MusicAlbum の Primary、ブックのライブラリは番組のフォルダ（Folder）の
     * Primary（#354）。画像が無い・大きすぎるときは null。版は [SourceProgram.image]（Primary の ImageTag）で、取り直すかは呼び出し側が決める。
     */
    suspend fun fetchProgramImage(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): ByteArray?

    /**
     * 原本のストリームを開く。[rangeStart] > 0 なら `Range` で続きを要求するが、サーバが無視して
     * 全体を返すこともある（[DownloadStream.resumedFrom] で判別）。使い終わったら [DownloadStream.close]。
     * 各回の取得元 ID はどちらの種類のライブラリでもアイテムの id なので、ライブラリは要らない。
     */
    suspend fun openDownload(credentials: ServerCredentials, episodeSourceId: SourceItemId, rangeStart: Long): DownloadStream

    /**
     * 各回の UserData（再生位置・再生済み・`LastPlayedDate`）を読む（**再生の共有**、ADR 0013 の決定 5）。ブックのライブラリの各回にだけ使う。
     * [timeout] があれば、それまでに返らなければ [SourceException.Unreachable]（▶ の直前の読み込み。失敗したら手元の値で鳴らす）。
     */
    suspend fun fetchPlayState(credentials: ServerCredentials, episodeSourceId: SourceItemId, timeout: Duration? = null): SharedPlayState

    /**
     * 各回の UserData に [state] を `POST /UserItems/{itemId}/UserData` で書き（ADR 0013 の決定 3。再生の報告は使わない）、
     * サーバが返した書いた後の値を返す。呼び出し側は、返した値を「最後に見たサーバの値」にする（送った値ではなく）。
     */
    suspend fun writePlayState(credentials: ServerCredentials, episodeSourceId: SourceItemId, state: SharedPlayState): SharedPlayState
}
