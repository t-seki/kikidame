package dev.tseki.kikidame.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter

/**
 * 各回のファイルに埋め込まれたチャプター（#348、CONTEXT.md の「チャプター」）。開始の時刻と題。
 * アプリは作らない・変えない。ファイルから読むだけ。
 */
data class EpisodeChapter(val startMs: Long, val title: String)

/**
 * [Tracks] の音声トラックの `Format.metadata` からチャプターを取り出す。`isHidden` は除き、開始の順に並べる。
 * チャプターが無ければ空。
 */
@OptIn(UnstableApi::class)
internal fun chaptersOf(tracks: Tracks): List<EpisodeChapter> {
    val chapters = buildList {
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until metadata.length()) {
                    val chapter = metadata.get(j) as? Chapter ?: continue
                    if (chapter.isHidden) continue
                    add(EpisodeChapter(chapter.startTimeMs, chapter.title?.value.orEmpty()))
                }
            }
        }
    }
    return chapters.sortedBy { it.startMs }
}

/**
 * [positionMs] の時点の今のチャプター: 開始が位置以下のうちで最後のもの。最初のチャプターの開始より前、またはチャプターが無ければ null。
 * [chapters] は [chaptersOf] が返す、開始の順で hidden を除いたもの。
 */
internal fun currentChapter(chapters: List<EpisodeChapter>, positionMs: Long): EpisodeChapter? =
    chapters.lastOrNull { it.startMs <= positionMs }
