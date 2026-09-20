package dev.tseki.kikidame.data.repository

import dev.tseki.kikidame.data.jellyfin.DownloadStream
import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.ServerCredentials
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.ServerEpisode
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.ServerProgram
import dev.tseki.kikidame.domain.ServerSnapshot
import dev.tseki.kikidame.domain.Session

class FakeJellyfinGateway : JellyfinGateway {
    var snapshot: ServerSnapshot = ServerSnapshot(emptyList(), emptyList())
    var libraries: List<LibraryView> = emptyList()
    var failWith: ServerException? = null
    var acceptedPassword: String = "secret"
    val fetchedLibraries = ArrayList<ServerItemId>()

    override suspend fun signIn(serverUrl: String, userName: String, password: String): Session {
        failWith?.let { throw it }
        if (password != acceptedPassword) throw ServerException.Unauthorized()
        return Session(serverUrl, userName, userId = "user-$userName", accessToken = "token-$userName")
    }

    override suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView> {
        failWith?.let { throw it }
        return libraries
    }

    override suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: ServerItemId): ServerSnapshot {
        failWith?.let { throw it }
        fetchedLibraries += libraryId
        return snapshot
    }

    val fetchedPrograms = ArrayList<ServerItemId>()

    /** [snapshot] の番組一覧に無ければ null（404 相当）。 */
    override suspend fun fetchProgram(credentials: ServerCredentials, programServerId: ServerItemId): ServerProgram? {
        failWith?.let { throw it }
        return snapshot.programs.firstOrNull { it.serverId == programServerId }
    }

    /** [snapshot] のうちその番組に属する各回。 */
    override suspend fun fetchProgramEpisodes(credentials: ServerCredentials, programServerId: ServerItemId): List<ServerEpisode> {
        failWith?.let { throw it }
        fetchedPrograms += programServerId
        return snapshot.episodes.filter { it.programServerId == programServerId }
    }
    /** 各回 ID → 本文。`honorRange` が false なら Range を無視して 200 で全体を返す。 */
    val files = HashMap<ServerItemId, ByteArray>()
    var honorRange = true
    val openedRanges = ArrayList<Long>()
    override suspend fun openDownload(credentials: ServerCredentials, episodeServerId: ServerItemId, rangeStart: Long): DownloadStream {
        failWith?.let { throw it }
        val bytes = files[episodeServerId] ?: throw ServerException.Failed("HTTP 404")
        openedRanges += rangeStart
        return if (honorRange && rangeStart > 0) {
            DownloadStream(resumedFrom = rangeStart, totalBytes = bytes.size.toLong(), body = bytes.inputStream().also { it.skip(rangeStart) })
        } else {
            DownloadStream(resumedFrom = null, totalBytes = bytes.size.toLong(), body = bytes.inputStream())
        }
    }
}
