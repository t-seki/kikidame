package dev.tseki.kikidame.ui.programs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.resolve
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.toLatestDateText
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** 番組一覧の行の補足（#41）。並び・省略を見るので、[UiText] を日本語（`values-ja/`）で解決した文字列で比較する（Robolectric）。 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja")
class ProgramRowTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate(2026, 9, 19)
    private val published = Instant.parse("2026-09-17T15:00:00Z") // JST 2026-09-18

    @Test
    fun latestDateDropsTheYearOnlyForThisYear() {
        assertEquals("09-18", published.toLatestDateText(today))
        assertEquals("2021-03-15", Instant.parse("2021-03-14T15:00:00Z").toLatestDateText(today))
        assertEquals("2026-09-18", published.toLatestDateText(LocalDate(2027, 1, 1)))
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
            summary(unplayed = 0, local = 0, total = 3, publisher = null, latest = null, gone = true).text(),
        )
    }

    private fun ProgramSummary.text() = toSupportingText(today).resolve(context)

    private fun summary(
        unplayed: Int,
        local: Int,
        total: Int,
        publisher: String? = "TBSラジオ",
        latest: Instant? = published,
        gone: Boolean = false,
    ) = ProgramSummary(
        program = Program(
            id = ProgramId(1),
            serverItemId = null,
            name = "ハライチのターン！",
            publisherName = publisher,
            goneSince = if (gone) published else null,
        ),
        episodeCount = total,
        localEpisodeCount = local,
        unplayedLocalCount = unplayed,
        latestPublishedAt = latest,
    )
}
