package dev.tseki.kikidame.ui.programs

import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.toLatestDateText
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** 番組一覧の行の補足（#41）。 */
class ProgramRowTextTest {
    private val today = LocalDate(2026, 9, 19)
    private val aired = Instant.parse("2026-09-17T15:00:00Z") // JST 2026-09-18

    @Test
    fun latestDateDropsTheYearOnlyForThisYear() {
        assertEquals("09-18", aired.toLatestDateText(today))
        assertEquals("2021-03-15", Instant.parse("2021-03-14T15:00:00Z").toLatestDateText(today))
        assertEquals("2026-09-18", aired.toLatestDateText(LocalDate(2027, 1, 1)))
    }

    @Test
    fun unplayedComesFirstAndIsOmittedWhenZero() {
        assertEquals("TBSラジオ · 未再生 3 / 手元 5 · 全 265 回 · 最新 09-18", summary(unplayed = 3, local = 5, total = 265).text())
        assertEquals("TBSラジオ · 手元 5 · 全 265 回 · 最新 09-18", summary(unplayed = 0, local = 5, total = 265).text())
    }

    @Test
    fun allLocalCollapsesTotal() {
        assertEquals("TBSラジオ · 未再生 1 / 手元 8 回 · 最新 09-18", summary(unplayed = 1, local = 8, total = 8).text())
        assertEquals("TBSラジオ · 手元 8 回 · 最新 09-18", summary(unplayed = 0, local = 8, total = 8).text())
    }

    @Test
    fun goneAndMissingPartsAreHandled() {
        assertEquals(
            "手元 0 · 全 3 回 · サーバ上で見つかりません",
            summary(unplayed = 0, local = 0, total = 3, station = null, latest = null, gone = true).text(),
        )
    }

    private fun ProgramSummary.text() = toSupportingText(today)

    private fun summary(
        unplayed: Int,
        local: Int,
        total: Int,
        station: String? = "TBSラジオ",
        latest: Instant? = aired,
        gone: Boolean = false,
    ) = ProgramSummary(
        program = Program(
            id = ProgramId(1),
            serverItemId = null,
            name = "ハライチのターン！",
            stationName = station,
            goneSince = if (gone) aired else null,
        ),
        episodeCount = total,
        localEpisodeCount = local,
        unplayedLocalCount = unplayed,
        latestAiredAt = latest,
    )
}
