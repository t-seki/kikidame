package dev.tseki.jellyfinradio.ui.programs

import dev.tseki.jellyfinradio.domain.ProgramSummary

/**
 * 番組一覧の検索（#44）。番組名と放送局名の部分一致で絞る。大文字小文字は区別しない。
 * 全角・半角やかなの表記揺れは吸収しない（サーバの表記そのまま）。
 */
object ProgramFilter {
    /** 検索語として意味のある形にする（前後の空白を落とす）。空なら絞り込みなし。 */
    fun normalize(query: String): String = query.trim()

    /** 絞り込みが効いているか。 */
    fun isActive(query: String): Boolean = normalize(query).isNotEmpty()

    /** 放送局が無い番組は番組名だけで判定する。 */
    fun apply(list: List<ProgramSummary>, query: String): List<ProgramSummary> {
        val q = normalize(query)
        if (q.isEmpty()) return list
        return list.filter { it.matches(q) }
    }

    private fun ProgramSummary.matches(q: String): Boolean =
        program.name.contains(q, ignoreCase = true) ||
            program.stationName?.contains(q, ignoreCase = true) == true
}
