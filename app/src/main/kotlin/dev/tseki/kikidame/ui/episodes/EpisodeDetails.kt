package dev.tseki.kikidame.ui.episodes

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.toPublishedDateText
import dev.tseki.kikidame.ui.toPerformersText
import dev.tseki.kikidame.ui.toClockText
import dev.tseki.kikidame.ui.toDateTimeText
import dev.tseki.kikidame.ui.toSizeText

/**
 * 各回の詳細画面（#43）に並べる値。Room にあるものを整形するだけで、サーバへは問い合わせない。
 * 利用者向けの群と、デバッグ向けの「技術的な詳細」に分ける（後者は折り畳み、長押しでコピー）。
 * ラベルと定型の値は [UiText]（ADR 0009）。サーバから来た値・日付・尺は [UiText.Plain]。
 */
object EpisodeDetails {
    data class Row(val label: UiText, val value: UiText) {
        constructor(label: Int, value: UiText) : this(UiText.Res(label), value)
        constructor(label: Int, value: String) : this(UiText.Res(label), UiText.Plain(value))
        constructor(label: Int, value: Int) : this(UiText.Res(label), UiText.Res(value))
    }
    data class Section(val title: UiText, val rows: List<Row>) {
        constructor(title: Int, rows: List<Row>) : this(UiText.Res(title), rows)
    }

    /**
     * 利用者向け: 各回・手元・再生の 3 群。手元に無い／再生記録が無いときはその群を 1 行に畳む。サイズが記録されていなければ「不明」。
     * 出演者と配信元（#70）は「全部の値を並べる」画面なので無くても行を出す（出演者は「なし」、配信元が無い番組は「不明」）。
     */
    fun sections(item: EpisodeWithState, program: Program): List<Section> {
        val e = item.episode
        val local = item.localFile
        val playback = item.playback
        val episode = Section(
            R.string.episode_details_section_episode,
            listOf(
                Row(R.string.episode_details_published, e.publishedAt.toPublishedDateText()),
                Row(R.string.episode_details_performers, e.performers.toPerformersText() ?: UiText.Res(R.string.common_none)),
                Row(R.string.episode_details_publisher, program.publisherName?.let(UiText::Plain) ?: UiText.Res(R.string.common_unknown)),
                Row(R.string.episode_details_runtime, e.runtime.toClockText()),
                Row(R.string.episode_details_size, if (e.sizeBytes > 0) UiText.Plain(e.sizeBytes.toSizeText()) else UiText.Res(R.string.common_unknown)),
            ),
        )
        val file = Section(
            R.string.episode_details_section_device,
            if (local == null) {
                listOf(Row(R.string.episode_details_state, R.string.episode_details_not_on_device))
            } else {
                listOfNotNull(
                    Row(R.string.episode_details_state, local.state.label()),
                    Row(R.string.episode_details_pin, if (local.pinned) R.string.episode_details_pinned else R.string.episode_details_not_pinned),
                    local.downloadedAt?.let { Row(R.string.episode_details_downloaded_at, it.toDateTimeText()) },
                )
            },
        )
        val play = Section(
            R.string.episode_details_section_playback,
            if (playback == null) {
                listOf(Row(R.string.episode_details_position, R.string.episode_details_no_record))
            } else {
                listOf(
                    Row(R.string.episode_details_position, "${playback.position.toClockText()} / ${e.runtime.toClockText()}"),
                    Row(R.string.common_played, if (playback.played) R.string.common_played else R.string.common_unplayed),
                )
            },
        )
        return listOf(episode, file, play)
    }

    /**
     * 技術的な詳細: ID・記録の生の値・パスなど。手元のファイルが無ければ保存先・失敗回数の行を、再生記録が無ければ更新日時の行を出さない。
     * 公開日は「サーバが返した生の値」ではなく、アプリが日付に丸めて JST の 0 時にした記録の瞬間（タグが無ければ取り込み日時で代用、CONTEXT.md）。
     * `PlaybackState.syncedAt` は出さない（ADR 0007 で再生位置はサーバへ送らない）。
     */
    fun technicalRows(item: EpisodeWithState): List<Row> {
        val e = item.episode
        val local = item.localFile
        val playback = item.playback
        return listOfNotNull(
            Row(R.string.episode_details_server_id, e.serverItemId?.value?.let(UiText::Plain) ?: UiText.Res(R.string.episode_details_server_id_none)),
            Row(R.string.episode_details_app_id, e.id.value.toString()),
            Row(R.string.episode_details_published_raw, e.publishedAt.toString()),
            Row(R.string.episode_details_added_at, e.addedAt?.toString()?.let(UiText::Plain) ?: UiText.Res(R.string.common_none)),
            Row(R.string.episode_details_container, e.container),
            local?.let { Row(R.string.episode_details_path, it.path?.let(UiText::Plain) ?: UiText.Res(R.string.common_none)) },
            // attemptCount は失敗のたびに増える（再試行の上限判定用）。lastAttemptAt は開始のたびに入るので、別の行にする
            local?.let { Row(R.string.episode_details_attempts, UiText.Plural(R.plurals.episode_details_attempts_value, it.attemptCount)) },
            local?.lastAttemptAt?.let { Row(R.string.episode_details_last_attempt, it.toDateTimeText()) },
            playback?.let { Row(R.string.episode_details_playback_updated_at, it.updatedAt.toDateTimeText()) },
        )
    }

    /** 各回一覧の行の状態に対応する語（行は Wi-Fi 待ち・再試行の案内を足し、ダウンロード済みは何も出さない）。 */
    private fun DownloadState.label(): Int = when (this) {
        DownloadState.PENDING -> R.string.common_download_pending
        DownloadState.RUNNING -> R.string.common_download_running
        DownloadState.DONE -> R.string.common_download_done
        DownloadState.FAILED -> R.string.common_download_failed
    }
}
