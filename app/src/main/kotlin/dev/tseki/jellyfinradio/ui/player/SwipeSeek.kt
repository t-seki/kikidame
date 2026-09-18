package dev.tseki.jellyfinradio.ui.player

import kotlin.math.roundToLong

/**
 * 再生画面の左右スワイプによる秒単位のシーク。移動量に比例し、指を離した時点で 1 回だけシークする（#29）。
 * 粒度は [DP_PER_SECOND] だけで決まる（実機で試して調整する）。
 */
object SwipeSeek {
    /** 横に何 dp 動かすと 1 秒ぶん動くか。 */
    const val DP_PER_SECOND = 10f

    /** 横の移動量（dp、右が正）を秒差（ミリ秒）に変換する。 */
    fun deltaMs(offsetDp: Float): Long = (offsetDp / DP_PER_SECOND * 1000).roundToLong()

    /** ドラッグ開始時の位置に差分を足し、0〜尺に丸める。 */
    fun targetMs(startMs: Long, offsetDp: Float, durationMs: Long): Long =
        (startMs + deltaMs(offsetDp)).coerceIn(0L, durationMs.coerceAtLeast(0L))

    /** 「+7 秒」「−12 秒」の形。0 は「±0 秒」。 */
    fun deltaText(deltaMs: Long): String {
        val seconds = deltaMs / 1000
        return when {
            seconds > 0 -> "+$seconds 秒"
            seconds < 0 -> "−${-seconds} 秒"
            else -> "±0 秒"
        }
    }
}
