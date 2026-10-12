package dev.tseki.kikidame.sync

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.text.UiText

// 更新・同期の結果を [UiText] にする純粋な関数（`LibraryRefresher` が流す文言。文字列への解決は ui/ だけ。ADR 0009）。

/** 「N 回をダウンロード予約、M 回を削除」。0 のものは省く。何も無ければ null。区切りは [R.string.common_list_separator]。 */
private fun RefreshResult.actionsText(): UiText? {
    val actions = listOfNotNull(
        enqueued.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_enqueued, it) },
        (deleted + removed).takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_deleted, it) },
    )
    return actions.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, UiText.Res(R.string.common_list_separator)) }
}

/** 文を「。」（言語ごとの [R.string.sync_sentence_separator]）でつなぐ。 */
private fun sentences(parts: List<UiText>): UiText =
    UiText.Joined(parts, UiText.Res(R.string.sync_sentence_separator))

/**
 * 手元の変化の文（#142）: 「新しい回 N 件」「M 回をダウンロード予約、L 回を削除」。0 のものは省き、何も無ければ「最新の状態です」。
 * 取得した番組数・各回数は出さない（毎回ほぼ同じで、変化が埋もれる）。
 */
private fun RefreshResult.changes(): List<UiText> {
    if (!hasChanges) return listOf(UiText.Res(R.string.sync_up_to_date))
    return listOfNotNull(
        newEpisodes.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_new_episodes, it) },
        actionsText(),
    )
}

/** 全走査（同期）の結果の文言。変化に続けて、判断保留があればそれも出す。 */
fun RefreshResult.toSyncMessage(): UiText =
    sentences(changes() + listOfNotNull(onHold.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_on_hold, it) }))

/** 1 番組の同期・取り込みの文言。番組が取得元で見つからなければそれだけを出す。 */
fun RefreshResult.toProgramSyncMessage(): UiText {
    if (onHold > 0) return UiText.Res(R.string.sync_program_gone)
    return sentences(changes())
}
