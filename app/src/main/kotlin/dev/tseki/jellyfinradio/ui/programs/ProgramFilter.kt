package dev.tseki.jellyfinradio.ui.programs

import dev.tseki.jellyfinradio.domain.ProgramSummary

/**
 * 番組一覧の絞り込み。検索（#44）は番組名と放送局名の部分一致で、大文字小文字は区別しない。
 * 放送局のチップ（#45）は局名の完全一致。両方あれば AND。
 * 全角・半角やかなの表記揺れは吸収しない（サーバの表記そのまま）。
 */
object ProgramFilter {
    /** 放送局の選択の鍵。[name] が null は「局なし」（放送局を持たない番組）。未選択は [StationKey] 自体を null にして区別する。 */
    data class StationKey(val name: String?) {
        val label: String get() = name ?: "局なし"
    }

    /** チップ 1 つ分。 */
    data class Station(val key: StationKey, val programCount: Int)

    /** 検索語として意味のある形にする（前後の空白を落とす）。空なら絞り込みなし。 */
    fun normalize(query: String): String = query.trim()

    /** 絞り込みが効いているか（検索語か局のどちらか）。 */
    fun isActive(query: String, station: StationKey? = null): Boolean =
        normalize(query).isNotEmpty() || station != null

    /** 放送局が無い番組は番組名だけで検索語を判定する。 */
    fun apply(list: List<ProgramSummary>, query: String, station: StationKey? = null): List<ProgramSummary> {
        val q = normalize(query)
        if (q.isEmpty() && station == null) return list
        return list.filter { (station == null || it.program.stationName == station.name) && (q.isEmpty() || it.matches(q)) }
    }

    /** 手元の番組から集めた放送局。番組数が多い順、同数は局名の辞書順（同期のたびに順序が入れ替わらない）。「局なし」は番組数に関わらず最後。 */
    fun stations(list: List<ProgramSummary>): List<Station> =
        list.groupingBy { it.program.stationName }.eachCount()
            .map { (name, count) -> Station(StationKey(name), count) }
            .sortedWith(
                compareBy<Station> { it.key.name == null }
                    .thenByDescending { it.programCount }
                    .thenBy { it.key.name },
            )

    private fun ProgramSummary.matches(q: String): Boolean =
        program.name.contains(q, ignoreCase = true) ||
            program.stationName?.contains(q, ignoreCase = true) == true
}
