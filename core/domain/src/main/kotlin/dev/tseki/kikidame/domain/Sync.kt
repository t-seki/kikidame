package dev.tseki.kikidame.domain

import kotlin.time.Instant

/**
 * ある番組について、取得元の各回一覧がどう見えているか（CONTEXT.md「判断保留」「到達不能」「消失」）。
 * 「一覧が空」は `Known(emptySet())` で、`Unavailable` とは型で区別する。
 */
sealed interface SourceEpisodes {
    /** 完全な一覧が取れた。削除の権限を持つのはこの形だけ（ADR 0004）。 */
    data class Known(val sourceIds: Set<SourceItemId>) : SourceEpisodes

    /** 一覧を取得できなかった。M3-b では全走査しか無いので番組ごとにこの形になる経路は無いが、型は残す。 */
    data object Unavailable : SourceEpisodes

    /** 番組の取得元 ID が取得元の番組一覧に無い。 */
    data object Gone : SourceEpisodes
}

/** 同期の判断に必要な列だけの手元の各回。取り込み後なので、取得元の各回にはすべて行がある。 */
data class LocalEpisodeState(
    val id: EpisodeId,
    val sourceItemId: SourceItemId?,
    val publishedAt: Instant,
    val title: String,
    /** 固定（`LocalFile.pinned`）。行が無ければ false。 */
    val pinned: Boolean,
    val played: Boolean,
    /** PENDING / RUNNING / DONE / FAILED のいずれかの `LocalFile` 行があるか。FAILED も「手元にある」。 */
    val hasLocalFile: Boolean,
)

data class SyncProgramInput(
    val programId: ProgramId,
    val syncEnabled: Boolean,
    val retentionRule: RetentionRule,
    val source: SourceEpisodes,
    val local: List<LocalEpisodeState>,
)

/**
 * 1 番組分の同期の結論。
 *
 * @property download 保持すべきなのに手元に無い各回（公開日の新しい順）
 * @property delete 保持ルールで手元に置かない各回。ファイルと `LocalFile` だけ消す（再生位置は残る）
 * @property remove 取得元の一覧から消えた各回。`Episode` 行ごと消す（固定でも）
 * @property onHold 判断保留（到達不能・消失）。他の 3 つは空
 */
data class SyncProgramPlan(
    val programId: ProgramId,
    val download: List<EpisodeId> = emptyList(),
    val delete: List<EpisodeId> = emptyList(),
    val remove: List<EpisodeId> = emptyList(),
    val onHold: Boolean = false,
)

/** 全番組分をまとめたもの。[download] は番組をまたいで公開日の新しい順。 */
data class SyncPlan(
    val programs: List<SyncProgramPlan>,
    val download: List<EpisodeId>,
) {
    val delete: List<EpisodeId> get() = programs.flatMap { it.delete }
    val remove: List<EpisodeId> get() = programs.flatMap { it.remove }
    val onHoldCount: Int get() = programs.count { it.onHold }
}

/**
 * 同期の純粋関数（handoff「同期エンジンの設計（M3）」「M3-b の範囲」）。I/O はしない。
 *
 * - `Unavailable` / `Gone` → 判断保留。何も出さない
 * - `Known` → 取得元の一覧に無い `sourceItemId != null` の各回は固定でも [SyncProgramPlan.remove]
 * - 固定された各回は保持ルールの外。「最新 N 回」の N にも数えない
 * - 同期対象でない番組は保持すべき集合が空: 固定でない手元の各回はすべて [SyncProgramPlan.delete]
 * - 同期対象なら、固定でない取得元の各回を公開日の新しい順に並べ、N 回まで取り、「再生済みなら削除」なら再生済みを除いたものが保持すべき集合。
 *   保持すべき − 手元にある → download、手元にある − 保持すべき → delete
 */
object SyncPlanner {
    fun planProgram(input: SyncProgramInput): SyncProgramPlan {
        val known = when (val s = input.source) {
            is SourceEpisodes.Known -> s
            SourceEpisodes.Unavailable, SourceEpisodes.Gone -> return SyncProgramPlan(input.programId, onHold = true)
        }
        val (gone, present) = input.local.partition { it.sourceItemId != null && it.sourceItemId !in known.sourceIds }

        val unpinned = present.filter { !it.pinned }
        val keep: Set<EpisodeId> = if (!input.syncEnabled) {
            emptySet()
        } else {
            val ordered = unpinned.filter { it.sourceItemId != null }.sortedWith(NEWEST_FIRST)
            val latest = input.retentionRule.keepLatest?.let { ordered.take(it) } ?: ordered
            latest.filter { !(input.retentionRule.deleteAfterPlayed && it.played) }.map { it.id }.toSet()
        }
        val keepOrdered = unpinned.filter { it.id in keep }.sortedWith(NEWEST_FIRST)
        return SyncProgramPlan(
            programId = input.programId,
            download = keepOrdered.filter { !it.hasLocalFile }.map { it.id },
            delete = unpinned.filter { it.hasLocalFile && it.id !in keep }.map { it.id },
            remove = gone.map { it.id },
        )
    }

    fun plan(inputs: List<SyncProgramInput>): SyncPlan {
        val plans = inputs.map(::planProgram)
        val publishedAtById = inputs.asSequence().flatMap { it.local }.associate { it.id to (it.publishedAt to it.title) }
        val download = plans.flatMap { it.download }
            .sortedWith(compareByDescending<EpisodeId> { publishedAtById.getValue(it).first }.thenBy { publishedAtById.getValue(it).second }.thenBy { it.value })
        return SyncPlan(plans, download)
    }

    /** [EpisodeOrder.newestFirst] と同じ順（公開日の新しい順 → タイトル → ローカル ID）。 */
    private val NEWEST_FIRST: Comparator<LocalEpisodeState> =
        compareByDescending<LocalEpisodeState> { it.publishedAt }.thenBy { it.title }.thenBy { it.id.value }
}
