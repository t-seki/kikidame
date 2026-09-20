package dev.tseki.kikidame.playback
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.PlaybackStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
class InMemoryPlaybackStateRepository(private val clock: Clock) : PlaybackStateRepository {
    val states = MutableStateFlow<Map<EpisodeId, PlaybackState>>(emptyMap())
    var updateCount = 0
        private set
    override suspend fun get(episodeId: EpisodeId): PlaybackState? = states.value[episodeId]
    override fun observe(episodeId: EpisodeId): Flow<PlaybackState?> = states.map { it[episodeId] }
    override suspend fun update(episodeId: EpisodeId, transform: (PlaybackState) -> PlaybackState): PlaybackState {
        updateCount++
        val existing = states.value[episodeId]
        val next = transform(existing ?: PlaybackState.initial(episodeId, clock.now()))
        if (PlaybackRules.isWorthRecording(existing, next)) states.value = states.value + (episodeId to next)
        return next
    }
}
