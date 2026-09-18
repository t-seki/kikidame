package dev.tseki.jellyfinradio.domain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
/** 再生位置と再生済み。ローカルが正（ADR 0002）。 */
data class PlaybackState(
    val episodeId: EpisodeId,
    val position: Duration,
    val played: Boolean,
    val updatedAt: Instant,
    /** null は未送信（dirty）。 */
    val syncedAt: Instant? = null,
) {
    companion object {
        fun initial(episodeId: EpisodeId, now: Instant): PlaybackState =
            PlaybackState(episodeId, Duration.ZERO, played = false, updatedAt = now)
    }
}
/**
 * 再生済みと再開位置の規則。再生済みフラグと再生位置は互いに触らず、
 * 両者が絡むのは [resumePosition] だけ。
 */
object PlaybackRules {
    /** 末尾からこの範囲に達したら再生済み。尺が短くてもスケールさせない。 */
    val PLAYED_THRESHOLD: Duration = 2.minutes

    /**
     * 位置がこれ未満で未再生なら「聴き始めていない」。⏭ の連打や誤タップで進む時間は数秒で、
     * 再生中 10 秒ごとの定期保存の最初より小さいので、聴き始めた回は必ず記録される（#7）。
     */
    val NOT_STARTED_THRESHOLD: Duration = 5.seconds

    /** 聴き始めていない（位置が [NOT_STARTED_THRESHOLD] 未満で未再生）。 */
    fun isNotStarted(state: PlaybackState): Boolean =
        !state.played && state.position < NOT_STARTED_THRESHOLD

    /**
     * [next] を保存する価値があるか。行が無く聴き始めてもいなければ、情報が無いので書かない
     * （⏭⏮ で通り過ぎただけの回）。既存の行があれば位置 0 でも更新する（先頭に戻した、は情報）。
     */
    fun isWorthRecording(existing: PlaybackState?, next: PlaybackState): Boolean =
        existing != null || !isNotStarted(next)
    /**
     * 再生位置が末尾付近に達したか。位置 0 では判定しない
     * （尺が閾値以下の回は「再生が少しでも進んだら再生済み」になる）。
     */
    fun isNearEnd(position: Duration, runtime: Duration): Boolean =
        position > Duration.ZERO && runtime - position <= PLAYED_THRESHOLD
    /**
     * 再生位置の更新。末尾付近なら再生済みを立てる。一度立った再生済みは
     * シークで戻しても自動では下ろさない。`syncedAt` はローカル変更なので null に戻す。
     */
    fun advance(state: PlaybackState, position: Duration, runtime: Duration, now: Instant): PlaybackState =
        state.copy(
            position = position,
            played = state.played || isNearEnd(position, runtime),
            updatedAt = now,
            syncedAt = null,
        )
    /** 手動の再生済み／未再生切替。位置は変えない。 */
    fun setPlayed(state: PlaybackState, played: Boolean, now: Instant): PlaybackState =
        state.copy(played = played, updatedAt = now, syncedAt = null)
    /**
     * 再生開始時の再開位置。残りが閾値以下なら先頭から、そうでなければ保存位置から。
     * 再生済みフラグは見ない。
     */
    fun resumePosition(position: Duration, runtime: Duration): Duration =
        if (isNearEnd(position, runtime)) Duration.ZERO else position
}
