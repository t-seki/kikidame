package dev.tseki.kikidame.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 突合に必要な列だけの手元の番組。 */
data class LocalProgramKey(
    val id: ProgramId,
    val sourceItemId: SourceItemId?,
    val publisherName: String?,
    val name: String,
)

/** 突合に必要な列だけの手元の各回。公開日と尺は第二段（タイトルで結べなかったとき）に使う。 */
data class LocalEpisodeKey(
    val id: EpisodeId,
    val sourceItemId: SourceItemId?,
    val programId: ProgramId,
    val title: String,
    val publishedAt: Instant,
    /** 不明なら [Duration.ZERO]（第二段の対象外）。 */
    val runtime: Duration = Duration.ZERO,
)

/**
 * 突合の結果。取得元 ID で同一視できる行はここには現れない。
 *
 * @property programLinks 取得元 ID を付ける（または付け直す）手元の番組
 * @property newPrograms 手元に対応する行が無い取得元の番組
 * @property episodeLinks 取得元 ID を付ける（または付け直す）手元の各回
 * @property newEpisodes 手元に対応する行が無い取得元の各回
 */
data class LibraryMatch(
    val programLinks: Map<ProgramId, SourceItemId>,
    val newPrograms: List<SourceProgram>,
    val episodeLinks: Map<EpisodeId, SourceItemId>,
    val newEpisodes: List<SourceEpisode>,
)

/**
 * 突合（CONTEXT.md「未結合」「突合」、ADR 0005）の純粋関数。
 *
 * - 対象は**未結合**の手元の行: 取得元 ID を持たない行と、持っている取得元 ID がスナップショットに無い行（再取り込み後）。
 *   ただしスナップショットが 1 番組分（[SourceSnapshot.scope]）のときは、番組の一覧が無いので番組の結び直しはせず、
 *   各回の結び直しもその番組のものに限る
 * - 番組は（配信元, 番組名）、各回は同じ番組内のタイトルの完全一致。タイトルで結べなかった各回は
 *   同じ番組内で公開日が同じ ＋ 尺の差が [RUNTIME_TOLERANCE] 以内（尺 0 は対象外）
 * - どちらの段でも、手元側・取得元側のどちらかに候補が複数あれば結ばない（取得元側は新規行になる）
 * - 表記ゆれは吸収しない
 */
object LibraryMatching {
    val RUNTIME_TOLERANCE: Duration = 5.seconds
    private fun LocalEpisodeKey.closeTo(other: Duration): Boolean = (runtime - other).absoluteValue <= RUNTIME_TOLERANCE

