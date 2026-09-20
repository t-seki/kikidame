package dev.tseki.kikidame.ui.programs

import dev.tseki.kikidame.domain.ProgramSummary

/**
 * 番組一覧の絞り込み。検索（#44）は番組名と配信元名の部分一致で、大文字小文字は区別しない。
 * 配信元の絞り込み（#45）は局名の完全一致。両方あれば AND。
 * 全角・半角やかなの表記揺れは吸収しない（サーバの表記そのまま）。
 */
object ProgramFilter {
    /** 配信元の選択の鍵。[name] が null は「局なし」（配信元を持たない番組）。未選択は [PublisherKey] 自体を null にして区別する。 */
    data class PublisherKey(val name: String?) {
        val label: String get() = name ?: "局なし"
    }

    /** 局を選ぶシートの 1 行分。[programCount] は手元の番組全体での数で、検索語では絞らない。 */
    data class Publisher(val key: PublisherKey, val programCount: Int)

    /** 検索語として意味のある形にする（前後の空白を落とす）。空なら絞り込みなし。 */
    fun normalize(query: String): String = query.trim()

    /** 絞り込みが効いているか（検索語か局のどちらか）。 */
    fun isActive(query: String, publisher: PublisherKey? = null): Boolean =
        normalize(query).isNotEmpty() || publisher != null

    /** 配信元が無い番組は番組名だけで検索語を判定する。 */
    fun apply(list: List<ProgramSummary>, query: String, publisher: PublisherKey? = null): List<ProgramSummary> {
        val q = normalize(query)
        if (q.isEmpty() && publisher == null) return list
        return list.filter { (publisher == null || it.program.publisherName == publisher.name) && (q.isEmpty() || it.matches(q)) }
    }

    /** 手元の番組から集めた配信元。番組数が多い順、同数は局名の辞書順（同期のたびに順序が入れ替わらない）。「局なし」は番組数に関わらず最後。 */
    fun publishers(list: List<ProgramSummary>): List<Publisher> =
        list.groupingBy { it.program.publisherName }.eachCount()
            .map { (name, count) -> Publisher(PublisherKey(name), count) }
            .sortedWith(
                compareBy<Publisher> { it.key.name == null }
                    .thenByDescending { it.programCount }
                    .thenBy { it.key.name },
            )

    private fun ProgramSummary.matches(q: String): Boolean =
        program.name.contains(q, ignoreCase = true) ||
            program.publisherName?.contains(q, ignoreCase = true) == true
}
