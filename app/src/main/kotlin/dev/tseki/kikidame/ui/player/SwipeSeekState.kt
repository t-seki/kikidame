package dev.tseki.kikidame.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.tseki.kikidame.domain.EpisodeId

/**
 * 再生画面の左右スワイプによるシーク（#29）の状態。計算は [SwipeSeek]、ジェスチャは [swipeToSeek]、ここは
 * 「ドラッグ中か・開始時の位置・移動量」と、開始・ドラッグ・終了・取り消しだけを持つ。
 * 中身は Compose の snapshot の state だけで、コンポジションの外（JVM のユニットテスト）でも動く。
 *
 * - 尺が分かる（[durationMs] > 0）まで、または中央の領域（[SwipeSeek.isInCenterArea]）の外から始めたドラッグは拾わない
 * - 指を離した時点（[end]）で 1 回だけ [onSeek] を呼ぶ
 * - 回が切り替わったら（[rememberSwipeSeekState] が [cancel] を呼ぶ）、古い回の位置を基準にシークしないよう破棄する
 */
@Stable
class SwipeSeekState {
    /** 今の再生位置。[rememberSwipeSeekState] がコンポジションのたびに入れ直す（開始時にはこの時点の値を読む）。 */
    var positionMs by mutableLongStateOf(0L)

    /** 今の回の尺。0 以下なら尺が分からないとみなして始めない。[rememberSwipeSeekState] が入れ直す。 */
    var durationMs by mutableLongStateOf(0L)

    /** 指を離したときに呼ぶシーク。[rememberSwipeSeekState] が入れ直す。 */
    var onSeek: (Long) -> Unit by mutableStateOf({})

    /** ドラッグを始めた時点の再生位置。 */
    var startMs by mutableLongStateOf(0L)
        private set

    /** 開始からの横の移動量（dp、右が正）。ドラッグ中でなければ null。 */
    private var offsetDp by mutableStateOf<Float?>(null)

    /** ドラッグ中の目標位置（0〜尺に丸めた値）。ドラッグ中でなければ null。 */
    val targetMs: Long?
        get() = offsetDp?.let { SwipeSeek.targetMs(startMs, it, durationMs) }

    /** ドラッグの開始。開始位置 ([x], [y]) と、拾う領域の大きさ ([width], [height]) を渡す。拾ったら true。 */
    fun start(x: Float, y: Float, width: Float, height: Float): Boolean {
        if (durationMs <= 0 || !SwipeSeek.isInCenterArea(x, y, width, height)) return false
        startMs = positionMs
        offsetDp = 0f
        return true
    }

    /** 横の移動量 [dragDp]（dp）を足す。拾っていない（中央の領域の外から始めた・尺が無い・回が切り替わった）なら何もせず false。 */
    fun drag(dragDp: Float): Boolean {
        val current = offsetDp ?: return false
        offsetDp = current + dragDp
        return true
    }

    /** 指を離した。拾っていたら目標位置へ 1 回だけシークする。 */
    fun end() {
        targetMs?.let { onSeek(it) }
        offsetDp = null
    }

    /** ドラッグの取り消し（ジェスチャの取り消し、回の切り替え）。シークしない。 */
    fun cancel() {
        offsetDp = null
    }
}

/**
 * [SwipeSeekState] を作って覚える。[positionMs]・[durationMs]・[onSeek] はコンポジションのたびに入れ直し、
 * [episodeId] が変わったらドラッグを破棄する。状態は回をまたいで 1 つのまま（作り直すと [swipeToSeek] の
 * ジェスチャがドラッグの途中で作り直されるため）。
 */
@Composable
fun rememberSwipeSeekState(episodeId: EpisodeId?, positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit): SwipeSeekState {
    val state = remember { SwipeSeekState() }
    state.positionMs = positionMs
    state.durationMs = durationMs
    state.onSeek = onSeek
    // ドラッグ中に回が切り替わったら（自動遷移）、古い回の位置を基準にシークしないよう破棄する
    LaunchedEffect(episodeId) { state.cancel() }
    return state
}

/**
 * 左右スワイプで [state] を動かす。中央の領域から始めたドラッグだけ拾い、拾ったドラッグだけ消費するので、
 * シークバーの上では Slider が勝つ（自分でドラッグを消費する）。ボタンの上から始めても拾う（タップはタッチスロップ内なので混ざらない）。
 * 中央の領域は、この Modifier を付けた要素の大きさで測る。
 */
fun Modifier.swipeToSeek(state: SwipeSeekState): Modifier = pointerInput(state) {
    detectHorizontalDragGestures(
        onDragStart = { start -> state.start(start.x, start.y, size.width.toFloat(), size.height.toFloat()) },
        onDragEnd = { state.end() },
        onDragCancel = { state.cancel() },
        onHorizontalDrag = { change, dragAmount ->
            if (state.drag(dragAmount.toDp().value)) change.consume()
        },
    )
}
