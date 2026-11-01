package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.db.PlaybackStateEntity
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SharedPlayState
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.SyncOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 再生の共有（ADR 0010）: 同期で、ブックのライブラリの一覧と一緒に読んだ UserData を手元と突き合わせる。
 * 一時停止などの後の送信（[PlaybackSharing.sendPending]）と、▶ の直前の読み込み（[PlaybackSharing.refreshBeforePlay]）も見る。
 */
@RunWith(AndroidJUnit4::class)
class RoomPlaybackSharingTest : JellyfinRefreshTestBase() {
    private val program = SourceProgram(SourceItemId("TBSラジオ/番組 A"), "番組 A", "TBSラジオ")
    private val runtime = 60.minutes

    private fun se(id: String, day: String, shared: SharedPlayState? = SharedPlayState.EMPTY) =
        sourceEpisode(id, title = day, published = day, program = program.sourceId.value, runtime = runtime).copy(shared = shared)

    /** 取得元の 3 回。[shared] は各回 ID → サーバの UserData（無い回は空）。 */
    private fun snapshot(shared: Map<String, SharedPlayState> = emptyMap()) = SourceSnapshot(
        listOf(program),
        listOf("e1" to "2026-09-01", "e2" to "2026-09-02", "e3" to "2026-09-03").map { (id, day) -> se(id, day, shared[id] ?: SharedPlayState.EMPTY) },
    )

    private suspend fun selectBooks() {
        session.signIn("jellyfin.lab.example", "alice", "secret")
        session.selectLibrary(LibraryView(SourceItemId("lib-books"), "Radio (books)", "books", LibraryKind.BOOKS))
    }

    private suspend fun episode(sourceId: String): EpisodeWithState =
        library.observePrograms().first().flatMap { library.observeEpisodes(it.program.id).first() }
            .first { it.episode.sourceItemId?.value == sourceId }

    private suspend fun id(sourceId: String): EpisodeId = episode(sourceId).episode.id

    private suspend fun row(sourceId: String) = db.playbackStateDao().findByEpisode(id(sourceId).value)

    private val t1 = Instant.parse("2026-09-16T01:00:00Z")
    private val t2 = Instant.parse("2026-09-16T02:00:00Z")

    @Test
    fun syncTakesWhatWasPlayedElsewhere() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()

        // Web で e1 を 20 分まで、e2 を聴き終えた
        gateway.snapshot = snapshot(
            mapOf("e1" to SharedPlayState(20.minutes, false, t1), "e2" to SharedPlayState(Duration.ZERO, true, t1)),
        )
        repo.refresh()

