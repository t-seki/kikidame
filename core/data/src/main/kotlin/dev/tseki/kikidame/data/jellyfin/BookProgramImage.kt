package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.domain.SourceImage
import java.util.UUID

/**
 * ブックのライブラリの番組の画像の選び方（ADR 0009 の「番組の画像」）。SDK を呼ばない純粋な関数だけを置く。
 * ライブラリ全体の同期（[SdkJellyfinGateway] の `fetchBooks`）と 1 番組の同期（`fetchBookProgramNarrowed`）が、どちらもこれで選ぶ。
 */
internal object BookProgramImage {
    /** 画像の候補になるアイテム（各回の AudioBook か、その親の Folder）。[primaryTag] は Primary の ImageTag（無ければ null か空）。 */
    data class Item(val id: UUID, val name: String?, val primaryTag: String?)

    /** 番組の各回（AudioBook）と、その親（`ParentId`）。 */
    data class Episode(val item: Item, val parentId: UUID?)

    /**
     * 選んだ番組の画像。[image] は一覧に載せる画像の版（無ければ画像なし）、[source] は画像の中身を取るアイテムの id
     * （`fetchProgramImage` が Primary を取る先。無ければ取らずに画像なし）。
     */
    data class Choice(val image: SourceImage?, val source: UUID?)

    /** 画像なし。 */
    val NONE = Choice(image = null, source = null)

    /**
     * 番組（Album が [album]）の各回 [episodes] から、番組の画像を選ぶ。[folderOf] は親の id → その Folder（引けなければ null）。
     *
     * 1. 各回の親がどれも同じ Folder で、その名前が Album と同じなら、それを番組のフォルダとみなし、その Primary
     *    （Primary が無くても取得先はそのフォルダ。中身を取りに行って 404 なら画像なし）
     * 2. 番組のフォルダが無く、各回が 1 本だけなら、その回の画像（その AudioBook の Primary）。Primary が無ければ画像なし
     * 3. それ以外は画像なし
     *
     * 各回が 1 本だけの番組は、各回の親が配信元のフォルダになる（Jellyfin 10.10.7 / 10.11.11 / 12.0.0 で確かめた）。
     * 名前が Album と違うので 1 にならず、配信元のフォルダの画像は番組の画像にしない。
     */
    fun choose(album: String, episodes: List<Episode>, folderOf: (UUID) -> Item?): Choice {
        val folder = episodes.map { it.parentId }.distinct().singleOrNull()?.let(folderOf)?.takeIf { it.name == album }
        if (folder != null) return Choice(image = folder.primaryImage(), source = folder.id)
        val only = episodes.singleOrNull()?.item ?: return NONE
        val image = only.primaryImage() ?: return NONE
        return Choice(image = image, source = only.id)
    }

    /** ライブラリ全体の同期で選ぶ。[folders] はライブラリの全 Folder（id → Folder）。 */
    fun forFullListing(album: String, episodes: List<Episode>, folders: Map<UUID, Item>): Choice = choose(album, episodes, folders::get)

    /** 1 番組の同期（親の直下だけを取る、ADR 0016）で選ぶ。[parent] は `ids=<親>` で引いた親（引けなければ null）。 */
    fun forNarrowed(album: String, episodes: List<Episode>, parent: Item?): Choice =
        choose(album, episodes) { id -> parent?.takeIf { it.id == id } }

    private fun Item.primaryImage(): SourceImage? = primaryTag?.takeIf { it.isNotBlank() }?.let(::SourceImage)
}
