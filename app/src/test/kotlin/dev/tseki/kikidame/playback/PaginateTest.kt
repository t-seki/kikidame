package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import org.junit.Test
import kotlin.test.assertEquals
/** `onGetChildren` のページ分割（#96）。Auto は普通ページを指定しないので、指定が無ければ全部。 */
class PaginateTest {
    private val items = (1..5).map { MediaItem.Builder().setMediaId(it.toString()).build() }
    private fun ids(list: List<MediaItem>) = list.map { it.mediaId }
    @Test
    fun noPageSizeReturnsEverything() {
        assertEquals(ids(items), ids(PlaybackService.paginate(items, 0, 0)))
        assertEquals(ids(items), ids(PlaybackService.paginate(items, 0, Int.MAX_VALUE)))
    }
    @Test
    fun pagesAreSlicedWithoutOverflow() {
        assertEquals(listOf("1", "2"), ids(PlaybackService.paginate(items, 0, 2)))
        assertEquals(listOf("5"), ids(PlaybackService.paginate(items, 2, 2)))
        assertEquals(emptyList(), ids(PlaybackService.paginate(items, 3, 2)))
        assertEquals(emptyList(), ids(PlaybackService.paginate(items, 1, Int.MAX_VALUE)))
    }
}
