package dev.tseki.jellyfinradio.data.repository

import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.EpisodeEntity
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.ProgramEntity
import dev.tseki.jellyfinradio.data.db.toDomain
import dev.tseki.jellyfinradio.data.jellyfin.JellyfinGateway
import dev.tseki.jellyfinradio.data.jellyfin.credentials
import dev.tseki.jellyfinradio.data.session.SessionStore
import dev.tseki.jellyfinradio.domain.LibraryMatching
import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.RefreshResult
import dev.tseki.jellyfinradio.domain.ServerEpisode
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.domain.Ticks
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * サーバのライブラリ全体を取得し、突合（[LibraryMatching]）して Room に 1 トランザクションで適用する。
 * サーバ由来の各回はサーバの値で上書きし、`LocalFile` / `PlaybackState` は触らない。
 * サーバの一覧に無い行は何もしない（削除・判断保留は M3）。
 */
@Singleton
class RoomLibraryRefreshRepository @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val store: SessionStore,
    private val gateway: JellyfinGateway,
    private val clock: Clock,
) : LibraryRefreshRepository {

    override suspend fun refresh(): RefreshResult {
        val ready = store.current() as? SessionState.Ready ?: throw ServerException.Unauthorized()
        val snapshot = gateway.fetchLibrary(ready.session.credentials(), ready.library.id)
        val result = apply(snapshot)
        store.saveLastFetchedAt(result.fetchedAt)
        return result
    }

    internal suspend fun apply(snapshot: ServerSnapshot): RefreshResult = db.withTransaction {
        val programDao = db.programDao()
        val episodeDao = db.episodeDao()
        val match = LibraryMatching.match(
            localPrograms = programDao.listKeys().map { it.toDomain() },
            localEpisodes = episodeDao.listKeys().map { it.toDomain() },
            server = snapshot,
        )

        for ((programId, serverId) in match.programLinks) {
            programDao.setServerItemId(programId.value, serverId.value)
        }
        for (sp in match.newPrograms) {
            programDao.insert(ProgramEntity(serverItemId = sp.serverId.value, name = sp.name, stationName = sp.stationName))
        }
        // サーバの番組名・放送局はサーバが正
        val programIdByServerId = HashMap<ServerItemId, Long>()
        for (sp in snapshot.programs) {
            val row = programDao.findByServerItemId(sp.serverId.value) ?: continue
            programIdByServerId[sp.serverId] = row.id
            if (row.name != sp.name || row.stationName != sp.stationName) {
                programDao.updateNames(row.id, sp.name, sp.stationName)
            }
        }

        for ((episodeId, serverId) in match.episodeLinks) {
            episodeDao.setServerItemId(episodeId.value, serverId.value)
        }
        for (se in snapshot.episodes) {
            val programId = programIdByServerId[se.programServerId] ?: continue
            val existing = episodeDao.findByServerItemId(se.serverId.value)
            if (existing == null) {
                episodeDao.insert(se.toEntity(programId))
            } else {
                val updated = se.toEntity(programId).copy(id = existing.id)
                if (updated != existing) episodeDao.update(updated)
            }
        }

        RefreshResult(
            programs = snapshot.programs.size,
            episodes = snapshot.episodes.size,
            linkedPrograms = match.programLinks.size,
            linkedEpisodes = match.episodeLinks.size,
            fetchedAt = clock.now(),
        )
    }

    private fun ServerEpisode.toEntity(programId: Long) = EpisodeEntity(
        serverItemId = serverId.value,
        programId = programId,
        title = title,
        airedAt = airedAt,
        addedAt = addedAt,
        runtimeTicks = Ticks.fromDuration(runtime),
        sizeBytes = sizeBytes,
        container = container,
    )
}
