package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.data.jellyfin.BookProgramImage.Choice
import dev.tseki.kikidame.data.jellyfin.BookProgramImage.Episode
import dev.tseki.kikidame.data.jellyfin.BookProgramImage.Item
import dev.tseki.kikidame.domain.SourceImage
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * ブックのライブラリの番組の画像の選び方（ADR 0009 の「番組の画像」）。実サーバ無しで、ライブラリ全体の同期（[BookProgramImage.forFullListing]）と
 * 1 番組の同期（[BookProgramImage.forNarrowed]）の両方の経路で、同じ結果になることを見る。
 */
class BookProgramImageTest {
    private val album = "テスト番組C"

    /** 配信元のフォルダ（名前が Album と違う）。Primary あり（`folder.jpg`）。 */
    private val publisherFolder = Item(UUID.randomUUID(), "ニッポン放送", "publisher-tag")

    /** 番組のフォルダ（名前が Album と同じ）。Primary あり。 */
    private val programFolder = Item(UUID.randomUUID(), album, "program-folder-tag")

    /** ほかの Folder（ライブラリ全体の同期の Folder の一覧に混ざるもの）。 */
    private val otherFolder = Item(UUID.randomUUID(), "テスト番組A", "other-tag")

    private fun episode(tag: String?, parent: Item) = Episode(Item(UUID.randomUUID(), "2026-09-01", tag), parent.id)

    /** 両方の経路で選ぶ。ライブラリ全体の同期はライブラリの全 Folder、1 番組の同期は各回の親だけを引ける。 */
    private fun bothPaths(episodes: List<Episode>, parent: Item): List<Choice> = listOf(
        BookProgramImage.forFullListing(album, episodes, listOf(publisherFolder, programFolder, otherFolder).associateBy { it.id }),
        BookProgramImage.forNarrowed(album, episodes, parent),
    )

    @Test
    fun singleEpisodeUnderThePublisherFolderUsesTheEpisodeImage() {
        val only = episode("episode-tag", publisherFolder)
        for (choice in bothPaths(listOf(only), publisherFolder)) {
            // 回の ImageTag（配信元のフォルダのものではない）。取得先はその回
            assertEquals(Choice(SourceImage("episode-tag"), only.item.id), choice)
        }
    }

    @Test
    fun singleEpisodeWithoutAnImageHasNoProgramImage() {
        for (tag in listOf(null, "")) {
            val only = episode(tag, publisherFolder)
            for (choice in bothPaths(listOf(only), publisherFolder)) {
                // 配信元のフォルダに Primary があっても、それを番組の画像にしない。取得先も無し
                assertEquals(BookProgramImage.NONE, choice, "ImageTag: $tag")
            }
        }
    }

    @Test
    fun singleEpisodeDirectlyUnderAFolderThatIsNotListedUsesTheEpisodeImage() {
        // 親が Folder の一覧に無い（ライブラリの直下など）ときも、番組のフォルダが無いので回の画像
        val root = Item(UUID.randomUUID(), "radio-book", "root-tag")
        val only = episode("episode-tag", root)
        assertEquals(Choice(SourceImage("episode-tag"), only.item.id), BookProgramImage.forFullListing(album, listOf(only), emptyMap()))
        assertEquals(Choice(SourceImage("episode-tag"), only.item.id), BookProgramImage.forNarrowed(album, listOf(only), null))
    }

    @Test
    fun aSecondEpisodeInAProgramFolderSwitchesToTheProgramFolderImage() {
        // 1 本だけ（親が配信元のフォルダ）: 回の画像
        val first = episode("episode-tag", publisherFolder)
        for (choice in bothPaths(listOf(first), publisherFolder)) {
            assertEquals(Choice(SourceImage("episode-tag"), first.item.id), choice)
        }
        // 2 本目が増えて番組のフォルダができた（各回の親が、名前が Album と同じフォルダ）: 番組のフォルダの画像、取得先はそのフォルダ
        val moved = first.copy(parentId = programFolder.id)
        val second = episode("second-tag", programFolder)
        for (choice in bothPaths(listOf(second, moved), programFolder)) {
            assertEquals(Choice(SourceImage("program-folder-tag"), programFolder.id), choice)
        }
    }

    @Test
    fun aProgramFolderWithoutAnImageIsStillTheSource() {
        // 番組のフォルダがあれば、回の画像は使わない（Primary が無ければ画像なし。取得先はフォルダのまま、取りに行って 404 なら画像なし）
        val folder = programFolder.copy(primaryTag = null)
        val episodes = listOf(episode("a", folder), episode("b", folder))
        assertEquals(Choice(null, folder.id), BookProgramImage.forFullListing(album, episodes, mapOf(folder.id to folder)))
        assertEquals(Choice(null, folder.id), BookProgramImage.forNarrowed(album, episodes, folder))
    }

    @Test
    fun severalEpisodesWithoutAProgramFolderHaveNoProgramImage() {
        // 番組のフォルダとみなせない（親が配信元のフォルダ・親が分かれる）2 本以上の番組は、回の画像も配信元の画像も使わない
        val underPublisher = listOf(episode("a", publisherFolder), episode("b", publisherFolder))
        for (choice in bothPaths(underPublisher, publisherFolder)) assertEquals(BookProgramImage.NONE, choice)
        val split = listOf(episode("a", programFolder), episode("b", publisherFolder))
        assertEquals(
            BookProgramImage.NONE,
            BookProgramImage.forFullListing(album, split, listOf(publisherFolder, programFolder).associateBy { it.id }),
        )
    }
}
