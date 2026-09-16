package dev.tseki.jellyfinradio.data.db
import dev.tseki.jellyfinradio.domain.Episode
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LocalFile
import dev.tseki.jellyfinradio.domain.PlaybackState
import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.domain.RetentionRule
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.Ticks
import kotlin.time.Instant
// Entity ↔ ドメイン型。ticks ↔ Duration の変換はここに閉じ込める。
fun ProgramEntity.toDomain(): Program = Program(
    id = ProgramId(id),
    serverItemId = serverItemId?.let(::ServerItemId),
    name = name,
    stationName = stationName,
    syncEnabled = syncEnabled,
    retentionRule = RetentionRule(keepLatest = keepLatest, deleteAfterPlayed = deleteAfterPlayed),
)
fun ProgramSummaryRow.toDomain(): ProgramSummary = ProgramSummary(
    program = program.toDomain(),
    episodeCount = episodeCount,
    latestAiredAt = latestAiredAt?.let(Instant::fromEpochMilliseconds),
)
fun EpisodeEntity.toDomain(): Episode = Episode(
    id = EpisodeId(id),
    serverItemId = serverItemId?.let(::ServerItemId),
    programId = ProgramId(programId),
    title = title,
    airedAt = airedAt,
    addedAt = addedAt,
    runtime = Ticks.toDuration(runtimeTicks),
    sizeBytes = sizeBytes,
    container = container,
)
fun LocalFileEntity.toDomain(): LocalFile = LocalFile(
    episodeId = EpisodeId(episodeId),
    state = state,
    path = path,
    pinned = pinned,
    attemptCount = attemptCount,
    lastAttemptAt = lastAttemptAt,
    downloadedAt = downloadedAt,
)
fun PlaybackStateEntity.toDomain(): PlaybackState = PlaybackState(
    episodeId = EpisodeId(episodeId),
    position = Ticks.toDuration(positionTicks),
    played = played,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
)
fun PlaybackState.toEntity(): PlaybackStateEntity = PlaybackStateEntity(
    episodeId = episodeId.value,
    positionTicks = Ticks.fromDuration(position),
    played = played,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
)
fun EpisodeRow.toDomain(): EpisodeWithState = EpisodeWithState(
    episode = episode.toDomain(),
    localFile = localFile?.toDomain(),
    playback = playback?.toDomain(),
)
