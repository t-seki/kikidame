package dev.tseki.jellyfinradio.data.repository

import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.EpisodeEntity
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.ProgramEntity
import dev.tseki.jellyfinradio.data.db.toDomain
import dev.tseki.jellyfinradio.data.jellyfin.JellyfinGateway
import dev.tseki.jellyfinradio.data.jellyfin.credentials
import dev.tseki.jellyfinradio.data.session.SessionStore
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.LibraryMatching
import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.RefreshResult
import dev.tseki.jellyfinradio.domain.RetentionRule
import dev.tseki.jellyfinradio.domain.ServerEpisode
import dev.tseki.jellyfinradio.domain.ServerEpisodes
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerProgram
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.SnapshotScope
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.domain.SyncPlan
import dev.tseki.jellyfinradio.domain.SyncPlanner
import dev.tseki.jellyfinradio.domain.SyncProgramInput
import dev.tseki.jellyfinradio.domain.Ticks
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * サーバの一覧を取得し、突合（[LibraryMatching]）して Room に 1 トランザクションで適用する。
 * サーバ由来の各回はサーバの値で上書きし（サーバが返さない値は手元の値を残す）、`LocalFile` / `PlaybackState` は触らない。
 *
 * 全走査（[refresh]）は同期そのもの: 取り込んだ後に [SyncPlanner] を回し、保持ルールによる削除・サーバから消えた各回の除去・
 * ダウンロードの予約まで行う（ADR 0004）。番組単位（[refreshProgram]）は取り込みだけで、削除はしない。
 */
@Singleton
class RoomLibraryRefreshRepository @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val store: SessionStore,
    private val gateway: JellyfinGateway,
    private val downloads: DownloadRepository,
    private val clock: Clock,
) : LibraryRefreshRepository {

    override suspend fun refresh(excluded: Set<EpisodeId>): RefreshResult {
        val ready = requireReady()
        val snapshot = gateway.fetchLibrary(ready.session.credentials(), ready.library.id)
        val result = apply(snapshot)
        val outcome = synchronize(snapshot, excluded)
        store.saveLastFetchedAt(result.fetchedAt)
        return result.copy(
            enqueued = outcome.enqueued,
            deleted = outcome.deleted,
            removed = outcome.removed,
            onHold = outcome.onHold,
        )
    }

    override suspend fun refreshProgram(programId: ProgramId): RefreshResult? {
        val ready = requireReady()
        val program = db.programDao().findById(programId.value) ?: return null
        val serverId = program.serverItemId?.let(::ServerItemId) ?: return null
        val episodes = gateway.fetchProgramEpisodes(ready.session.credentials(), serverId)
        // 番組の名前・放送局は手元の値のまま（番組一覧は取らない）。突合（結び直しを含む）はこの番組の各回だけを相手にする
        val snapshot = ServerSnapshot(
            programs = listOf(ServerProgram(serverId, program.name, program.stationName)),
            episodes = episodes,
            scope = SnapshotScope.Program(serverId),
        )
        return apply(snapshot)
    }

    override suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId>): RefreshResult? {
        val ready = requireReady()
        val local = db.programDao().findById(programId.value) ?: return null
        val serverId = local.serverItemId?.let(::ServerItemId) ?: return null
        val credentials = ready.session.credentials()
        val program = gateway.fetchProgram(credentials, serverId)
        if (program == null) {
            // 消失。何も落とさず何も消さない
            return RefreshResult(programs = 0, episodes = 0, linkedPrograms = 0, linkedEpisodes = 0, fetchedAt = clock.now(), onHold = 1)
        }
        val snapshot = ServerSnapshot(
            programs = listOf(program),
            episodes = gateway.fetchProgramEpisodes(credentials, serverId),
            scope = SnapshotScope.Program(serverId),
        )
        val result = apply(snapshot)
        val outcome = synchronize(snapshot, excluded, onlyProgramId = programId)
        return result.copy(enqueued = outcome.enqueued, deleted = outcome.deleted, removed = outcome.removed, onHold = outcome.onHold)
    }

    private suspend fun requireReady(): SessionState.Ready =
        store.current() as? SessionState.Ready ?: throw ServerException.Unauthorized()

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
                episodeDao.insert(se.toEntity(programId, existingSize = null))
            } else {
                val updated = se.toEntity(programId, existingSize = existing.sizeBytes).copy(id = existing.id)
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

    internal data class SyncOutcome(val enqueued: Int, val deleted: Int, val removed: Int, val onHold: Int)

    /**
     * 取り込み後の Room の状態と全走査の結果から [SyncPlanner] を回し、結論を実行する。
     * 実行順は 除去 → 削除 → 予約（容量を先に空ける）。[excluded]（再生中の回）は今回は消さない。
     * サーバ ID を持たない番組は `Known` でも `Gone` でもない（サーバに在ると主張していない）ので入力に入れない。
     */
    internal suspend fun synchronize(snapshot: ServerSnapshot, excluded: Set<EpisodeId>, onlyProgramId: ProgramId? = null): SyncOutcome {
        val plan = plan(snapshot, onlyProgramId)
        var removed = 0
        for (id in plan.remove) {
            if (id in excluded) continue
            downloads.removeEpisode(id)
            removed++
        }
        var deleted = 0
        for (id in plan.delete) {
            if (id in excluded) continue
            downloads.deleteLocal(id)
            deleted++
        }
        val enqueued = downloads.enqueueForSync(plan.download)
        return SyncOutcome(enqueued = enqueued, deleted = deleted, removed = removed, onHold = plan.onHoldCount)
    }

    /** [onlyProgramId] を渡すとその番組だけを入力にする（1 番組の同期。他の番組は一覧に無くても消失扱いにしない）。 */
    internal suspend fun plan(snapshot: ServerSnapshot, onlyProgramId: ProgramId? = null): SyncPlan {
        val serverProgramIds = snapshot.programs.map { it.serverId }.toSet()
        val serverEpisodesByProgram = snapshot.episodes.groupBy({ it.programServerId }, { it.serverId })
        val localByProgram = db.episodeDao().listSyncRows().groupBy { it.programId }
        val programs = if (onlyProgramId == null) db.programDao().listAll() else listOfNotNull(db.programDao().findById(onlyProgramId.value))
        val inputs = programs.mapNotNull { p ->
            val serverId = p.serverItemId?.let(::ServerItemId) ?: return@mapNotNull null
            SyncProgramInput(
                programId = ProgramId(p.id),
                syncEnabled = p.syncEnabled,
                retentionRule = RetentionRule(p.keepLatest, p.deleteAfterPlayed),
                server = if (serverId in serverProgramIds) {
                    ServerEpisodes.Known(serverEpisodesByProgram[serverId].orEmpty().toSet())
                } else {
                    ServerEpisodes.Gone
                },
                local = localByProgram[p.id].orEmpty().map { it.toDomain() },
            )
        }
        return SyncPlanner.plan(inputs)
    }

    /** サーバが返さない値（サイズ）は手元の値を残す。 */
    private fun ServerEpisode.toEntity(programId: Long, existingSize: Long?) = EpisodeEntity(
        serverItemId = serverId.value,
        programId = programId,
        title = title,
        airedAt = airedAt,
        addedAt = addedAt,
        runtimeTicks = Ticks.fromDuration(runtime),
        sizeBytes = sizeBytes ?: existingSize ?: 0L,
        container = container,
    )
}
