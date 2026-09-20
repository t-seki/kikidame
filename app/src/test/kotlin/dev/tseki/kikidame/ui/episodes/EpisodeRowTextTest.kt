package dev.tseki.kikidame.ui.episodes

import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ServerItemId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 各回一覧の行の補足（#66）: タイトルが公開日と同じ文字列のときだけ公開日を省く。 */
class EpisodeRowTextTest {
    private val published = Instant.parse("2026-08-01T15:00:00Z") // JST 2026-08-02

    @Test
    fun publishedDateIsOmittedWhenTitleEqualsIt() {
        assertEquals("2:00:00", item(title = "2026-08-02").text())
    }

    @Test
    fun publishedDateStaysWhenTitleDiffers() {
        assertEquals("2026-08-02 · 2:00:00", item(title = "第 12 回 夏休みスペシャル").text())
    }

    /** 同じ日の 2 本目（`(1)` 付き）は完全一致ではないので、公開日も残す。 */
    @Test
    fun suffixedTitleKeepsPublishedDate() {
        assertEquals("2026-08-02 · 2:00:00", item(title = "2026-08-02 (1)").text())
    }

    /** 表記ゆれは吸収しない（サーバの表記そのまま）。 */
    @Test
    fun differentNotationIsNotAMatch() {
        assertEquals("2026-08-02 · 2:00:00", item(title = "2026/08/02").text())
    }

    /** 年またぎ: UTC では前年の 12-31 でも、公開日は JST で読むので 01-01 のタイトルと一致する。 */
    @Test
    fun matchesAcrossTheYearBoundaryInJst() {
        val newYear = Instant.parse("2025-12-31T15:00:00Z") // JST 2026-01-01
        assertEquals("2:00:00", item(title = "2026-01-01", publishedAt = newYear).text())
        assertEquals("2026-01-01 · 2:00:00", item(title = "2025-12-31", publishedAt = newYear).text())
    }

    @Test
    fun downloadStatusFollowsTheRuntime() {
        assertEquals("2:00:00 · ダウンロード中", item(title = "2026-08-02", state = DownloadState.RUNNING).text())
        assertEquals("2026-08-02 · 2:00:00 · 待機中", item(title = "第 12 回", state = DownloadState.PENDING).text())
        assertEquals("2:00:00 · Wi-Fi 待ち", item(title = "2026-08-02", state = DownloadState.PENDING).text(waitingForNetwork = true))
        assertEquals("2:00:00 · 失敗（タップで再試行）", item(title = "2026-08-02", state = DownloadState.FAILED).text())
        assertEquals("2:00:00", item(title = "2026-08-02", state = DownloadState.DONE).text())
    }

    /** 出演者（#70）は公開日と尺の間。無い回は今までどおり。 */
    @Test
    fun performersSitBetweenPublishedDateAndRuntime() {
        assertEquals("岩井勇気、澤部佑 · 2:00:00", item(title = "2026-08-02", performers = listOf("岩井勇気", "澤部佑")).text())
        assertEquals("2026-08-02 · 岩井勇気 · 2:00:00 · ダウンロード中", item(title = "第 12 回", performers = listOf("岩井勇気"), state = DownloadState.RUNNING).text())
        assertEquals("2:00:00", item(title = "2026-08-02", performers = emptyList()).text())
    }

    private fun EpisodeWithState.text(waitingForNetwork: Boolean = false) = toSupportingText(waitingForNetwork)

    private fun item(title: String, publishedAt: Instant = published, state: DownloadState? = null, performers: List<String> = emptyList()): EpisodeWithState {
        val episode = Episode(
            id = EpisodeId(1),
            serverItemId = ServerItemId("abc"),
            programId = ProgramId(1),
            title = title,
            publishedAt = publishedAt,
            addedAt = publishedAt,
            runtime = 2.hours,
            sizeBytes = 0,
            container = "m4a",
            performers = performers,
        )
        val local = state?.let { LocalFile(episode.id, it, path = null, pinned = false) }
        return EpisodeWithState(episode, local, null)
    }
}