    fun match(
        localPrograms: List<LocalProgramKey>,
        localEpisodes: List<LocalEpisodeKey>,
        source: SourceSnapshot,
    ): LibraryMatch {
        val scope = source.scope
        val sourceProgramIds = source.programs.map { it.sourceId }.toSet()

        // --- 番組 ---
        // 結び付いている = 取得元 ID がスナップショットに在る。番組一覧が無い（1 番組分）なら、ID を持つ行はすべて結び付いているとみなす
        fun LocalProgramKey.isLinked(): Boolean =
            sourceItemId != null && (scope !is SnapshotScope.Library || sourceItemId in sourceProgramIds)

        val linkedPrograms = localPrograms.filter { it.isLinked() }
        val linkedProgramIds = linkedPrograms.mapNotNull { it.sourceItemId }.toSet()
        val unlinkedProgramsByKey = localPrograms.filter { !it.isLinked() }.groupBy { it.publisherName to it.name }
        // 既に結び付いている取得元の番組は突合の相手にも曖昧判定の分母にも入れない
        val freeSourceProgramsByKey = source.programs.filter { it.sourceId !in linkedProgramIds }.groupBy { it.publisherName to it.name }

        val programLinks = HashMap<ProgramId, SourceItemId>()
        val newPrograms = ArrayList<SourceProgram>()
        // 取得元の番組 → 手元の番組 ID（既知・今回結んだもの）。新規の番組は null（手元に行がまだ無い）
        val localProgramOf = HashMap<SourceItemId, ProgramId?>()
        val linkedBySourceId = linkedPrograms.associateBy { it.sourceItemId!! }

        for (sp in source.programs) {
            val linked = linkedBySourceId[sp.sourceId]
            if (linked != null) {
                localProgramOf[sp.sourceId] = linked.id
                continue
            }
            val key = sp.publisherName to sp.name
            val localCandidates = unlinkedProgramsByKey[key].orEmpty()
            val sourceCandidates = freeSourceProgramsByKey.getValue(key)
            if (localCandidates.size == 1 && sourceCandidates.size == 1) {
                val local = localCandidates.single()
                programLinks[local.id] = sp.sourceId
                localProgramOf[sp.sourceId] = local.id
            } else {
                newPrograms += sp
                localProgramOf[sp.sourceId] = null
            }
        }

        // --- 各回 ---
        val sourceEpisodeIds = source.episodes.map { it.sourceId }.toSet()
        // 手元の番組 ID → 結び付いた後の取得元 ID
        val programSourceIdOf = HashMap<ProgramId, SourceItemId>()
        for (p in linkedPrograms) programSourceIdOf[p.id] = p.sourceItemId!!
        for ((id, sid) in programLinks) programSourceIdOf[id] = sid

        // 結び直しの対象になる番組: 完全な一覧ならすべて、1 番組分ならその番組だけ
        fun LocalEpisodeKey.isRelinkable(): Boolean = when (scope) {
            SnapshotScope.Library -> true
            is SnapshotScope.Program -> programSourceIdOf[programId] == scope.programSourceId
        }
        fun LocalEpisodeKey.isLinked(): Boolean =
            sourceItemId != null && (sourceItemId in sourceEpisodeIds || !isRelinkable())

        val linkedEpisodeIds = localEpisodes.filter { it.isLinked() }.mapNotNull { it.sourceItemId }.toSet()
        val unlinkedEpisodes = localEpisodes.filter { !it.isLinked() }
        val freeSourceEpisodes = source.episodes.filter { it.sourceId !in linkedEpisodeIds }

        val episodeLinks = HashMap<EpisodeId, SourceItemId>()
        val newEpisodes = ArrayList<SourceEpisode>()

        // 第一段: 同じ番組内のタイトル完全一致
        val unlinkedByTitle = unlinkedEpisodes.groupBy { it.programId to it.title }
        val freeByTitle = freeSourceEpisodes.groupBy { it.programSourceId to it.title }
        val unmatchedSource = ArrayList<SourceEpisode>()
        for (se in freeSourceEpisodes) {
            val programId = localProgramOf[se.programSourceId]
            val localCandidates = programId?.let { unlinkedByTitle[it to se.title] }.orEmpty()
            val sourceCandidates = freeByTitle.getValue(se.programSourceId to se.title)
            if (localCandidates.size == 1 && sourceCandidates.size == 1) {
                episodeLinks[localCandidates.single().id] = se.sourceId
            } else {
                unmatchedSource += se
            }
        }

        // 第二段: 同じ番組内で公開日が同じ ＋ 尺がほぼ同じ。尺 0 の手元行は対象外
        val remainingLocal = unlinkedEpisodes.filter { it.id !in episodeLinks && it.runtime > Duration.ZERO }
        val remainingLocalByDay = remainingLocal.groupBy { it.programId to it.publishedAt }
        val unmatchedSourceByDay = unmatchedSource.groupBy { it.programSourceId to it.publishedAt }
        for (se in unmatchedSource) {
            val programId = localProgramOf[se.programSourceId]
            val localCandidates = programId?.let { remainingLocalByDay[it to se.publishedAt] }.orEmpty()
                .filter { it.id !in episodeLinks && it.closeTo(se.runtime) }
            val local = localCandidates.singleOrNull()
            // その手元行から見ても取得元側の候補が 1 つだけのとき結ぶ（同日パート違いを尺で区別できないなら結ばない）
            val sourceCandidates = local?.let { l -> unmatchedSourceByDay.getValue(se.programSourceId to se.publishedAt).filter { l.closeTo(it.runtime) } }
            if (local != null && sourceCandidates?.size == 1) {
                episodeLinks[local.id] = se.sourceId
            } else {
                newEpisodes += se
            }
        }

        return LibraryMatch(programLinks, newPrograms, episodeLinks, newEpisodes)
    }
}
