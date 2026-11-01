package dev.tseki.kikidame.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 埋め込みチャプターの取り出しと、今のチャプターの決め方。 */
@UnstableApi
class EpisodeChaptersTest {
    private fun chapter(startMs: Long, title: String, hidden: Boolean = false): Chapter =
        Chapter.Builder().setStartTimeMs(startMs).setEndTimeMs(startMs + 1).setHidden(hidden).setTitle(Label(null, title)).build()

    private fun tracksOf(vararg entries: Metadata.Entry): Tracks {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setMetadata(Metadata(*entries)).build()
        return Tracks(listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))))
    }

    private val chapters = listOf(EpisodeChapter(0, "オープニング"), EpisodeChapter(60_000, "ふつおた"), EpisodeChapter(180_000, "エンディング"))

    @Test
    fun noTracksMeansNoChapters() {
        assertEquals(emptyList(), chaptersOf(Tracks.EMPTY))
    }

    @Test
    fun trackWithoutChaptersMeansNoChapters() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))))
        assertEquals(emptyList(), chaptersOf(tracks))
    }

    @Test
    fun readsStartAndTitleInStartOrder() {
        val tracks = tracksOf(chapter(60_000, "ふつおた"), chapter(0, "オープニング"))
        assertEquals(listOf(EpisodeChapter(0, "オープニング"), EpisodeChapter(60_000, "ふつおた")), chaptersOf(tracks))
    }

    @Test
    fun dropsHiddenChapters() {
        val tracks = tracksOf(chapter(0, "オープニング"), chapter(30_000, "隠し", hidden = true), chapter(60_000, "ふつおた"))
        assertEquals(listOf("オープニング", "ふつおた"), chaptersOf(tracks).map { it.title })
    }

    @Test
    fun ignoresChaptersOnNonAudioTracks() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setMetadata(Metadata(chapter(0, "字幕側"))).build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))))
        assertEquals(emptyList(), chaptersOf(tracks))
    }

    @Test
    fun noChapterIsCurrentWhenThereAreNone() {
        assertNull(currentChapter(emptyList(), 10_000))
    }

    @Test
    fun chapterStartingExactlyAtPositionIsCurrent() {
        assertEquals(chapters[1], currentChapter(chapters, 60_000))
    }

    @Test
    fun positionBetweenTwoChaptersIsThePreviousOne() {
        assertEquals(chapters[1], currentChapter(chapters, 179_999))
    }

    @Test
    fun positionAfterTheLastStartIsTheLastChapter() {
        assertEquals(chapters[2], currentChapter(chapters, 999_999))
    }

    @Test
    fun positionBeforeTheFirstStartIsNone() {
        assertNull(currentChapter(listOf(EpisodeChapter(5_000, "最初")), 4_999))
    }

    @Test
    fun hiddenChaptersDoNotTakePartInDecidingTheCurrentOne() {
        val shown = chaptersOf(tracksOf(chapter(0, "オープニング"), chapter(30_000, "隠し", hidden = true), chapter(60_000, "ふつおた")))
        assertEquals("オープニング", currentChapter(shown, 45_000)?.title)
    }
}
