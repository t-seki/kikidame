package androidx.media3.session

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Android Auto が一覧に出す legacy の `MediaDescriptionCompat` に、`BrowseTree` が置く subtitle が本当に届くか（#113）。
 * `LegacyConversions` はパッケージ非公開なので、同じパッケージ名に置いて呼ぶ。Media3 の実装（1.11.1 の
 * `LegacyConversions.convertToMediaDescriptionCompat`）が変わって subtitle が捨てられるようになったらここで気づく。
 */
@RunWith(AndroidJUnit4::class)
class BrowseTreeLegacyDescriptionTest {
    private fun item(displayTitle: String?): MediaItem = MediaItem.Builder()
        .setMediaId("42")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("第 12 回")
                .setDisplayTitle(displayTitle)
                .setArtist("ハライチのターン！")
                .setAlbumTitle("TBSラジオ")
                .setSubtitle("2026-09-19 · ハライチのターン！")
                .setDescription("TBSラジオ")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    /** `displayTitle` があれば subtitle / description がそのまま 2 行目・3 行目になる（`BrowseTree.episodeItem` の形）。 */
    @Test
    fun displayTitleKeepsSubtitleForTheBrowseList() {
        val description = LegacyConversions.convertToMediaDescriptionCompat(item(displayTitle = "第 12 回"), null)
        assertEquals("第 12 回", description.title.toString())
        assertEquals("2026-09-19 · ハライチのターン！", description.subtitle.toString())
        assertEquals("TBSラジオ", description.description.toString())
    }

    /** `displayTitle` が無いと title → artist → album の順で埋まり、subtitle は無視される（#113 で番組名だけが出た理由）。 */
    @Test
    fun withoutDisplayTitleArtistBecomesTheSubtitle() {
        val description = LegacyConversions.convertToMediaDescriptionCompat(item(displayTitle = null), null)
        assertEquals("第 12 回", description.title.toString())
        assertEquals("ハライチのターン！", description.subtitle.toString())
        assertEquals("TBSラジオ", description.description.toString())
    }
}
