package dev.tseki.jellyfinradio.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 突合に必要な列だけの手元の番組。 */
data class LocalProgramKey(
    val id: ProgramId,
    val serverItemId: ServerItemId?,
    val stationName: String?,
    val name: String,
)

/** 突合に必要な列だけの手元の各回。放送日と尺は第二段（タイトルで結べなかったとき）に使う。 */
data class LocalEpisodeKey(
    val id: EpisodeId,
    val serverItemId: ServerItemId?,
    val programId: ProgramId,
    val title: String,
    val airedAt: Instant,
    /** 不明なら [Duration.ZERO]（第二段の対象外）。 */
    val runtime: Duration = Duration.ZERO,
)

/**
 * 突合の結果。サーバ ID で同一視できる行はここには現れない。
 *
 * @property programLinks サーバ ID を付ける（または付け直す）手元の番組
 * @property newPrograms 手元に対応する行が無いサーバの番組
 * @property episodeLinks サーバ ID を付ける（または付け直す）手元の各回
 * @property newEpisodes 手元に対応する行が無いサーバの各回
 */
data class LibraryMatch(
    val programLinks: Map<ProgramId, ServerItemId>,
    val newPrograms: List<ServerProgram>,
    val episodeLinks: Map<EpisodeId, ServerItemId>,
    val newEpisodes: List<ServerEpisode>,
)

/**
 * 突合（CONTEXT.md「未結合」「突合」、ADR 0005）の純粋関数。
 *
 * - 対象は**未結合**の手元の行: サーバ ID を持たない行と、持っているサーバ ID がスナップショットに無い行（再取り込み後）。
 *   ただしスナップショットが 1 番組分（[ServerSnapshot.scope]）のときは、番組の一覧が無いので番組の結び直しはせず、
 *   各回の結び直しもその番組のものに限る
 * - 番組は（放送局, 番組名）、各回は同じ番組内のタイトルの完全一致。タイトルで結べなかった各回は
 *   同じ番組内で放送日が同じ ＋ 尺の差が [RUNTIME_TOLERANCE] 以内（尺 0 は対象外）
 * - どちらの段でも、手元側・サーバ側のどちらかに候補が複数あれば結ばない（サーバ側は新規行になる）
 * - 表記ゆれは吸収しない
 */
object LibraryMatching {
    val RUNTIME_TOLERANCE: Duration = 5.seconds
    private fun LocalEpisodeKey.closeTo(other: Duration): Boolean = (runtime - other).absoluteValue <= RUNTIME_TOLERANCE

