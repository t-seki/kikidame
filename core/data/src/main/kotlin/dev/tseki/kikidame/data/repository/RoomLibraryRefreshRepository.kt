package dev.tseki.kikidame.data.repository

import androidx.room.withTransaction
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.ProgramEntity
import dev.tseki.kikidame.data.db.toDomain
import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.credentials
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryMatching
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.ServerEpisode
import dev.tseki.kikidame.domain.ServerEpisodes
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.ServerSnapshot
import dev.tseki.kikidame.domain.SnapshotScope
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SyncPlan
import dev.tseki.kikidame.domain.SyncPlanner
import dev.tseki.kikidame.domain.SyncProgramInput
import dev.tseki.kikidame.domain.Ticks
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * サーバの一覧を取得し、突合（[LibraryMatching]）して Room に 1 トランザクションで適用する。
 * サーバ由来の各回はサーバの値で上書きし（サーバが返さないサイズだけ手元の値を残す。出演者は空でも上書き）、`LocalFile` / `PlaybackState` は触らない。
 *
 * 全走査（[refresh]）は同期そのもの: 取り込んだ後に [SyncPlanner] を回し、保持ルールによる削除・サーバから消えた各回の除去・
 * ダウンロードの予約まで行う（ADR 0004）。番組単位（[refreshProgram]）は取り込みだけで、削除はしない。
 */
@Singleton
class RoomLibraryRefreshRepository @Inject constructor(
    private val db: KikidameDatabase,
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
        val local = db.programDao().findById(programId.value) ?: return null
        val serverId = local.serverItemId?.let(::ServerItemId) ?: return null
        val credentials = ready.session.credentials()
        // `ParentId` での各回取得は番組が無くても空を返すので、先に番組そのものを確かめて消失を立てる／戻す
        val program = gateway.fetchProgram(credentials, serverId)
        if (program == null) {
            if (local.goneSince == null) db.programDao().setGoneSince(local.id, clock.now())
            return RefreshResult(programs = 0, episodes = 0, linkedPrograms = 0, linkedEpisodes = 0, fetchedAt = clock.now(), onHold = 1)
        }
        if (local.goneSince != null) db.programDao().setGoneSince(local.id, null)
        // 突合（結び直しを含む）はこの番組の各回だけを相手にする。削除も予約もしない
        val snapshot = ServerSnapshot(
            programs = listOf(program),
            episodes = gateway.fetchProgramEpisodes(credentials, serverId),
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
            // 消失。何も落とさず何も消さない。番組一覧が無いので結び直しは全体同期に任せる
            if (local.goneSince == null) db.programDao().setGoneSince(local.id, clock.now())
            return RefreshResult(programs = 0, episodes = 0, linkedPrograms = 0, linkedEpisodes = 0, fetchedAt = clock.now(), onHold = 1)
        }
        if (local.goneSince != null) db.programDao().setGoneSince(local.id, null)
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
        val inputs = inputs(snapshot, onlyProgramId)
        val plan = SyncPlanner.plan(inputs)
        recordGone(inputs)
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

    /**
     * 消失した番組に日時を立て、見つかった（結び直しを含む）番組は消す。到達不能は判断保留だが消失ではないので触らない。
     * 1 番組の同期では対象がその番組だけ。
     */
    private suspend fun recordGone(inputs: List<SyncProgramInput>) {
        val now = clock.now()
        for (input in inputs) {
            val row = db.programDao().findById(input.programId.value) ?: continue
            when (input.server) {
                ServerEpisodes.Gone -> if (row.goneSince == null) db.programDao().setGoneSince(row.id, now)
                is ServerEpisodes.Known -> if (row.goneSince != null) db.programDao().setGoneSince(row.id, null)
                ServerEpisodes.Unavailable -> Unit
            }
        }
    }

    internal suspend fun plan(snapshot: ServerSnapshot, onlyProgramId: ProgramId? = null): SyncPlan =
        SyncPlanner.plan(inputs(snapshot, onlyProgramId))

    /** [onlyProgramId] を渡すとその番組だけを入力にする（1 番組の同期。他の番組は一覧に無くても消失扱いにしない）。 */
    private suspend fun inputs(snapshot: ServerSnapshot, onlyProgramId: ProgramId? = null): List<SyncProgramInput> {
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
        return inputs
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
        performers = performers,
    )
}
