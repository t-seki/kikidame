package dev.tseki.kikidame.data.repository

import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.ServerCredentials
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.Session

class FakeJellyfinGateway : JellyfinGateway {
    var snapshot: SourceSnapshot = SourceSnapshot(emptyList(), emptyList())
    var libraries: List<LibraryView> = emptyList()
    var failWith: ServerException? = null
    var acceptedPassword: String = "secret"
    val fetchedLibraries = ArrayList<SourceItemId>()
    /** 番組・各回の取得とダウンロードに渡された認証の情報（呼ばれた順）。 */
    val usedCredentials = ArrayList<ServerCredentials>()

    override suspend fun signIn(serverUrl: String, userName: String, password: String): Session {
        failWith?.let { throw it }
        if (password != acceptedPassword) throw ServerException.Unauthorized()
        return Session(serverUrl, userName, userId = "user-$userName", accessToken = "token-$userName")
    }

    override suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView> {
        failWith?.let { throw it }
        return libraries
    }

    override suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: SourceItemId): SourceSnapshot {
        failWith?.let { throw it }
        usedCredentials += credentials
        fetchedLibraries += libraryId
        return snapshot
    }

    val fetchedPrograms = ArrayList<SourceItemId>()

    /** [snapshot] の番組一覧に無ければ null（404 相当）。 */
    override suspend fun fetchProgram(credentials: ServerCredentials, programSourceId: SourceItemId): SourceProgram? {
        failWith?.let { throw it }
        usedCredentials += credentials
        return snapshot.programs.firstOrNull { it.sourceId == programSourceId }
    }

    /** [snapshot] のうちその番組に属する各回。 */
    override suspend fun fetchProgramEpisodes(credentials: ServerCredentials, programSourceId: SourceItemId): List<SourceEpisode> {
        failWith?.let { throw it }
        usedCredentials += credentials
        fetchedPrograms += programSourceId
        return snapshot.episodes.filter { it.programSourceId == programSourceId }
    }
    /** 各回 ID → 本文。`honorRange` が false なら Range を無視して 200 で全体を返す。 */
    val files = HashMap<SourceItemId, ByteArray>()
    var honorRange = true
    val openedRanges = ArrayList<Long>()
    override suspend fun openDownload(credentials: ServerCredentials, episodeSourceId: SourceItemId, rangeStart: Long): DownloadStream {
        failWith?.let { throw it }
        usedCredentials += credentials
        val bytes = files[episodeSourceId] ?: throw ServerException.Failed("HTTP 404")
        openedRanges += rangeStart
        return if (honorRange && rangeStart > 0) {
            DownloadStream(resumedFrom = rangeStart, totalBytes = bytes.size.toLong(), body = bytes.inputStream().also { it.skip(rangeStart) })
        } else {
            DownloadStream(resumedFrom = null, totalBytes = bytes.size.toLong(), body = bytes.inputStream())
        }
    }
}
