package dev.tseki.kikidame.ui.player

import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.UiText
import kotlin.math.roundToLong

/**
 * 再生画面の左右スワイプによる秒単位のシーク。移動量に比例し、指を離した時点で 1 回だけシークする（#29）。
 * 粒度は [DP_PER_SECOND] だけで決まる（実機で試して調整する）。
 */
object SwipeSeek {
    /** 横に何 dp 動かすと 1 秒ぶん動くか（10dp = 2 秒。実機で試して決めた）。 */
    const val DP_PER_SECOND = 5f
    /** ドラッグの開始位置がこの横幅（画面幅に対する割合、中央寄せ）に入っているときだけ拾う。端は戻るジェスチャや誤操作の緩衝。 */
    const val CENTER_WIDTH_FRACTION = 0.6f
    /** 同じく縦（画面高に対する割合、中央寄せ）。コンテンツ自体が中央にあるので横より広め。 */
    const val CENTER_HEIGHT_FRACTION = 0.7f
    /** 開始位置 ([x], [y]) が中央の領域に入っているか。 */
    fun isInCenterArea(x: Float, y: Float, width: Float, height: Float): Boolean =
        inBand(x, width, CENTER_WIDTH_FRACTION) && inBand(y, height, CENTER_HEIGHT_FRACTION)
    private fun inBand(v: Float, size: Float, fraction: Float): Boolean {
        val half = size * fraction / 2
        return v >= size / 2 - half && v <= size / 2 + half
    }

    /** 横の移動量（dp、右が正）を秒差（ミリ秒）に変換する。 */
    fun deltaMs(offsetDp: Float): Long = (offsetDp / DP_PER_SECOND * 1000).roundToLong()

    /** ドラッグ開始時の位置に差分を足し、0〜尺に丸める。 */
    fun targetMs(startMs: Long, offsetDp: Float, durationMs: Long): Long =
        (startMs + deltaMs(offsetDp)).coerceIn(0L, durationMs.coerceAtLeast(0L))

    /** 「+7 秒」「−12 秒」の形。0 は「±0 秒」。符号付きの数はここで作り、単位（秒）は言語ごとの文言に任せる。 */
    fun deltaText(deltaMs: Long): UiText {
        val seconds = deltaMs / 1000
        val signed = when {
            seconds > 0 -> "+$seconds"
            seconds < 0 -> "−${-seconds}"
            else -> "±0"
        }
        return UiText.Res(R.string.player_swipe_delta, signed)
    }
}
