package dev.tseki.jellyfinradio.data.repository
import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.toDomain
import dev.tseki.jellyfinradio.data.db.toEntity
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.PlaybackState
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
@Singleton
class RoomPlaybackStateRepository @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val clock: Clock,
) : PlaybackStateRepository {
    private val dao get() = db.playbackStateDao()
    override suspend fun get(episodeId: EpisodeId): PlaybackState? =
        dao.findByEpisode(episodeId.value)?.toDomain()
    override fun observe(episodeId: EpisodeId): Flow<PlaybackState?> =
        dao.observeByEpisode(episodeId.value).map { it?.toDomain() }
    override suspend fun update(
        episodeId: EpisodeId,
        transform: (PlaybackState) -> PlaybackState,
    ): PlaybackState = db.withTransaction {
        val current = dao.findByEpisode(episodeId.value)?.toDomain()
            ?: PlaybackState.initial(episodeId, clock.now())
        transform(current).also { dao.upsert(it.toEntity()) }
    }
}
