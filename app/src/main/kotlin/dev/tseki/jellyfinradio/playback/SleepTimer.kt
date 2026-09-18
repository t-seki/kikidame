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
    /** この時刻に一時停止する。 */
    data class At(val endsAt: Instant) : SleepTimerSetting

    /** 今の聴いている回が終わったら、次に進まずに一時停止する。⏭ で回を進めたら新しい回の終わりに付いてくる。 */
    data object EndOfEpisode : SleepTimerSetting
}

/**
 * スリープタイマーの置き場。再生画面が [set] で書き、[PlaybackService] の [SleepTimerRunner] が読んで実行する
 * （[NowPlaying] と同じくプロセス内で受け渡す、ADR 0006）。再生が止まると解除される。
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
 * 解除の条件: 利用者が選び直す・解除する、または再生が止まる（`playWhenReady` が false になる。
 * 一時停止・タイマー自身の発火・回の終わりでの停止・エラー）。`isPlaying` ではなく `playWhenReady` を見るのは、
 * バッファリングやシークの一瞬で解除しないため。
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
        if (!playWhenReady) timer.set(null)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) timer.set(null)
    }

    private fun apply(setting: SleepTimerSetting?) {
        countdown?.cancel()
        countdown = null
        setPauseAtEndOfMediaItems(setting is SleepTimerSetting.EndOfEpisode)
        if (setting is SleepTimerSetting.At) {
            countdown = scope.launch {
                val remaining = setting.endsAt - clock.now()
                if (remaining > Duration.ZERO) delay(remaining)
                player.pause()
                timer.set(null)
            }
        }
    }
}
