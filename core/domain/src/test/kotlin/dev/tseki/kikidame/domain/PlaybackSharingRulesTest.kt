package dev.tseki.kikidame.domain

import dev.tseki.kikidame.domain.PlaybackSharingRules.Decision
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 再生の共有の衝突の規則（ADR 0010 の決定 4）。 */
class PlaybackSharingRulesTest {
    private val episode = EpisodeId(1)
    private val t0 = Instant.parse("2026-10-27T00:00:00Z")
    private val t1 = Instant.parse("2026-10-27T01:00:00Z")
    private val t2 = Instant.parse("2026-10-27T02:00:00Z")

    private fun local(position: Duration, played: Boolean = false, at: Instant) = PlaybackState(episode, position, played, at)
    private fun shared(position: Duration, played: Boolean = false, at: Instant?) = SharedPlayState(position, played, at)

    @Test
    fun `nothing changed keeps both`() {
        val seen = shared(10.minutes, at = t0)
        val record = SharedPlaybackRecord(local(10.minutes, at = t0), pending = false, lastSeen = seen)
        assertEquals(Decision.Keep, PlaybackSharingRules.decide(record, seen))
    }

    /** サーバの `LastPlayedDate` のミリ秒未満の端数は、ミリ秒で持つ最後に見た値と比べる前に切り捨てる。 */
    @Test
    fun `a sub-millisecond fraction of the server time is not a change`() {
        val seen = shared(10.minutes, at = t0)
        val server = shared(10.minutes, at = t0 + 300.microseconds)
        assertEquals(Decision.Keep, PlaybackSharingRules.decide(SharedPlaybackRecord(local(10.minutes, at = t0), pending = false, lastSeen = seen), server))
        // 手元だけが変わったとみなされ、送る（両方変わったとはみなさない）
        val record = SharedPlaybackRecord(local(15.minutes, at = t0 - 1.minutes), pending = true, lastSeen = seen)
        assertEquals(Decision.SendLocal(shared(15.minutes, at = t0 - 1.minutes)), PlaybackSharingRules.decide(record, server))
        assertEquals(t0, server.toMillis().lastPlayedAt)
    }

    @Test
    fun `only the server changed takes the server value`() {
        val record = SharedPlaybackRecord(local(10.minutes, at = t0), pending = false, lastSeen = shared(10.minutes, at = t0))
        val server = shared(25.minutes, at = t1)
        assertEquals(Decision.TakeShared(server), PlaybackSharingRules.decide(record, server))
    }

    @Test
    fun `only the local state changed sends it with its update time`() {
        val seen = shared(10.minutes, at = t0)
        val record = SharedPlaybackRecord(local(15.minutes, at = t1), pending = true, lastSeen = seen)
        assertEquals(Decision.SendLocal(shared(15.minutes, at = t1)), PlaybackSharingRules.decide(record, seen))
    }

    @Test
    fun `both changed and the server is newer takes the server value`() {
        val record = SharedPlaybackRecord(local(15.minutes, at = t1), pending = true, lastSeen = shared(10.minutes, at = t0))
        val server = shared(30.minutes, played = true, at = t2)
        assertEquals(Decision.TakeShared(server), PlaybackSharingRules.decide(record, server))
    }

    @Test
    fun `both changed and the local state is newer sends it`() {
        val record = SharedPlaybackRecord(local(15.minutes, at = t2), pending = true, lastSeen = shared(10.minutes, at = t0))
        val server = shared(30.minutes, at = t1)
        assertEquals(Decision.SendLocal(shared(15.minutes, at = t2)), PlaybackSharingRules.decide(record, server))
    }

    @Test
    fun `both changed at the same time prefers the local state`() {
        val record = SharedPlaybackRecord(local(15.minutes, at = t1), pending = true, lastSeen = shared(10.minutes, at = t0))
        assertEquals(Decision.SendLocal(shared(15.minutes, at = t1)), PlaybackSharingRules.decide(record, shared(30.minutes, at = t1)))
    }

