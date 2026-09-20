package dev.tseki.kikidame.ui.player

import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.UiText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 再生画面のタイトル下 `配信元 · 出演者`（#70）。 */
class PlayerSubtitleTest {
    @Test
    fun joinsPublisherAndPerformers() {
        // 「TBSラジオ · 岩井勇気、澤部佑」
        assertEquals(
            subtitle(UiText.Plain("TBSラジオ"), performers("岩井勇気", "澤部佑")),
            playerSubtitle("TBSラジオ", listOf("岩井勇気", "澤部佑")),
        )
    }

    @Test
    fun omitsTheMissingPart() {
        assertEquals(subtitle(UiText.Plain("TBSラジオ")), playerSubtitle("TBSラジオ", emptyList()))
        assertEquals(subtitle(performers("岩井勇気")), playerSubtitle(null, listOf("岩井勇気")))
    }

    @Test
    fun nullWhenBothAreMissing() {
        assertNull(playerSubtitle(null, emptyList()))
    }

    private fun subtitle(vararg parts: UiText) = UiText.Joined(parts.toList(), UiText.Plain(" · "))
    private fun performers(vararg names: String) = UiText.Joined(names.map(UiText::Plain), UiText.Res(R.string.common_list_separator))
}