    fun match(
        localPrograms: List<LocalProgramKey>,
        localEpisodes: List<LocalEpisodeKey>,
        server: ServerSnapshot,
    ): LibraryMatch {
        val scope = server.scope
        val serverProgramIds = server.programs.map { it.serverId }.toSet()

        // --- 番組 ---
        // 結び付いている = サーバ ID がスナップショットに在る。番組一覧が無い（1 番組分）なら、ID を持つ行はすべて結び付いているとみなす
        fun LocalProgramKey.isLinked(): Boolean =
            serverItemId != null && (scope !is SnapshotScope.Library || serverItemId in serverProgramIds)

        val linkedPrograms = localPrograms.filter { it.isLinked() }
        val linkedProgramIds = linkedPrograms.mapNotNull { it.serverItemId }.toSet()
        val unlinkedProgramsByKey = localPrograms.filter { !it.isLinked() }.groupBy { it.stationName to it.name }
        // 既に結び付いているサーバの番組は突合の相手にも曖昧判定の分母にも入れない
        val freeServerProgramsByKey = server.programs.filter { it.serverId !in linkedProgramIds }.groupBy { it.stationName to it.name }

        val programLinks = HashMap<ProgramId, ServerItemId>()
        val newPrograms = ArrayList<ServerProgram>()
        // サーバの番組 → 手元の番組 ID（既知・今回結んだもの）。新規の番組は null（手元に行がまだ無い）
        val localProgramOf = HashMap<ServerItemId, ProgramId?>()
        val linkedByServerId = linkedPrograms.associateBy { it.serverItemId!! }

        for (sp in server.programs) {
            val linked = linkedByServerId[sp.serverId]
            if (linked != null) {
                localProgramOf[sp.serverId] = linked.id
                continue
            }
            val key = sp.stationName to sp.name
            val localCandidates = unlinkedProgramsByKey[key].orEmpty()
            val serverCandidates = freeServerProgramsByKey.getValue(key)
            if (localCandidates.size == 1 && serverCandidates.size == 1) {
                val local = localCandidates.single()
                programLinks[local.id] = sp.serverId
                localProgramOf[sp.serverId] = local.id
            } else {
                newPrograms += sp
                localProgramOf[sp.serverId] = null
            }
        }

        // --- 各回 ---
        val serverEpisodeIds = server.episodes.map { it.serverId }.toSet()
        // 手元の番組 ID → 結び付いた後のサーバ ID
        val programServerIdOf = HashMap<ProgramId, ServerItemId>()
        for (p in linkedPrograms) programServerIdOf[p.id] = p.serverItemId!!
        for ((id, sid) in programLinks) programServerIdOf[id] = sid

        // 結び直しの対象になる番組: 完全な一覧ならすべて、1 番組分ならその番組だけ
        fun LocalEpisodeKey.isRelinkable(): Boolean = when (scope) {
            SnapshotScope.Library -> true
            is SnapshotScope.Program -> programServerIdOf[programId] == scope.programServerId
        }
        fun LocalEpisodeKey.isLinked(): Boolean =
            serverItemId != null && (serverItemId in serverEpisodeIds || !isRelinkable())

        val linkedEpisodeIds = localEpisodes.filter { it.isLinked() }.mapNotNull { it.serverItemId }.toSet()
        val unlinkedEpisodes = localEpisodes.filter { !it.isLinked() }
        val freeServerEpisodes = server.episodes.filter { it.serverId !in linkedEpisodeIds }

        val episodeLinks = HashMap<EpisodeId, ServerItemId>()
        val newEpisodes = ArrayList<ServerEpisode>()

        // 第一段: 同じ番組内のタイトル完全一致
        val unlinkedByTitle = unlinkedEpisodes.groupBy { it.programId to it.title }
        val freeByTitle = freeServerEpisodes.groupBy { it.programServerId to it.title }
        val unmatchedServer = ArrayList<ServerEpisode>()
        for (se in freeServerEpisodes) {
            val programId = localProgramOf[se.programServerId]
            val localCandidates = programId?.let { unlinkedByTitle[it to se.title] }.orEmpty()
            val serverCandidates = freeByTitle.getValue(se.programServerId to se.title)
            if (localCandidates.size == 1 && serverCandidates.size == 1) {
                episodeLinks[localCandidates.single().id] = se.serverId
            } else {
                unmatchedServer += se
            }
        }

        // 第二段: 同じ番組内で放送日が同じ ＋ 尺がほぼ同じ。尺 0 の手元行は対象外
        val remainingLocal = unlinkedEpisodes.filter { it.id !in episodeLinks && it.runtime > Duration.ZERO }
        val remainingLocalByDay = remainingLocal.groupBy { it.programId to it.airedAt }
        val unmatchedServerByDay = unmatchedServer.groupBy { it.programServerId to it.airedAt }
        for (se in unmatchedServer) {
            val programId = localProgramOf[se.programServerId]
            val localCandidates = programId?.let { remainingLocalByDay[it to se.airedAt] }.orEmpty()
                .filter { it.id !in episodeLinks && it.closeTo(se.runtime) }
            val local = localCandidates.singleOrNull()
            // その手元行から見てもサーバ側の候補が 1 つだけのとき結ぶ（同日パート違いを尺で区別できないなら結ばない）
            val serverCandidates = local?.let { l -> unmatchedServerByDay.getValue(se.programServerId to se.airedAt).filter { l.closeTo(it.runtime) } }
            if (local != null && serverCandidates?.size == 1) {
                episodeLinks[local.id] = se.serverId
            } else {
                newEpisodes += se
            }
        }

        return LibraryMatch(programLinks, newPrograms, episodeLinks, newEpisodes)
    }
}
