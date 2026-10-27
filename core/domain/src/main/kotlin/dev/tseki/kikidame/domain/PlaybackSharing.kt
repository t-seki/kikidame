package dev.tseki.kikidame.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 取得元に置かれた、1 つの各回の再生位置と再生済み（**再生の共有**、ADR 0013）。Jellyfin のブックのライブラリの UserData の
 * `PlaybackPositionTicks`・`Played`・`LastPlayedDate`。[lastPlayedAt] は、その値を最後に書いた（再生した）時刻で、書かれていなければ null。
 */
data class SharedPlayState(val position: Duration, val played: Boolean, val lastPlayedAt: Instant?) {
    /** [lastPlayedAt] をミリ秒にそろえた値（手元の記録はミリ秒。サーバの端数を切り捨てる）。 */
    fun toMillis(): SharedPlayState = copy(lastPlayedAt = lastPlayedAt?.let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) })

    /** 位置と再生済みが同じか（[lastPlayedAt] は見ない）。 */
    fun sameProgress(other: SharedPlayState): Boolean = position == other.position && played == other.played

    companion object {
        /** 誰も再生していない各回の値（位置 0・未再生・時刻なし）。 */
        val EMPTY = SharedPlayState(Duration.ZERO, played = false, lastPlayedAt = null)

        /** 手元の状態を送るときの値。`LastPlayedDate` には手元の更新時刻を入れる（ADR 0013 の決定 3）。 */
        fun of(state: PlaybackState): SharedPlayState = SharedPlayState(state.position, state.played, state.updatedAt)
    }
}

/**
 * 1 つの各回についての、再生の共有の手元の記録（ADR 0013 の決定 4・5）。
 *
 * @property local 手元の再生位置と再生済み。行が無ければ null
 * @property pending 未送信（手元で位置か再生済みが変わってから、まだ取得元に送れていない）
 * @property lastSeen 最後に見た取得元の値。一度も見ていなければ null
 */
data class SharedPlaybackRecord(
    val local: PlaybackState?,
    val pending: Boolean,
    val lastSeen: SharedPlayState?,
)

/**
 * 再生の共有の衝突の規則（ADR 0013 の決定 4）。純粋関数で、I/O はしない。
 *
 * - 取得元が変わった: 取得元の値（位置・再生済み・`LastPlayedDate`）が、最後に見た値と違う。最後に見た値が無ければ、
 *   誰も再生していない値（[SharedPlayState.EMPTY]）を見たとみなす（初めて見る回は、取得元に値があれば「変わった」）
 * - 手元が変わった: 未送信の印がある（[SharedPlaybackRecord.pending]）
 * - 取得元だけが変わったら取得元の値を採り、手元だけが変わったら手元を送る。両方なら、手元の更新時刻と取得元の
 *   `LastPlayedDate` の新しい方（取得元の時刻が無ければ手元。同じ時刻なら手元）
 * - `LastPlayedDate` はミリ秒にそろえてから比べる（手元の記録はミリ秒。[SharedPlayState.toMillis]）
 * - 位置と再生済みは 1 組で扱う。手元で未再生に戻した回も、手元の変化として同じ規則に乗る
 */
object PlaybackSharingRules {
    sealed interface Decision {
        /** どちらも変わっていない。何もしない。 */
        data object Keep : Decision

        /** 取得元の値を手元に入れる。 */
        data class TakeShared(val shared: SharedPlayState) : Decision

        /** 手元の値を取得元に送る。 */
        data class SendLocal(val state: SharedPlayState) : Decision
    }

    fun decide(record: SharedPlaybackRecord, server: SharedPlayState): Decision {
        // 手元は時刻をミリ秒で持つ（Room）。サーバの端数で「変わった」と誤らないよう、比べる前にそろえる
        val shared = server.toMillis()
        val seen = (record.lastSeen ?: SharedPlayState.EMPTY).toMillis()
        val sharedChanged = shared != seen
        val local = record.local?.takeIf { record.pending }
        return when {
            local == null -> if (sharedChanged) Decision.TakeShared(shared) else Decision.Keep
            !sharedChanged -> Decision.SendLocal(SharedPlayState.of(local))
            shared.lastPlayedAt != null && shared.lastPlayedAt > local.updatedAt -> Decision.TakeShared(shared)
            else -> Decision.SendLocal(SharedPlayState.of(local))
        }
    }

    /**
     * 取得元の値を採るときの手元の状態。更新時刻は取得元の `LastPlayedDate`（無ければ [now]）。
     * 手元に行が無く、採っても聴き始めていない（[PlaybackRules.isWorthRecording]）なら null（行を作らない）。
     */
    fun taken(episodeId: EpisodeId, existing: PlaybackState?, shared: SharedPlayState, now: Instant): PlaybackState? {
        val next = PlaybackState(episodeId, shared.position, shared.played, shared.lastPlayedAt ?: now)
        return next.takeIf { PlaybackRules.isWorthRecording(existing, it) }
    }

    /** ▶ の直前の読み込みの待ち時間の上限（ADR 0013 の決定 5。目安 2 秒。#352 の決定 7）。超えたら手元の値で鳴らす。 */
    val READ_BEFORE_PLAY_TIMEOUT: Duration = 2.seconds
}

/**
 * 再生の共有の最後の送受信（設定の「最終同期」の近くの 1 行。ADR 0013 の決定 8）。今のライブラリのものだけ。
 *
 * @property lastSucceededAt 最後に送受信できた時刻（同期での突き合わせと、一時停止などの後の送信）。無ければ null
 * @property failedAt 最後に送れなかった時刻。[lastSucceededAt] より新しいときだけ失敗を出す
 * @property failure 最後に送れなかった理由（同期の失敗と同じ形。文言も同じものを使う）
 */
data class PlaybackShareStatus(
    val lastSucceededAt: Instant?,
    val failedAt: Instant?,
    val failure: SyncOutcome.Failure?,
) {
    /** 最後の試みが失敗なら、その時刻と理由。 */
    val currentFailure: Pair<Instant, SyncOutcome.Failure>?
        get() {
            val at = failedAt ?: return null
            val reason = failure ?: return null
            if (lastSucceededAt != null && lastSucceededAt >= at) return null
            return at to reason
        }
}
