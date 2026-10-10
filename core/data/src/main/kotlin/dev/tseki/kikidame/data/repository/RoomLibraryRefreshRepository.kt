package dev.tseki.kikidame.data.repository

import androidx.room.withTransaction
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.ProgramEntity
import dev.tseki.kikidame.data.db.toDomain
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryMatching
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceEpisodes
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceSnapshot
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
 * 取得元の一覧を [SourceGateway] から取得し、突合（[LibraryMatching]）して Room に 1 トランザクションで適用する。
 * 取得元由来の各回は取得元の値で上書きし（取得元が返さないサイズだけ手元の値を残す。出演者は空でも上書き。
 * 共有フォルダのファイルのサイズ・更新日時（`sourceFileSize` / `sourceModifiedAt`、#197）も取得元の値で書く）、`LocalFile` / `PlaybackState` は触らない。
 *
 * 全走査（[refresh]）は同期そのもの: 取り込んだ後に [SyncPlanner] を回し、保持ルールによる削除・取得元から消えた各回の除去・
 * ダウンロードの予約まで行う（ADR 0004）。番組単位（[refreshProgram]）は取り込みだけで、削除はしない。
 */
@Singleton
class RoomLibraryRefreshRepository @Inject constructor(
    private val db: KikidameDatabase,
    private val store: SessionStore,
    private val source: SourceGateway,
    private val downloads: DownloadRepository,
    private val clock: Clock,
) : LibraryRefreshRepository {

    override suspend fun refresh(excluded: Set<EpisodeId>): RefreshResult {
        requireReady()
        // 失敗しても記録する。到達できない間、起動時同期が前面に出るたびに積まれないように（#135）
        store.saveLastAttemptedAt(clock.now())
        val snapshot = source.fetchAll()
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
        requireReady()
        val local = db.programDao().findById(programId.value) ?: return null
        val sourceId = local.sourceItemId?.let(::SourceItemId) ?: return null
        // `ParentId` での各回取得は番組が無くても空を返すので、先に番組そのものを確かめて消失を立てる／戻す
        val program = source.fetchProgram(sourceId)
        if (program == null) {
            if (local.goneSince == null) db.programDao().setGoneSince(local.id, clock.now())
            return RefreshResult(programs = 0, episodes = 0, linkedPrograms = 0, linkedEpisodes = 0, fetchedAt = clock.now(), onHold = 1)
        }
        if (local.goneSince != null) db.programDao().setGoneSince(local.id, null)
        // 突合（結び直しを含む）はこの番組の各回だけを相手にする。削除も予約もしない
        val snapshot = SourceSnapshot(
            programs = listOf(program),
            episodes = source.fetchProgramEpisodes(sourceId),
            scope = SnapshotScope.Program(sourceId),
        )
        return apply(snapshot)
    }

    override suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId>): RefreshResult? {
        requireReady()
        val local = db.programDao().findById(programId.value) ?: return null
        val sourceId = local.sourceItemId?.let(::SourceItemId) ?: return null
        val program = source.fetchProgram(sourceId)
        if (program == null) {
            // 消失。何も落とさず何も消さない。番組一覧が無いので結び直しは全体同期に任せる
            if (local.goneSince == null) db.programDao().setGoneSince(local.id, clock.now())
            return RefreshResult(programs = 0, episodes = 0, linkedPrograms = 0, linkedEpisodes = 0, fetchedAt = clock.now(), onHold = 1)
        }
        if (local.goneSince != null) db.programDao().setGoneSince(local.id, null)
        val snapshot = SourceSnapshot(
            programs = listOf(program),
            episodes = source.fetchProgramEpisodes(sourceId),
            scope = SnapshotScope.Program(sourceId),
        )
        val result = apply(snapshot)
        val outcome = synchronize(snapshot, excluded, onlyProgramId = programId)
        return result.copy(enqueued = outcome.enqueued, deleted = outcome.deleted, removed = outcome.removed, onHold = outcome.onHold)
    }

    private suspend fun requireReady(): SessionState.Ready =
        store.current() as? SessionState.Ready ?: throw ServerException.Unauthorized()

    internal suspend fun apply(snapshot: SourceSnapshot): RefreshResult = db.withTransaction {
        val programDao = db.programDao()
        val episodeDao = db.episodeDao()
        val match = LibraryMatching.match(
            localPrograms = programDao.listKeys().map { it.toDomain() },
            localEpisodes = episodeDao.listKeys().map { it.toDomain() },
            source = snapshot,
        )

        for ((programId, sourceId) in match.programLinks) {
            programDao.setSourceItemId(programId.value, sourceId.value)
        }
        for (sp in match.newPrograms) {
            programDao.insert(ProgramEntity(sourceItemId = sp.sourceId.value, name = sp.name, publisherName = sp.publisherName))
        }
        // 番組名・配信元は取得元が正
        val programIdBySourceId = HashMap<SourceItemId, Long>()
        for (sp in snapshot.programs) {
            val row = programDao.findBySourceItemId(sp.sourceId.value) ?: continue
            programIdBySourceId[sp.sourceId] = row.id
            if (row.name != sp.name || row.publisherName != sp.publisherName) {
                programDao.updateNames(row.id, sp.name, sp.publisherName)
            }
        }

        for ((episodeId, sourceId) in match.episodeLinks) {
            episodeDao.setSourceItemId(episodeId.value, sourceId.value)
        }
        // 結び直した回は上で取得元 ID を得ているので、ここで挿入されるのは手元に無かった回だけ（#142 の「新しい回」）
        var newEpisodes = 0
        for (se in snapshot.episodes) {
            val programId = programIdBySourceId[se.programSourceId] ?: continue
            val existing = episodeDao.findBySourceItemId(se.sourceId.value)
            if (existing == null) {
                episodeDao.insert(se.toEntity(programId, existingSize = null))
                newEpisodes++
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
            newEpisodes = newEpisodes,
        )
    }

    internal data class SyncOutcome(val enqueued: Int, val deleted: Int, val removed: Int, val onHold: Int)

    /**
     * 取り込み後の Room の状態と全走査の結果から [SyncPlanner] を回し、結論を実行する。
     * 実行順は 除去 → 削除 → 予約（容量を先に空ける）。[excluded]（再生中の回）は今回は消さない。
     * 取得元 ID を持たない番組は `Known` でも `Gone` でもない（取得元に在ると主張していない）ので入力に入れない。
     */
    internal suspend fun synchronize(snapshot: SourceSnapshot, excluded: Set<EpisodeId>, onlyProgramId: ProgramId? = null): SyncOutcome {
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
            when (input.source) {
                SourceEpisodes.Gone -> if (row.goneSince == null) db.programDao().setGoneSince(row.id, now)
                is SourceEpisodes.Known -> if (row.goneSince != null) db.programDao().setGoneSince(row.id, null)
                SourceEpisodes.Unavailable -> Unit
            }
        }
    }

    internal suspend fun plan(snapshot: SourceSnapshot, onlyProgramId: ProgramId? = null): SyncPlan =
        SyncPlanner.plan(inputs(snapshot, onlyProgramId))

    /** [onlyProgramId] を渡すとその番組だけを入力にする（1 番組の同期。他の番組は一覧に無くても消失扱いにしない）。 */
    private suspend fun inputs(snapshot: SourceSnapshot, onlyProgramId: ProgramId? = null): List<SyncProgramInput> {
        val sourceProgramIds = snapshot.programs.map { it.sourceId }.toSet()
        val sourceEpisodesByProgram = snapshot.episodes.groupBy({ it.programSourceId }, { it.sourceId })
        val localByProgram = db.episodeDao().listSyncRows().groupBy { it.programId }
        val programs = if (onlyProgramId == null) db.programDao().listAll() else listOfNotNull(db.programDao().findById(onlyProgramId.value))
        val inputs = programs.mapNotNull { p ->
            val sourceId = p.sourceItemId?.let(::SourceItemId) ?: return@mapNotNull null
            SyncProgramInput(
                programId = ProgramId(p.id),
                syncEnabled = p.syncEnabled,
                retentionRule = RetentionRule(p.keepLatest, p.deleteAfterPlayed),
                source = if (sourceId in sourceProgramIds) {
                    SourceEpisodes.Known(sourceEpisodesByProgram[sourceId].orEmpty().toSet())
                } else {
                    SourceEpisodes.Gone
                },
                local = localByProgram[p.id].orEmpty().map { it.toDomain() },
            )
        }
        return inputs
    }

    /** 取得元が返さない値（サイズ）は手元の値を残す。 */
    private fun SourceEpisode.toEntity(programId: Long, existingSize: Long?) = EpisodeEntity(
        sourceItemId = sourceId.value,
        programId = programId,
        title = title,
        publishedAt = publishedAt,
        addedAt = addedAt,
        runtimeTicks = Ticks.fromDuration(runtime),
        sizeBytes = sizeBytes ?: existingSize ?: 0L,
        container = container,
        performers = performers,
        sourceFileSize = sourceFileSize,
        sourceModifiedAt = sourceModifiedAt,
    )
}
