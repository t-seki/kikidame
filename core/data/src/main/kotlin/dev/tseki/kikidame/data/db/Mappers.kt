package dev.tseki.kikidame.data.db
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalEpisodeKey
import dev.tseki.kikidame.domain.LocalEpisodeState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.LocalProgramKey
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.Ticks
import kotlin.time.Instant
// Entity ↔ ドメイン型。ticks ↔ Duration の変換はここに閉じ込める。
fun ProgramEntity.toDomain(): Program = Program(
    id = ProgramId(id),
    serverItemId = serverItemId?.let(::ServerItemId),
    name = name,
    publisherName = publisherName,
    syncEnabled = syncEnabled,
    retentionRule = RetentionRule(keepLatest = keepLatest, deleteAfterPlayed = deleteAfterPlayed),
    goneSince = goneSince,
    starred = starred,
)
fun ProgramSummaryRow.toDomain(): ProgramSummary = ProgramSummary(
    program = program.toDomain(),
    episodeCount = episodeCount,
    localEpisodeCount = localEpisodeCount,
    unplayedLocalCount = unplayedLocalCount,
    latestPublishedAt = latestPublishedAt?.let(Instant::fromEpochMilliseconds),
)
fun ProgramKeyRow.toDomain(): LocalProgramKey =
    LocalProgramKey(ProgramId(id), serverItemId?.let(::ServerItemId), publisherName, name)
fun EpisodeKeyRow.toDomain(): LocalEpisodeKey =
    LocalEpisodeKey(EpisodeId(id), serverItemId?.let(::ServerItemId), ProgramId(programId), title, publishedAt, Ticks.toDuration(runtimeTicks))
fun EpisodeSyncRow.toDomain(): LocalEpisodeState = LocalEpisodeState(
    id = EpisodeId(id),
    serverItemId = serverItemId?.let(::ServerItemId),
    publishedAt = publishedAt,
    title = title,
    pinned = pinned == true,
    played = played == true,
    hasLocalFile = hasLocalFile,
)
fun EpisodeEntity.toDomain(): Episode = Episode(
    id = EpisodeId(id),
    serverItemId = serverItemId?.let(::ServerItemId),
    programId = ProgramId(programId),
    title = title,
    publishedAt = publishedAt,
    addedAt = addedAt,
    runtime = Ticks.toDuration(runtimeTicks),
    sizeBytes = sizeBytes,
    container = container,
    performers = performers,
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
