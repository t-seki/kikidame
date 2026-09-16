package dev.tseki.jellyfinradio.domain

/** 突合に必要な列だけの手元の番組。 */
data class LocalProgramKey(
    val id: ProgramId,
    val serverItemId: ServerItemId?,
    val stationName: String?,
    val name: String,
)

/** 突合に必要な列だけの手元の各回。 */
data class LocalEpisodeKey(
    val id: EpisodeId,
    val serverItemId: ServerItemId?,
    val programId: ProgramId,
    val title: String,
)

/**
 * 突合の結果。サーバ ID を持つ行はここには現れない（ID で同一視するだけ）。
 *
 * @property programLinks サーバ ID を付ける手元の番組
 * @property newPrograms 手元に対応する行が無いサーバの番組
 * @property episodeLinks サーバ ID を付ける手元の各回
 * @property newEpisodes 手元に対応する行が無いサーバの各回
 */
data class LibraryMatch(
    val programLinks: Map<ProgramId, ServerItemId>,
    val newPrograms: List<ServerProgram>,
    val episodeLinks: Map<EpisodeId, ServerItemId>,
    val newEpisodes: List<ServerEpisode>,
)

/**
 * 突合（CONTEXT.md「突合」）の純粋関数。
 *
 * - サーバ ID を持たない手元の行にだけ行う
 * - 番組は（放送局, 番組名）、各回は同じ番組内のタイトルの完全一致
 * - 手元側・サーバ側のどちらかに候補が複数あれば結ばない（サーバ側は新規行になる）
 * - 表記ゆれは吸収しない
 */
object LibraryMatching {
    fun match(
        localPrograms: List<LocalProgramKey>,
        localEpisodes: List<LocalEpisodeKey>,
        server: ServerSnapshot,
    ): LibraryMatch {
        val knownProgramIds = localPrograms.mapNotNull { it.serverItemId }.toSet()
        val localProgramsByKey = localPrograms
            .filter { it.serverItemId == null }
            .groupBy { it.stationName to it.name }
        // サーバ ID で同一視できる行は突合の対象外なので、曖昧判定の分母にも入れない
        val serverProgramsByKey = server.programs
            .filter { it.serverId !in knownProgramIds }
            .groupBy { it.stationName to it.name }

        val programLinks = HashMap<ProgramId, ServerItemId>()
        val newPrograms = ArrayList<ServerProgram>()
        // サーバの番組 → 手元の番組 ID（既知・今回結んだもの）。新規の番組は null（手元に行がまだ無い）
        val localProgramOf = HashMap<ServerItemId, ProgramId?>()
        val localByServerId = localPrograms.filter { it.serverItemId != null }.associateBy { it.serverItemId!! }

        for (sp in server.programs) {
            if (sp.serverId in knownProgramIds) {
                localProgramOf[sp.serverId] = localByServerId.getValue(sp.serverId).id
                continue
            }
            val key = sp.stationName to sp.name
            val localCandidates = localProgramsByKey[key].orEmpty()
            val serverCandidates = serverProgramsByKey.getValue(key)
            if (localCandidates.size == 1 && serverCandidates.size == 1) {
                val local = localCandidates.single()
                programLinks[local.id] = sp.serverId
                localProgramOf[sp.serverId] = local.id
            } else {
                newPrograms += sp
                localProgramOf[sp.serverId] = null
            }
        }

        val knownEpisodeIds = localEpisodes.mapNotNull { it.serverItemId }.toSet()
        val localEpisodesByKey = localEpisodes
            .filter { it.serverItemId == null }
            .groupBy { it.programId to it.title }
        val serverEpisodesByKey = server.episodes
            .filter { it.serverId !in knownEpisodeIds }
            .groupBy { it.programServerId to it.title }

        val episodeLinks = HashMap<EpisodeId, ServerItemId>()
        val newEpisodes = ArrayList<ServerEpisode>()

        for (se in server.episodes) {
            if (se.serverId in knownEpisodeIds) continue
            val programId = localProgramOf[se.programServerId]
            val localCandidates = programId?.let { localEpisodesByKey[it to se.title] }.orEmpty()
            val serverCandidates = serverEpisodesByKey.getValue(se.programServerId to se.title)
            if (localCandidates.size == 1 && serverCandidates.size == 1) {
                episodeLinks[localCandidates.single().id] = se.serverId
            } else {
                newEpisodes += se
            }
        }

        return LibraryMatch(programLinks, newPrograms, episodeLinks, newEpisodes)
    }
}