    @Test
    fun `both changed and the server has no time sends the local state`() {
        val record = SharedPlaybackRecord(local(15.minutes, at = t1), pending = true, lastSeen = shared(10.minutes, at = t0))
        assertEquals(Decision.SendLocal(shared(15.minutes, at = t1)), PlaybackSharingRules.decide(record, shared(Duration.ZERO, at = null)))
    }

    /** 手元で未再生に戻した回: 位置と再生済みは 1 組で、未再生に戻したことも手元の変化（新しい方として扱われる）。 */
    @Test
    fun `marking unplayed locally is a local change sent with the position`() {
        val seen = shared(29.minutes, played = true, at = t0)
        val record = SharedPlaybackRecord(local(29.minutes, played = false, at = t1), pending = true, lastSeen = seen)
        assertEquals(Decision.SendLocal(shared(29.minutes, played = false, at = t1)), PlaybackSharingRules.decide(record, seen))
        // サーバでも誰かが変えていたら、時刻の新しい方。手元で未再生に戻した方が新しければ、未再生が勝つ
        val elsewhere = shared(5.minutes, played = true, at = t0 + 1.minutes)
        assertEquals(
            Decision.SendLocal(shared(29.minutes, played = false, at = t1)),
            PlaybackSharingRules.decide(record, elsewhere),
        )
    }

    /** 初めて見る回: 最後に見た値が無ければ、誰も再生していない値を見たとみなす。 */
    @Test
    fun `first sight of an empty server value sends the local state`() {
        // 音楽のライブラリからブックのライブラリへ移った直後（サーバは空、手元は聴いた位置）。手元が送られる（ADR 0010 の決定 7）
        val record = SharedPlaybackRecord(local(12.minutes, at = t1), pending = true, lastSeen = null)
        assertEquals(Decision.SendLocal(shared(12.minutes, at = t1)), PlaybackSharingRules.decide(record, SharedPlayState.EMPTY))
    }

    @Test
    fun `first sight of a value written elsewhere takes it`() {
        // Web でだけ聴いた回（手元に行が無い）
        val server = shared(20.minutes, at = t1)
        assertEquals(Decision.TakeShared(server), PlaybackSharingRules.decide(SharedPlaybackRecord(null, pending = false, lastSeen = null), server))
        // 手元に行はあるが未送信でない（送り終えた後に、ほかで聴かれた）
        val sent = SharedPlaybackRecord(local(3.minutes, at = t0), pending = false, lastSeen = shared(3.minutes, at = t0))
        assertEquals(Decision.TakeShared(server), PlaybackSharingRules.decide(sent, server))
    }

    @Test
    fun `first sight of an empty server value without local changes keeps`() {
        assertEquals(Decision.Keep, PlaybackSharingRules.decide(SharedPlaybackRecord(null, pending = false, lastSeen = null), SharedPlayState.EMPTY))
    }

    @Test
    fun `taking a value uses its time and skips rows not worth recording`() {
        assertEquals(local(20.minutes, at = t1), PlaybackSharingRules.taken(episode, null, shared(20.minutes, at = t1), now = t2))
        assertEquals(local(20.minutes, at = t2), PlaybackSharingRules.taken(episode, null, shared(20.minutes, at = null), now = t2))
        assertNull(PlaybackSharingRules.taken(episode, null, shared(Duration.ZERO, at = t1), now = t2))
        // 行があれば位置 0 でも書く（先頭に戻した、は情報）
        assertEquals(
            local(Duration.ZERO, at = t1),
            PlaybackSharingRules.taken(episode, local(5.minutes, at = t0), shared(Duration.ZERO, at = t1), now = t2),
        )
    }

    @Test
    fun `status shows a failure only when it is newer than the last success`() {
        assertEquals(t2 to SyncOutcome.Unreachable, PlaybackShareStatus(t1, t2, SyncOutcome.Unreachable).currentFailure)
        assertNull(PlaybackShareStatus(t2, t1, SyncOutcome.Unreachable).currentFailure)
        assertNull(PlaybackShareStatus(t1, null, null).currentFailure)
    }
}