        assertEquals(20.minutes, episode("e1").playback?.position)
        assertEquals(false, episode("e1").playback?.played)
        assertEquals(t1, episode("e1").playback?.updatedAt, "the time is the server's LastPlayedDate")
        assertEquals(true, episode("e2").playback?.played)
        assertNull(episode("e3").playback, "no row for an episode nobody played")
        assertFalse(row("e1")!!.sharePending, "taken values are not sent back")
        assertEquals(emptyList(), gateway.writtenPlayStates)
    }

    @Test
    fun syncSendsWhatWasPlayedHereAndRemembersTheServerValue() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        assertTrue(row("e1")!!.sharePending, "a local change is pending")

        repo.refresh()

        assertEquals(listOf(SourceItemId("e1") to SharedPlayState(3.minutes, false, t1)), gateway.writtenPlayStates)
        val sent = row("e1")!!
        assertFalse(sent.sharePending)
        assertEquals(3.minutes, sent.sharedPositionTicks?.let { dev.tseki.kikidame.domain.Ticks.toDuration(it) })
        assertEquals(t1, sent.sharedLastPlayedAt)

        // 次の同期ではサーバの値（送った値）が最後に見た値と同じなので、何もしない
        gateway.snapshot = snapshot(mapOf("e1" to SharedPlayState(3.minutes, false, t1)))
        repo.refresh()
        assertEquals(1, gateway.writtenPlayStates.size)
        assertEquals(3.minutes, episode("e1").playback?.position)
        assertNull(shareStatus.status.first()?.currentFailure)
        assertNotNull(shareStatus.status.first()?.lastSucceededAt)
    }

    @Test
    fun bothChangedTakesTheNewerOne() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        playback.update(id("e2")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }

        // e1 は Web の方が新しく、e2 は手元の方が新しい
        gateway.playStateFailure = null
        gateway.snapshot = snapshot(
            mapOf(
                "e1" to SharedPlayState(40.minutes, false, t2),
                "e2" to SharedPlayState(40.minutes, false, t1 - 1.minutes),
            ),
        )
        repo.refresh()

        assertEquals(40.minutes, episode("e1").playback?.position)
        assertEquals(3.minutes, episode("e2").playback?.position)
        assertEquals(listOf(SourceItemId("e2") to SharedPlayState(3.minutes, false, t1)), gateway.writtenPlayStates)
    }

    @Test
    fun theEpisodeBeingListenedToStaysLocal() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }

        gateway.snapshot = snapshot(mapOf("e1" to SharedPlayState(40.minutes, false, t2)))
        val listening = id("e1")
        repo.refresh(excluded = { setOf(listening) })

        assertEquals(3.minutes, episode("e1").playback?.position)
        assertTrue(row("e1")!!.sharePending)
        assertEquals(emptyList(), gateway.writtenPlayStates)
    }

    @Test
    fun unsentChangesAreSentOnTheNextSyncAfterFailing() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        gateway.playStateFailure = SourceException.Unreachable()

        repo.refresh()

        assertTrue(row("e1")!!.sharePending, "still pending after failing to send")
        assertEquals(t1 to SyncOutcome.Unreachable, shareStatus.status.first()?.currentFailure)

        gateway.playStateFailure = null
        now = t2
        repo.refresh()

        assertFalse(row("e1")!!.sharePending)
        assertEquals(SourceItemId("e1") to SharedPlayState(3.minutes, false, t1), gateway.writtenPlayStates.last())
        assertNull(shareStatus.status.first()?.currentFailure)
    }

    @Test
    fun episodesPlayedElsewhereAreDeletedAfterPlayed() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        library.updateSync(episode("e1").episode.programId, syncEnabled = true, RetentionRule(keepLatest = null, deleteAfterPlayed = true))
        val file = markDoneAt(id("e3").value, File(directory.root, "e3.m4a"), pinned = false)

        gateway.snapshot = snapshot(mapOf("e3" to SharedPlayState(Duration.ZERO, true, t1)))
        val result = repo.refresh()

        assertEquals(true, episode("e3").playback?.played)
        assertEquals(1, result.deleted)
        assertFalse(file.exists())
        assertNull(episode("e3").localFile)
    }

    @Test
    fun syncingOneProgramAlsoShares() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()

        gateway.snapshot = snapshot(mapOf("e1" to SharedPlayState(20.minutes, false, t1)))
        repo.syncProgram(episode("e1").episode.programId, excluded = emptySet())

        assertEquals(20.minutes, episode("e1").playback?.position)
    }

    /** 音楽のライブラリでは、一覧に値があっても読みも書きもしない（ADR 0010 の決定 1）。 */
    @Test
    fun theMusicLibraryNeverShares() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot().copy(programs = listOf(program))
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }

        gateway.snapshot = snapshot(mapOf("e1" to SharedPlayState(40.minutes, false, t2)))
        repo.refresh()

        assertEquals(3.minutes, episode("e1").playback?.position)
        assertEquals(emptyList(), gateway.writtenPlayStates)
        assertEquals(emptyList(), gateway.readPlayStates)
        assertFalse(sharing.sendPending(sharing.observePending().first(), emptySet()))
        assertNull(sharing.refreshBeforePlay(id("e1")))
        assertEquals(emptyList(), gateway.writtenPlayStates)
        assertEquals(emptyList(), gateway.readPlayStates)
        assertNull(shareStatus.status.first())
    }

    /**
     * 共有フォルダでも読みも書きもしない（ADR 0010 の決定 1）。共有フォルダの一覧は UserData を持たないが、ここでは持たせても
     * 接続の種類で外れることを見る。
     */
    @Test
    fun aSharedFolderNeverShares() = runTest {
        store.saveSmb(SmbConnection("nas.local", "recordings", "", "alice", "pw"))
        var listing = snapshot()
        val folder = object : SourceGateway {
            override suspend fun fetchAll(listener: ScanListener) = listing
            override suspend fun fetchProgramEpisodes(programId: SourceItemId) = listing.episodes.filter { it.programSourceId == programId }
            override suspend fun fetchProgram(programId: SourceItemId) = listing.programs.firstOrNull { it.sourceId == programId }
            override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream = throw UnsupportedOperationException()
            override suspend fun fetchFileSize(episodeId: SourceItemId): Long? = null
        }
        val folderRepo = refreshRepository(folder)
        folderRepo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }

        listing = snapshot(mapOf("e1" to SharedPlayState(40.minutes, false, t2)))
        folderRepo.refresh()

        assertEquals(3.minutes, episode("e1").playback?.position)
        assertFalse(sharing.sendPending(sharing.observePending().first(), emptySet()))
        assertNull(sharing.refreshBeforePlay(id("e1")))
        assertEquals(emptyList(), gateway.writtenPlayStates)
        assertEquals(emptyList(), gateway.readPlayStates)
        assertNull(shareStatus.status.first())
    }

    /** 音楽からブックへ移ったとき、結び直した回の手元の状態は未送信になり、新しい ID に書かれる（ADR 0010 の決定 7）。 */
    @Test
    fun relinkedEpisodesAreSentToTheirNewIds() = runTest {
        signInAndSelect()
        val music = SourceSnapshot(
            listOf(SourceProgram(SourceItemId("music-album"), program.name, program.publisherName)),
            listOf(
                sourceEpisode("audio-1", "2026-09-01", "2026-09-01", program = "music-album", runtime = runtime),
                sourceEpisode("audio-2", "2026-09-02", "2026-09-02", program = "music-album", runtime = runtime),
            ),
        )
        gateway.snapshot = music
        repo.refresh()
        now = t1
        playback.update(id("audio-1")) { PlaybackRules.advance(it, 12.minutes, runtime, now) }
        playback.update(id("audio-2")) { PlaybackRules.setPlayed(it, true, now) }
        // 音楽のライブラリの間に送ったことにしておく（印を下ろし、前の ID の値を見たことにする）
        db.playbackStateDao().markSent(id("audio-1").value, 0, false, null, sentPosition = 0, sentPlayed = false, sentUpdatedAt = t1)

        session.selectLibrary(LibraryView(SourceItemId("lib-books"), "Radio (books)", "books", LibraryKind.BOOKS))
        gateway.snapshot = SourceSnapshot(
            listOf(program),
            listOf(se("book-1", "2026-09-01"), se("book-2", "2026-09-02")),
        )
        repo.refresh()

        assertEquals(
            setOf(SourceItemId("book-1") to SharedPlayState(12.minutes, false, t1), SourceItemId("book-2") to SharedPlayState(Duration.ZERO, true, t1)),
            gateway.writtenPlayStates.toSet(),
        )
        assertFalse(row("book-1")!!.sharePending)
    }

    /** v10 に上げる前からある行（未送信で、最後に見た値なし）は、ブックのライブラリの最初の同期で送られる。 */
    @Test
    fun rowsFromBeforeTheUpgradeAreSentOnTheFirstSync() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        db.playbackStateDao().upsert(
            PlaybackStateEntity(id("e1").value, dev.tseki.kikidame.domain.Ticks.fromDuration(8.minutes), played = false, updatedAt = t1, sharePending = true),
        )

        repo.refresh()

        assertEquals(listOf(SourceItemId("e1") to SharedPlayState(8.minutes, false, t1)), gateway.writtenPlayStates)
        assertFalse(row("e1")!!.sharePending)
    }

    /**
     * 一時停止などの後の送信も、送る前に取得元の値を読んで衝突の規則を通す（ADR 0010 の決定 4）。
     * v10 に上げた直後（未送信・最後に見た値なし）に、ほかで聴き進めた値の方が新しければ、手元の古い値で上書きしない。
     */
    @Test
    fun sendPendingDoesNotOverwriteANewerServerValueAfterTheUpgrade() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        db.playbackStateDao().upsert(
            PlaybackStateEntity(id("e1").value, dev.tseki.kikidame.domain.Ticks.fromDuration(8.minutes), played = false, updatedAt = t1, sharePending = true),
        )
        gateway.playStates[SourceItemId("e1")] = SharedPlayState(30.minutes, false, t2)

        assertTrue(sharing.sendPending(sharing.observePending().first(), emptySet()))

        assertEquals(emptyList(), gateway.writtenPlayStates)
        assertEquals(30.minutes, episode("e1").playback?.position)
        assertFalse(row("e1")!!.sharePending)
    }

    /** 手元の方が新しければ、v10 に上げた直後でも送る。 */
    @Test
    fun sendPendingSendsALocalValueNewerThanTheServer() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        db.playbackStateDao().upsert(
            PlaybackStateEntity(id("e1").value, dev.tseki.kikidame.domain.Ticks.fromDuration(8.minutes), played = false, updatedAt = t2, sharePending = true),
        )
        gateway.playStates[SourceItemId("e1")] = SharedPlayState(30.minutes, false, t1)

        sharing.sendPending(sharing.observePending().first(), emptySet())

        assertEquals(listOf(SourceItemId("e1") to SharedPlayState(8.minutes, false, t2)), gateway.writtenPlayStates)
        assertFalse(row("e1")!!.sharePending)
    }

    /** 結び直しの直後も同じ。前の ID で見た値は忘れているので、取得元の値が新しければそれを採る。 */
    @Test
    fun sendPendingAfterRelinkTakesANewerServerValue() = runTest {
        signInAndSelect()
        gateway.snapshot = SourceSnapshot(
            listOf(SourceProgram(SourceItemId("music-album"), program.name, program.publisherName)),
            listOf(sourceEpisode("audio-1", "2026-09-01", "2026-09-01", program = "music-album", runtime = runtime)),
        )
        repo.refresh()
        now = t1
        playback.update(id("audio-1")) { PlaybackRules.advance(it, 12.minutes, runtime, now) }
        session.selectLibrary(LibraryView(SourceItemId("lib-books"), "Radio (books)", "books", LibraryKind.BOOKS))
        // 一覧の UserData が無い（読めなかった）ことにして、同期では突き合わせない。結び直しだけが起きる
        gateway.snapshot = SourceSnapshot(listOf(program), listOf(se("book-1", "2026-09-01", shared = null)))
        repo.refresh()
        assertTrue(row("book-1")!!.sharePending)
        gateway.playStates[SourceItemId("book-1")] = SharedPlayState(40.minutes, false, t2)

        sharing.sendPending(sharing.observePending().first(), emptySet())

        assertEquals(emptyList(), gateway.writtenPlayStates)
        assertEquals(40.minutes, episode("book-1").playback?.position)
    }

    /** 送る前に読めなかった回は、送らずに未送信のまま残す（再生は止めない）。 */
    @Test
    fun sendPendingKeepsPendingWhenTheReadFails() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        gateway.playStateReadFailure = SourceException.Unreachable()

        sharing.sendPending(sharing.observePending().first(), emptySet())

        assertEquals(emptyList(), gateway.writtenPlayStates)
        assertTrue(row("e1")!!.sharePending)
        assertEquals(t1 to SyncOutcome.Unreachable, shareStatus.status.first()?.currentFailure)
    }

    /**
     * 同期で「手元を送る」と決めてから送るまでに手元が変わったら、変わった後の値を送る。
     * 2 回を送る同期で、1 回目を送っている間にもう 1 回の手元を変える。
     */
    @Test
    fun syncSendsTheLatestLocalValueWhenItChangesBeforeSending() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        val e1 = id("e1")
        val e2 = id("e2")
        playback.update(e1) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        playback.update(e2) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        var changed: SourceItemId? = null
        gateway.duringWrite = {
            if (changed == null) {
                val first = gateway.writtenPlayStates.single().first
                val other = if (first == SourceItemId("e1")) e2 else e1
                changed = if (other == e1) SourceItemId("e1") else SourceItemId("e2")
                now = t2
                playback.update(other) { PlaybackRules.advance(it, 4.minutes, runtime, now) }
            }
        }

        repo.refresh()

        assertEquals(2, gateway.writtenPlayStates.size)
        assertEquals(changed!! to SharedPlayState(4.minutes, false, t2), gateway.writtenPlayStates.last())
        assertFalse(row(changed!!.value)!!.sharePending)
    }

    /** 決めてから送るまでに、ほかの送信（一時停止の後など）がその回を送り終えていたら、同期は古い一覧の値で送り直さない。 */
    @Test
    fun syncDoesNotResendWhatAnotherSendAlreadySent() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        val e1 = id("e1")
        val e2 = id("e2")
        playback.update(e1) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        playback.update(e2) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        var other: EpisodeId? = null
        gateway.duringWrite = {
            if (other == null) {
                val first = gateway.writtenPlayStates.single().first
                val o = if (first == SourceItemId("e1")) e2 else e1
                other = o
                // ほかの送信が 5 分を送り、最後に見た値が変わった
                now = t2
                playback.update(o) { PlaybackRules.advance(it, 5.minutes, runtime, now) }
                db.playbackStateDao().markSent(
                    o.value, dev.tseki.kikidame.domain.Ticks.fromDuration(5.minutes), false, t2,
                    dev.tseki.kikidame.domain.Ticks.fromDuration(5.minutes), false, t2,
                )
            }
        }

        repo.refresh()

        assertEquals(1, gateway.writtenPlayStates.size, "only the first one is written by the sync")
        assertEquals(5.minutes, db.playbackStateDao().findByEpisode(other!!.value)!!.let { dev.tseki.kikidame.domain.Ticks.toDuration(it.positionTicks) })
        assertFalse(db.playbackStateDao().findByEpisode(other!!.value)!!.sharePending)
    }

    @Test
    fun sendPendingSkipsTheEpisodeBeingPlayed() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        playback.update(id("e2")) { PlaybackRules.setPlayed(it, true, now) }

        assertTrue(sharing.sendPending(sharing.observePending().first(), except = setOf(id("e1"))))

        assertEquals(listOf(SourceItemId("e2") to SharedPlayState(Duration.ZERO, true, t1)), gateway.writtenPlayStates)
        assertTrue(row("e1")!!.sharePending)
        assertEquals(listOf("e1"), sharing.observePending().first().map { it.sourceItemId })
    }

    /** 送っている間に手元が変わったら（再生中の定期保存など）、未送信の印は残す。 */
    @Test
    fun aChangeWhileSendingStaysPending() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        val rows = sharing.observePending().first()
        val e1 = id("e1")
        gateway.duringWrite = {
            now = t2
            playback.update(e1) { PlaybackRules.advance(it, 4.minutes, runtime, now) }
        }

        sharing.sendPending(rows, emptySet())

        assertTrue(row("e1")!!.sharePending)
        assertEquals(t1, row("e1")!!.sharedLastPlayedAt, "the server value is remembered anyway")
    }

    @Test
    fun refreshBeforePlayTakesANewerServerValue() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }
        sharing.sendPending(sharing.observePending().first(), emptySet())

        // 送った後に Web で 30 分まで聴いた
        gateway.playStates[SourceItemId("e1")] = SharedPlayState(30.minutes, false, t2)
        val taken = sharing.refreshBeforePlay(id("e1"))

        assertEquals(30.minutes, taken?.position)
        assertEquals(30.minutes, episode("e1").playback?.position)
        assertFalse(row("e1")!!.sharePending)
        assertEquals(SourceItemId("e1"), gateway.readPlayStates.last())
    }

    @Test
    fun refreshBeforePlayKeepsTheLocalValueWhenNothingNewerOrOnFailure() = runTest {
        selectBooks()
        gateway.snapshot = snapshot()
        repo.refresh()
        now = t1
        playback.update(id("e1")) { PlaybackRules.advance(it, 3.minutes, runtime, now) }

        // 手元が未送信で、サーバは変わっていない: 手元のまま（送るのは一時停止の後と同期）
        assertNull(sharing.refreshBeforePlay(id("e1")))
        assertEquals(3.minutes, episode("e1").playback?.position)

        gateway.playStateFailure = SourceException.Unreachable()
        gateway.playStates[SourceItemId("e1")] = SharedPlayState(30.minutes, false, t2)
        assertNull(sharing.refreshBeforePlay(id("e1")), "a failed read plays from the local position")
        assertEquals(3.minutes, episode("e1").playback?.position)
    }

    @Test
    fun localUpdatesMarkPendingAndKeepTheLastServerValue() = runTest {
        selectBooks()
        gateway.snapshot = snapshot(mapOf("e1" to SharedPlayState(20.minutes, false, t1)))
        repo.refresh()
        assertFalse(row("e1")!!.sharePending)

        now = t2
        playback.update(id("e1")) { PlaybackRules.advance(it, 21.minutes, runtime, now) }

        val updated = row("e1")!!
        assertTrue(updated.sharePending)
        assertEquals(t1, updated.sharedLastPlayedAt, "the last server value is carried over")
        // 位置も再生済みも変わらない書き込みは、新しく印を立てない
        db.playbackStateDao().markSent(updated.episodeId, updated.positionTicks, false, t2, updated.positionTicks, false, t2)
        playback.update(id("e1")) { it.copy(updatedAt = now) }
        assertFalse(row("e1")!!.sharePending)
    }
}
