package dev.tseki.jellyfinradio.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** スリープタイマーの設定（CONTEXT.md）。時間が来るか今の回が終わったら一時停止する。 */
sealed interface SleepTimerSetting {
    /**
     * 残り時間。再生中だけ減り、止まっている間は減らない（トイレに立って一時停止しても消えない）。
     * [runningSince] が null なら止まっていて [remaining] がそのまま残り、そうでなければ
     * [runningSince] から数えている（今の残り = [remaining] − 経過）。
     */
    data class Countdown(val remaining: Duration, val runningSince: Instant? = null) : SleepTimerSetting {
        fun remainingAt(now: Instant): Duration =
            (runningSince?.let { remaining - (now - it) } ?: remaining).coerceAtLeast(Duration.ZERO)
    }

    /** 今の聴いている回が終わったら、次に進まずに一時停止する。⏭ で回を進めたら新しい回の終わりに付いてくる。 */
    data object EndOfEpisode : SleepTimerSetting
}

/**
 * スリープタイマーの置き場。再生画面が [set] で選び、[PlaybackService] の [SleepTimerRunner] が読んで実行し、
 * 進み具合（数え始め・一時停止・発火・解除）も書き戻す。プロセス内で受け渡す点は [NowPlaying] と同じ（ADR 0006）。
 */
@Singleton
class SleepTimer @Inject constructor() {
    private val _setting = MutableStateFlow<SleepTimerSetting?>(null)
    val setting: StateFlow<SleepTimerSetting?> = _setting

    fun set(setting: SleepTimerSetting?) {
        _setting.value = setting
    }

    companion object {
        /** 選べる時間。 */
        val CHOICES: List<Duration> = listOf(15.minutes, 30.minutes, 45.minutes, 60.minutes)
    }
}

/**
 * [SleepTimer] の設定をプレイヤーに適用する。[scope] は [player] のアプリケーションスレッドで動くこと。
 * [setPauseAtEndOfMediaItems] は ExoPlayer の同名の機能（[Player] のインターフェースには無いので注入する）。
 *
 * カウントダウンは `playWhenReady` が true の間だけ進める（`isPlaying` ではなく `playWhenReady` を見るのは、
 * バッファリングやシークの一瞬で止めないため）。解除されるのは、利用者が選び直す・解除する、発火する
 * （カウントダウンは時間が来て止めたとき、回の終わりまでは ExoPlayer が回の終わりで止めたとき =
 * `PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM`）、回が終わり切る（`STATE_ENDED`。もう待つものが無い）、
 * サービスが消える（[detach]）とき。
 */
class SleepTimerRunner(
    private val player: Player,
    private val timer: SleepTimer,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val setPauseAtEndOfMediaItems: (Boolean) -> Unit,
) : Player.Listener {
    private var collector: Job? = null
    private var countdown: Job? = null

    fun attach() {
        player.addListener(this)
        collector = scope.launch { timer.setting.collect(::apply) }
    }

    fun detach() {
        collector?.cancel()
        countdown?.cancel()
        setPauseAtEndOfMediaItems(false)
        player.removeListener(this)
        timer.set(null)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        when (val setting = timer.setting.value) {
            // 回の終わりで止めた = 発火。次の回まで持ち越さない
            SleepTimerSetting.EndOfEpisode ->
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) timer.set(null)
            is SleepTimerSetting.Countdown -> {
                val now = clock.now()
                if (playWhenReady) {
                    if (setting.runningSince == null) timer.set(setting.copy(runningSince = now))
                } else if (setting.runningSince != null) {
                    timer.set(SleepTimerSetting.Countdown(setting.remainingAt(now)))
                }
            }
            null -> Unit
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) timer.set(null)
    }

    /** 設定が変わるたびに、カウントダウンを張り直す／止め、回の終わりで止まる設定をプレイヤーに反映する。 */
    private fun apply(setting: SleepTimerSetting?) {
        countdown?.cancel()
        countdown = null
        setPauseAtEndOfMediaItems(setting is SleepTimerSetting.EndOfEpisode)
        if (setting !is SleepTimerSetting.Countdown) return
        val now = clock.now()
        when {
            // 選んだ時点で再生中なら、その瞬間から数え始める
            setting.runningSince == null && player.playWhenReady -> timer.set(setting.copy(runningSince = now))
            setting.runningSince != null -> countdown = scope.launch {
                val remaining = setting.remainingAt(now)
                if (remaining > Duration.ZERO) delay(remaining)
                player.pause()
                timer.set(null)
            }
        }
    }
}
