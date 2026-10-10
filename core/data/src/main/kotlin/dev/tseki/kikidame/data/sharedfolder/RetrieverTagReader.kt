package dev.tseki.kikidame.data.sharedfolder

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import kotlin.time.Duration.Companion.milliseconds

/**
 * [MediaMetadataRetriever] でタグを読む [TagReader]（#197）。ファイル全体を転送しないように、
 * [FolderTree.read]（部分読み）を使う [MediaDataSource] を渡す。
 *
 * - 木の読み取りが失敗したら、その例外を retriever の後で投げ直す（[TagReader] の約束。タグ無しとして保存しないため）
 * - retriever がファイルを解釈できないとき（[RuntimeException]）は空の [AudioTags]
 * - 日付は `METADATA_KEY_YEAR` → `METADATA_KEY_DATE` の順に、年月日まで読めるほうを使う（どちらに何が入るかは形式による。実機で未確認）
 *
 * JVM のテストで動くかは確かめていない（未確認）。テストはフェイクの [TagReader] で行い、このクラス自体はテストしていない。実機で確かめる（#198）。
 */
class RetrieverTagReader : TagReader {
    override fun read(tree: FolderTree, path: String, sizeBytes: Long): AudioTags {
        val source = FolderTreeDataSource(tree, path, sizeBytes)
        val tags = try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(source)
                val year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                val date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                AudioTags(
                    title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    date = listOfNotNull(year, date).firstOrNull { TagDate.parse(it) != null } ?: date ?: year,
                    artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.milliseconds,
                )
            }
        } catch (e: RuntimeException) {
            source.failure?.let { throw it }
            AudioTags()
        }
        source.failure?.let { throw it }
        return tags
    }
}

/** [FolderTree.read] を [MediaDataSource] に見せる。読み取りの失敗は [failure] に取っておき、retriever には末尾として返す。 */
private class FolderTreeDataSource(
    private val tree: FolderTree,
    private val path: String,
    private val sizeBytes: Long,
) : MediaDataSource() {
    var failure: Exception? = null
        private set

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (failure != null) return -1
        return try {
            tree.read(path, position, buffer, offset, size)
        } catch (e: Exception) {
            failure = e
            -1
        }
    }

    override fun getSize(): Long = sizeBytes

    override fun close() = Unit
}
