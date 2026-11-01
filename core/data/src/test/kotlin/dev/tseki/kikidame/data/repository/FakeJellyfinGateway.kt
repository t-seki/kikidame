package dev.tseki.kikidame.data.repository

import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.ServerCredentials
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

class FakeJellyfinGateway : JellyfinGateway {
    var snapshot: SourceSnapshot = SourceSnapshot(emptyList(), emptyList())
    var libraries: List<LibraryView> = emptyList()
    var failWith: SourceException? = null
    var acceptedPassword: String = "secret"
    val fetchedLibraries = ArrayList<SourceItemId>()

    /** 番組・各回の取得に渡されたライブラリ（呼ばれた順）。 */
    val usedLibraries = ArrayList<SelectedLibrary>()
    /** 番組・各回の取得とダウンロードに渡された認証の情報（呼ばれた順）。 */
    val usedCredentials = ArrayList<ServerCredentials>()

    override suspend fun signIn(serverUrl: String, userName: String, password: String): Session {
        failWith?.let { throw it }
        if (password != acceptedPassword) throw SourceException.Unauthorized()
        return Session(serverUrl, userName, userId = "user-$userName", accessToken = "token-$userName")
    }

    override suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView> {
        failWith?.let { throw it }
        return libraries
    }

    override suspend fun fetchLibrary(credentials: ServerCredentials, library: SelectedLibrary): SourceSnapshot {
        failWith?.let { throw it }
        usedCredentials += credentials
        fetchedLibraries += library.id
        usedLibraries += library
        return snapshot
    }

    val fetchedPrograms = ArrayList<SourceItemId>()

    /** [snapshot] の番組一覧に無ければ null（404 相当）。 */
    override suspend fun fetchProgram(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): SourceProgram? {
        failWith?.let { throw it }
        usedCredentials += credentials
        usedLibraries += library
        return snapshot.programs.firstOrNull { it.sourceId == programSourceId }
    }

    /** [snapshot] のうちその番組に属する各回。 */
    override suspend fun fetchProgramEpisodes(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): List<SourceEpisode> {
        failWith?.let { throw it }
        usedCredentials += credentials
        usedLibraries += library
        fetchedPrograms += programSourceId
        return snapshot.episodes.filter { it.programSourceId == programSourceId }
    }

    /** 番組 ID → 画像の中身。無ければ null（画像なし）。 */
    val programImages = HashMap<SourceItemId, ByteArray>()
    val fetchedImages = ArrayList<SourceItemId>()

    /** 画像の取得だけが投げる。 */
    var imageFailure: SourceException? = null

    override suspend fun fetchProgramImage(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): ByteArray? {
        failWith?.let { throw it }
        usedCredentials += credentials
        usedLibraries += library
        fetchedImages += programSourceId
        imageFailure?.let { throw it }
        return programImages[programSourceId]
    }

    /** 各回 ID → 本文。`honorRange` が false なら Range を無視して 200 で全体を返す。 */
    val files = HashMap<SourceItemId, ByteArray>()
    var honorRange = true
    val openedRanges = ArrayList<Long>()
    override suspend fun openDownload(credentials: ServerCredentials, episodeSourceId: SourceItemId, rangeStart: Long): DownloadStream {
        failWith?.let { throw it }
        usedCredentials += credentials
        val bytes = files[episodeSourceId] ?: throw SourceException.Failed("HTTP 404")
        openedRanges += rangeStart
        return if (honorRange && rangeStart > 0) {
            DownloadStream(resumedFrom = rangeStart, totalBytes = bytes.size.toLong(), body = bytes.inputStream().also { it.skip(rangeStart) })
        } else {
            DownloadStream(resumedFrom = null, totalBytes = bytes.size.toLong(), body = bytes.inputStream())
        }
    }

    /** 大きさを問い合わせた各回（呼ばれた順、ADR 0011）。大きさは [fileSizes] にあればその値、無ければ [files] の長さ、どちらも無ければ null。 */
    val fetchedFileSizes = ArrayList<SourceItemId>()
    val fileSizes = HashMap<SourceItemId, Long>()
    /** 大きさの問い合わせだけが投げる。 */
    var fileSizeFailure: SourceException? = null
    override suspend fun fetchFileSize(credentials: ServerCredentials, episodeSourceId: SourceItemId): Long? {
        failWith?.let { throw it }
        fetchedFileSizes += episodeSourceId
        fileSizeFailure?.let { throw it }
        return fileSizes[episodeSourceId] ?: files[episodeSourceId]?.size?.toLong()
    }

    /** 各回 ID → サーバの UserData（再生の共有）。無ければ [SharedPlayState.EMPTY]。 */
    val playStates = HashMap<SourceItemId, SharedPlayState>()
    /** UserData を読んだ各回（呼ばれた順）。 */
    val readPlayStates = ArrayList<SourceItemId>()
    /** UserData に書いた各回と値（呼ばれた順）。 */
    val writtenPlayStates = ArrayList<Pair<SourceItemId, SharedPlayState>>()
    /** UserData の読み書きだけが投げる。 */
    var playStateFailure: SourceException? = null
    /** UserData の読み込みだけが投げる（書き込みは通る）。 */
    var playStateReadFailure: SourceException? = null
    /** UserData を書く途中（受け取った後、覚える前）に呼ぶ。送っている間の手元の変化を作る。 */
    var duringWrite: (suspend () -> Unit)? = null

    override suspend fun fetchPlayState(credentials: ServerCredentials, episodeSourceId: SourceItemId, timeout: Duration?): SharedPlayState {
        failWith?.let { throw it }
        readPlayStates += episodeSourceId
        playStateFailure?.let { throw it }
        playStateReadFailure?.let { throw it }
        return playStates[episodeSourceId] ?: SharedPlayState.EMPTY
    }

    override suspend fun writePlayState(credentials: ServerCredentials, episodeSourceId: SourceItemId, state: SharedPlayState): SharedPlayState {
        failWith?.let { throw it }
        writtenPlayStates += episodeSourceId to state
        playStateFailure?.let { throw it }
        duringWrite?.invoke()
        playStates[episodeSourceId] = state
        return state
    }
}
