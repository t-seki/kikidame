package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.domain.SourceItemId
import java.util.UUID

/** ブックのライブラリの 1 番組の同期で、絞り込み（各回の親の直下だけ）をやめてライブラリ全体の取得に切り替える理由（ADR 0016）。 */
internal enum class FullListingReason {
    /** 手元に取得元 ID のある各回が 0 本で、親を引けない */
    NO_KNOWN_EPISODES,

    /** `ids=` で引いた既知の各回が、サーバに無い */
    KNOWN_EPISODE_NOT_ON_SERVER,

    /** 既知の各回が、絞り込みの結果に 1 本でも載らない（`ids=` で引いた各回の親が複数に分かれている・親が無い場合を含む） */
    KNOWN_EPISODE_NOT_IN_NARROWED,
}

/** 絞り込みの判定の結果。 */
internal sealed interface BookNarrowing {
    /** [parentId] の直下だけを取ればよい（その結果を、その番組の完全な一覧とみなせる）。 */
    data class Narrow(val parentId: UUID) : BookNarrowing

    /** 絞り込みの結果を完全な一覧と見ずに、ライブラリ全体を取って削除を判断する。 */
    data class FullListing(val reason: FullListingReason) : BookNarrowing
}

/**
 * ブックのライブラリの 1 番組の同期の安全網（ADR 0016）。SDK を呼ばない純粋な関数だけを置く。
 *
 * 取り方は、手元が知っているその番組の各回（[sample] で選んだ数本）を `ids=` で引いて親（`ParentId`）を得て、
 * その親の直下（`parentId=<親>&recursive=false`）の AudioBook をタグで番組に選び直す。
 * 削除の権限を持つのは完全な一覧だけ（ADR 0003）なので、絞り込みの結果に既知の各回が 1 本でも載らなければ、全件に切り替える。
 */
internal object BookProgramNarrowing {
    /** 親を得るために `ids=` で引く既知の各回の数。 */
    const val SAMPLE_SIZE = 3

    /** 親を得るために `ids=` で引く既知の各回。[known] は新しい順（取得元の境界の決まり）なので、先頭の数本。 */
    fun sample(known: Set<SourceItemId>): List<SourceItemId> = known.take(SAMPLE_SIZE)

    /**
     * 絞り込みの親を決める（直下を取る前の判定）。[lookedUp] は `ids=` で引けた各回の取得元 ID → その親（無ければ null）。
     * サーバに無かった各回は [lookedUp] に載らない。
     */
    fun chooseParent(known: Set<SourceItemId>, lookedUp: Map<SourceItemId, UUID?>): BookNarrowing {
        val queried = sample(known)
        if (queried.isEmpty()) return BookNarrowing.FullListing(FullListingReason.NO_KNOWN_EPISODES)
        if (queried.any { it !in lookedUp }) return BookNarrowing.FullListing(FullListingReason.KNOWN_EPISODE_NOT_ON_SERVER)
        val parent = queried.map { lookedUp[it] }.distinct().singleOrNull()
            ?: return BookNarrowing.FullListing(FullListingReason.KNOWN_EPISODE_NOT_IN_NARROWED)
        return BookNarrowing.Narrow(parent)
    }

    /**
     * 絞り込みで良いかの判定。[narrowed] は、親の直下を取ってその番組のタグで選び直した各回の取得元 ID。
     * 既知の各回が全部 [narrowed] に載っていれば [BookNarrowing.Narrow]、そうでなければ全件に切り替える理由。
     */
    fun judge(known: Set<SourceItemId>, lookedUp: Map<SourceItemId, UUID?>, narrowed: Collection<SourceItemId>): BookNarrowing {
        val choice = chooseParent(known, lookedUp)
        if (choice is BookNarrowing.FullListing) return choice
        val listed = narrowed.toSet()
        if (known.any { it !in listed }) return BookNarrowing.FullListing(FullListingReason.KNOWN_EPISODE_NOT_IN_NARROWED)
        return choice
    }
}
