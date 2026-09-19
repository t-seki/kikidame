package dev.tseki.jellyfinradio.ui.episodes

import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.Episode
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LocalFile
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ServerItemId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 各回一覧の行の補足（#66）: タイトルが放送日と同じ文字列のときだけ放送日を省く。 */
class EpisodeRowTextTest {
    private val aired = Instant.parse("2026-08-01T15:00:00Z") // JST 2026-08-02

    @Test
    fun airedDateIsOmittedWhenTitleEqualsIt() {
        assertEquals("2:00:00", item(title = "2026-08-02").text())
    }

    @Test
    fun airedDateStaysWhenTitleDiffers() {
        assertEquals("2026-08-02 · 2:00:00", item(title = "第 12 回 夏休みスペシャル").text())
    }

    /** 同じ日の 2 本目（`(1)` 付き）は完全一致ではないので、放送日も残す。 */
    @Test
    fun suffixedTitleKeepsAiredDate() {
        assertEquals("2026-08-02 · 2:00:00", item(title = "2026-08-02 (1)").text())
    }

    /** 表記ゆれは吸収しない（サーバの表記そのまま）。 */
    @Test
    fun differentNotationIsNotAMatch() {
        assertEquals("2026-08-02 · 2:00:00", item(title = "2026/08/02").text())
    }

    /** 年またぎ: UTC では前年の 12-31 でも、放送日は JST で読むので 01-01 のタイトルと一致する。 */
    @Test
    fun matchesAcrossTheYearBoundaryInJst() {
        val newYear = Instant.parse("2025-12-31T15:00:00Z") // JST 2026-01-01
        assertEquals("2:00:00", item(title = "2026-01-01", airedAt = newYear).text())
        assertEquals("2026-01-01 · 2:00:00", item(title = "2025-12-31", airedAt = newYear).text())
    }

    @Test
    fun downloadStatusFollowsTheRuntime() {
        assertEquals("2:00:00 · ダウンロード中", item(title = "2026-08-02", state = DownloadState.RUNNING).text())
        assertEquals("2026-08-02 · 2:00:00 · 待機中", item(title = "第 12 回", state = DownloadState.PENDING).text())
        assertEquals("2:00:00 · Wi-Fi 待ち", item(title = "2026-08-02", state = DownloadState.PENDING).text(waitingForNetwork = true))
        assertEquals("2:00:00 · 失敗（タップで再試行）", item(title = "2026-08-02", state = DownloadState.FAILED).text())
        assertEquals("2:00:00", item(title = "2026-08-02", state = DownloadState.DONE).text())
    }

    private fun EpisodeWithState.text(waitingForNetwork: Boolean = false) = toSupportingText(waitingForNetwork)

    private fun item(title: String, airedAt: Instant = aired, state: DownloadState? = null): EpisodeWithState {
        val episode = Episode(
            id = EpisodeId(1),
            serverItemId = ServerItemId("abc"),
            programId = ProgramId(1),
            title = title,
            airedAt = airedAt,
            addedAt = airedAt,
            runtime = 2.hours,
            sizeBytes = 0,
            container = "m4a",
        )
        val local = state?.let { LocalFile(episode.id, it, path = null, pinned = false) }
        return EpisodeWithState(episode, local, null)
    }
}
