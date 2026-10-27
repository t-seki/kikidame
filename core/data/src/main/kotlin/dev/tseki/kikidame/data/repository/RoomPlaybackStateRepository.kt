package dev.tseki.kikidame.data.repository
import androidx.room.withTransaction
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.toDomain
import dev.tseki.kikidame.data.db.toEntity
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.PlaybackStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
@Singleton
class RoomPlaybackStateRepository @Inject constructor(
    private val db: KikidameDatabase,
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
        val row = dao.findByEpisode(episodeId.value)
        val existing = row?.toDomain()
        val next = transform(existing ?: PlaybackState.initial(episodeId, clock.now()))
        if (PlaybackRules.isWorthRecording(existing, next)) {
            // 位置か再生済みが変わったら未送信（再生の共有、ADR 0013 の決定 5）。最後に見た取得元の値は運ぶ（ここでは変えない）
            val changed = existing == null || existing.position != next.position || existing.played != next.played
            val entity = next.toEntity()
            dao.upsert(
                entity.copy(
                    sharePending = (row?.sharePending ?: false) || changed,
                    sharedPositionTicks = row?.sharedPositionTicks,
                    sharedPlayed = row?.sharedPlayed,
                    sharedLastPlayedAt = row?.sharedLastPlayedAt,
                ),
            )
        }
        next
    }
}
