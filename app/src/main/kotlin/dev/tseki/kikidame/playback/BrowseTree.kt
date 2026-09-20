package dev.tseki.kikidame.playback
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.toPublishedDateText
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
/**
 * Android Auto のブラウズツリー（#96）。ルートの下は「よく聴く」「番組」のタブ → 番組 → 手元にある各回の 3 階層
 * （Auto が推奨する上限内）。手元に無い回は出さない（運転中に落とさせない）。
 * 「よく聴く」は、印が付いていて手元に回がある番組が 1 つも無ければタブごと出さない。
 * 「続きから」と再開（`onPlaybackResumption`）は #108。
 *
 * ID の形は [Node]。各回の ID は [EpisodeMediaItems.mediaId] と同じなので、ツリーから選ばれた回は
 * 既存の `onAddMediaItems` / `onSetMediaItems` がそのまま解決できる。
 */
// Media3 の MediaConstants（完了状態の extras）は unstable API。クラス単位で opt-in する（#111）
@OptIn(UnstableApi::class)
class BrowseTree(
    private val library: LibraryRepository,
    private val labels: Labels,
) {
    /** タブの表示名。Service が `getString` して渡す（ADR 0009 の例外）。 */
    data class Labels(val starred: String, val programs: String)

    sealed interface Node {
        data object Root : Node
        data object Starred : Node
        data object Programs : Node
        data class Program(val id: ProgramId) : Node
        data class Episode(val id: EpisodeId) : Node
    }

    fun root(): MediaItem = folder(ROOT_ID, "", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** [parentId] の子。ツリーに無い ID なら null（`RESULT_ERROR_BAD_VALUE`）。各回は葉なので空リスト。 */
    suspend fun children(parentId: String): List<MediaItem>? = when (val node = parse(parentId)) {
        null -> null
        Node.Root -> rootChildren()
        Node.Starred -> programsWithLocalEpisodes().filter { it.program.starred }.map(::programItem)
        Node.Programs -> programsWithLocalEpisodes().map(::programItem)
        is Node.Program -> {
            val program = library.observeProgram(node.id).first() ?: return null
            library.observeEpisodes(node.id).first().filter { it.isPlayable }.map { episodeItem(it, program) }
        }
        is Node.Episode -> emptyList()
    }

    suspend fun item(mediaId: String): MediaItem? = when (val node = parse(mediaId)) {
        null -> null
        Node.Root -> root()
        Node.Starred -> folder(STARRED_ID, labels.starred, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
        Node.Programs -> folder(PROGRAMS_ID, labels.programs, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
        is Node.Program -> library.observeProgram(node.id).first()?.let { programItem(it) }
        is Node.Episode -> {
            val episode = library.getEpisode(node.id)?.takeIf { it.isPlayable } ?: return null
            val program = library.observeProgram(episode.episode.programId).first() ?: return null
            episodeItem(episode, program)
        }
    }

    private suspend fun rootChildren(): List<MediaItem> {
        val programs = programsWithLocalEpisodes()
        return buildList {
            if (programs.any { it.program.starred }) add(folder(STARRED_ID, labels.starred, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS))
            add(folder(PROGRAMS_ID, labels.programs, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS))
        }
    }

    /** [LibraryRepository.observePrograms] の順（最新回の公開日が新しい順）のまま、手元に回がある番組だけ。 */
    private suspend fun programsWithLocalEpisodes(): List<ProgramSummary> =
        library.observePrograms().first().filter { it.localEpisodeCount > 0 }

    private fun programItem(summary: ProgramSummary): MediaItem = programItem(summary.program)

    private fun programItem(program: Program): MediaItem = MediaItem.Builder()
        .setMediaId(programId(program.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(program.name)
                .setSubtitle(program.publisherName)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                .build(),
        )
        .build()

    /** 各回の葉。[EpisodeMediaItems.toMediaItem] に、一覧向けの公開日（subtitle）と再生済み／途中の印を足す。 */
    private fun episodeItem(item: EpisodeWithState, program: Program): MediaItem {
        val base = EpisodeMediaItems.toMediaItem(item, program)
        return base.buildUpon()
            .setMediaMetadata(
                base.mediaMetadata.buildUpon()
                    .setSubtitle(item.episode.publishedAt.toPublishedDateText())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setExtras(completionExtras(item.playback, item.episode.runtime))
                    .build(),
            )
            .build()
    }

    private fun folder(id: String, title: String, mediaType: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()

    companion object {
        const val ROOT_ID = "root"
        const val STARRED_ID = "starred"
        const val PROGRAMS_ID = "programs"
        private const val PROGRAM_PREFIX = "program:"

        fun programId(programId: ProgramId): String = PROGRAM_PREFIX + programId.value

        fun parse(mediaId: String): Node? = when {
            mediaId == ROOT_ID -> Node.Root
            mediaId == STARRED_ID -> Node.Starred
            mediaId == PROGRAMS_ID -> Node.Programs
            mediaId.startsWith(PROGRAM_PREFIX) ->
                mediaId.removePrefix(PROGRAM_PREFIX).toLongOrNull()?.let { Node.Program(ProgramId(it)) }
            else -> mediaId.toLongOrNull()?.let { Node.Episode(EpisodeId(it)) }
        }

        /**
         * Auto の一覧に出す再生済み／途中の印。再生済みなら FULLY_PLAYED。そうでなく聴き始めていれば
         * （[PlaybackRules.isNotStarted] でない）PARTIALLY_PLAYED と進捗率。それ以外は NOT_PLAYED。
         */
        fun completionExtras(playback: PlaybackState?, runtime: Duration): Bundle = Bundle().apply {
            when {
                playback == null || PlaybackRules.isNotStarted(playback) ->
                    putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
                playback.played ->
                    putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
                else -> {
                    putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                    putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, completionPercentage(playback.position, runtime))
                }
            }
        }

        /** 0.0〜1.0。尺が不明（0）なら 0.0。 */
        fun completionPercentage(position: Duration, runtime: Duration): Double =
            if (runtime <= Duration.ZERO) 0.0
            else (position.inWholeMilliseconds.toDouble() / runtime.inWholeMilliseconds).coerceIn(0.0, 1.0)
    }
}
