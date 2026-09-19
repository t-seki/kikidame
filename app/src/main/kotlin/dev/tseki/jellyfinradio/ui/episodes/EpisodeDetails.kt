package dev.tseki.jellyfinradio.ui.episodes

import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.ui.toAiredDateText
import dev.tseki.jellyfinradio.ui.toClockText
import dev.tseki.jellyfinradio.ui.toDateTimeText
import dev.tseki.jellyfinradio.ui.toSizeText

/**
 * 各回の詳細シート（#43）に並べる値。Room にあるものを整形するだけで、サーバへは問い合わせない。
 * 利用者向けの群と、デバッグ向けの「技術的な詳細」に分ける（後者は折り畳み、長押しでコピー）。
 */
object EpisodeDetails {
    data class Row(val label: String, val value: String)
    data class Section(val title: String, val rows: List<Row>)

    /** 利用者向け: 各回・手元・再生の 3 群。手元に無い／再生記録が無いときはその群を 1 行に畳む。サイズが記録されていなければ「不明」。 */
    fun sections(item: EpisodeWithState): List<Section> {
        val e = item.episode
        val local = item.localFile
        val playback = item.playback
        val episode = Section(
            "各回",
            listOf(
                Row("放送日", e.airedAt.toAiredDateText()),
                Row("尺", e.runtime.toClockText()),
                Row("サイズ", if (e.sizeBytes > 0) e.sizeBytes.toSizeText() else "不明"),
            ),
        )
        val file = Section(
            "手元",
            if (local == null) {
                listOf(Row("状態", "手元に無い"))
            } else {
                listOfNotNull(
                    Row("状態", local.state.label()),
                    Row("固定", if (local.pinned) "固定（保持ルールの対象外）" else "固定していない"),
                    local.downloadedAt?.let { Row("ダウンロード日時", it.toDateTimeText()) },
                )
            },
        )
        val play = Section(
            "再生",
            if (playback == null) {
                listOf(Row("再生位置", "記録なし"))
            } else {
                listOf(
                    Row("再生位置", "${playback.position.toClockText()} / ${e.runtime.toClockText()}"),
                    Row("再生済み", if (playback.played) "再生済み" else "未再生"),
                )
            },
        )
        return listOf(episode, file, play)
    }

    /**
     * 技術的な詳細: ID・記録の生の値・パスなど。手元のファイルが無ければ保存先・失敗回数の行を、再生記録が無ければ更新日時の行を出さない。
     * 放送日は「サーバが返した生の値」ではなく、アプリが日付に丸めて JST の 0 時にした記録の瞬間（タグが無ければ取り込み日時で代用、CONTEXT.md）。
     * `PlaybackState.syncedAt` は出さない（ADR 0007 で再生位置はサーバへ送らない）。
     */
    fun technicalRows(item: EpisodeWithState): List<Row> {
        val e = item.episode
        val local = item.localFile
        val playback = item.playback
        return listOfNotNull(
            Row("サーバ ID", e.serverItemId?.value ?: "なし（手元だけの各回）"),
            Row("ID（アプリ内）", e.id.value.toString()),
            Row("放送日（記録の瞬間、UTC）", e.airedAt.toString()),
            Row("取り込み日時", e.addedAt?.toString() ?: "なし"),
            Row("コンテナ", e.container),
            local?.let { Row("保存先", it.path ?: "なし") },
            // attemptCount は失敗のたびに増える（再試行の上限判定用）。lastAttemptAt は開始のたびに入るので、別の行にする
            local?.let { Row("失敗回数", "${it.attemptCount} 回") },
            local?.lastAttemptAt?.let { Row("最終試行", it.toDateTimeText()) },
            playback?.let { Row("再生記録の更新日時", it.updatedAt.toDateTimeText()) },
        )
    }

    /** 各回一覧の行の状態に対応する語（行は Wi-Fi 待ち・再試行の案内を足し、ダウンロード済みは何も出さない）。 */
    private fun DownloadState.label(): String = when (this) {
        DownloadState.PENDING -> "待機中"
        DownloadState.RUNNING -> "ダウンロード中"
        DownloadState.DONE -> "ダウンロード済み"
        DownloadState.FAILED -> "失敗"
    }
}
