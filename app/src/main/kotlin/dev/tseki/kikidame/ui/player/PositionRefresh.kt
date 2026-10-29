package dev.tseki.kikidame.ui.player

import kotlin.math.ceil

/** 止まっている間と、倍速が来ないはずの値のときに位置を読む間隔（ms）。 */
internal const val IDLE_POSITION_REFRESH_MS = 500L

/** 秒の境目・チャプターの開始の直後に読むための余裕（ms）。プレイヤーの位置のずれは測っていない（未確認）。 */
internal const val POSITION_REFRESH_MARGIN_MS = 30L

/**
 * 次に位置を読むまでの待ち時間（ms）。
 * 再生中は、次の秒の境目と次のチャプターの開始のうち早い方（壁時計の時間なので倍速で割る）に [POSITION_REFRESH_MARGIN_MS] を足す（ms 未満は切り上げ）。
 * 止まっている間、倍速が 0 以下のときは [IDLE_POSITION_REFRESH_MS]。再生中は必ず [POSITION_REFRESH_MARGIN_MS] 以上になる。
 * [chapters] は [chaptersOf] が返す、開始の順のもの。開始が [positionMs] より後で最も早いものだけを見る。
 */
internal fun nextPositionRefreshDelayMs(
    isPlaying: Boolean,
    positionMs: Long,
    speed: Float,
    chapters: List<EpisodeChapter>,
): Long {
    if (!isPlaying || speed <= 0f) return IDLE_POSITION_REFRESH_MS
    val toSecondMs = 1000L - positionMs.mod(1000L)
    val toChapterMs = chapters.firstOrNull { it.startMs > positionMs }?.let { it.startMs - positionMs }
    val aheadMs = minOf(toSecondMs, toChapterMs ?: Long.MAX_VALUE)
    return ceil(aheadMs.toDouble() / speed.toDouble()).toLong() + POSITION_REFRESH_MARGIN_MS
}
