package dev.tseki.jellyfinradio.ui.episodes

import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.Episode
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LocalFile
import dev.tseki.jellyfinradio.domain.PlaybackState
import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.ui.episodes.EpisodeDetails.Row
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 各回の詳細（#43）の値の組み立て。 */
class EpisodeDetailsTest {
    private val aired = Instant.parse("2026-09-17T15:00:00Z") // JST 2026-09-18
    private val at = Instant.parse("2026-09-18T03:04:00Z") // JST 12:04
    private val episode = Episode(
        id = EpisodeId(42),
        serverItemId = ServerItemId("abc123"),
        programId = ProgramId(1),
        title = "2026-09-18",
        airedAt = aired,
        addedAt = at,
        runtime = 60.minutes,
        sizeBytes = 21_900_000,
        container = "m4a",
    )
    private val program = Program(id = ProgramId(1), serverItemId = null, name = "ハライチのターン！", stationName = "TBSラジオ")

    @Test
    fun userSectionsCollapseMissingFileAndPlayback() {
        val sections = EpisodeDetails.sections(EpisodeWithState(episode, null, null), program)
        assertEquals(listOf("各回", "手元", "再生"), sections.map { it.title })
        assertEquals(
            listOf(Row("放送日", "2026-09-18"), Row("出演者", "なし"), Row("放送局", "TBSラジオ"), Row("尺", "1:00:00"), Row("サイズ", "21.9 MB")),
            sections[0].rows,
        )
        assertEquals(listOf(Row("状態", "手元に無い")), sections[1].rows)
        assertEquals(listOf(Row("再生位置", "記録なし")), sections[2].rows)
    }

    @Test
    fun userSectionsShowFileAndPlayback() {
        val item = EpisodeWithState(
            episode,
            LocalFile(episode.id, DownloadState.DONE, "/x/a.m4a", pinned = true, downloadedAt = at),
            PlaybackState(episode.id, 12.minutes, played = false, updatedAt = at),
        )
        val sections = EpisodeDetails.sections(item, program)
        assertEquals(
            listOf(Row("状態", "ダウンロード済み"), Row("固定", "固定（保持ルールの対象外）"), Row("ダウンロード日時", "2026-09-18 12:04")),
            sections[1].rows,
        )
        assertEquals(listOf(Row("再生位置", "12:00 / 1:00:00"), Row("再生済み", "未再生")), sections[2].rows)
    }

    /** 出演者（#70）は「、」で並べ、放送局は番組から。番組が読めていなければ「不明」。 */
    @Test
    fun performersAndStationRows() {
        val item = EpisodeWithState(episode.copy(performers = listOf("岩井勇気", "澤部佑")), null, null)
        assertEquals(Row("出演者", "岩井勇気、澤部佑"), EpisodeDetails.sections(item, program)[0].rows[1])
        assertEquals(Row("放送局", "不明"), EpisodeDetails.sections(item, null)[0].rows[2])
    }

    @Test
    fun technicalRowsUseRawValuesAndSkipSyncedAt() {
        val item = EpisodeWithState(
            episode,
            LocalFile(episode.id, DownloadState.FAILED, null, pinned = false, attemptCount = 3, lastAttemptAt = at),
            PlaybackState(episode.id, 12.minutes, played = false, updatedAt = at, syncedAt = at),
        )
        val rows = EpisodeDetails.technicalRows(item)
        assertEquals(Row("サーバ ID", "abc123"), rows[0])
        assertEquals(Row("ID（アプリ内）", "42"), rows[1])
        assertEquals(Row("放送日（記録の瞬間、UTC）", "2026-09-17T15:00:00Z"), rows[2])
        assertTrue(rows.any { it == Row("保存先", "なし") })
        assertTrue(rows.any { it == Row("失敗回数", "3 回") })
        assertTrue(rows.any { it == Row("最終試行", "2026-09-18 12:04") })
        assertFalse(rows.any { it.label.contains("同期") || it.label.contains("synced") })
    }

    /** 1 回で成功した回: 失敗 0 回でも最終試行（開始時刻）はある。以前は「試行 0 回（最終 …）」と矛盾して見えた。 */
    @Test
    fun successfulDownloadShowsZeroFailuresAndLastAttempt() {
        val rows = EpisodeDetails.technicalRows(
            EpisodeWithState(episode, LocalFile(episode.id, DownloadState.DONE, "/x/a.m4a", pinned = false, attemptCount = 0, lastAttemptAt = at, downloadedAt = at), null),
        )
        assertTrue(rows.any { it == Row("失敗回数", "0 回") })
        assertTrue(rows.any { it == Row("最終試行", "2026-09-18 12:04") })
    }
    @Test
    fun localOnlyEpisodeSaysSo() {
        val rows = EpisodeDetails.technicalRows(EpisodeWithState(episode.copy(serverItemId = null, addedAt = null), null, null))
        assertEquals(Row("サーバ ID", "なし（手元だけの各回）"), rows[0])
        assertEquals(Row("取り込み日時", "なし"), rows[3])
        assertFalse(rows.any { it.label == "保存先" })
    }
}
