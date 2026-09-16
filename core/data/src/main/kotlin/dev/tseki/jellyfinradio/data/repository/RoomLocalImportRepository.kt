package dev.tseki.jellyfinradio.data.repository
import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.EpisodeEntity
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.LocalFileEntity
import dev.tseki.jellyfinradio.data.db.ProgramEntity
import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.ImportResult
import dev.tseki.jellyfinradio.domain.LocalImportRepository
import dev.tseki.jellyfinradio.domain.ScannedEpisode
import dev.tseki.jellyfinradio.domain.Ticks
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
/** 追加専用・べき等な取り込み。番組は (放送局, 番組名)、各回は path をキーにする。 */
@Singleton
class RoomLocalImportRepository @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val clock: Clock,
) : LocalImportRepository {
    override suspend fun import(scanned: List<ScannedEpisode>): ImportResult = db.withTransaction {
        var addedPrograms = 0
        var addedEpisodes = 0
        var skipped = 0
        val programIds = HashMap<Pair<String, String>, Long>()
        for (item in scanned) {
            if (db.localFileDao().findByPath(item.path) != null) {
                skipped++
                continue
            }
            val programId = programIds.getOrPut(item.stationName to item.programName) {
                db.programDao().findByStationAndName(item.stationName, item.programName)?.id
                    ?: db.programDao().insert(
                        ProgramEntity(serverItemId = null, name = item.programName, stationName = item.stationName),
                    ).also { addedPrograms++ }
            }
            val episodeId = db.episodeDao().insert(
                EpisodeEntity(
                    serverItemId = null,
                    programId = programId,
                    title = item.title,
                    airedAt = item.airedAt,
                    addedAt = null,
                    runtimeTicks = Ticks.fromDuration(item.runtime),
                    sizeBytes = item.sizeBytes,
                    container = item.container,
                ),
            )
            db.localFileDao().upsert(
                LocalFileEntity(
                    episodeId = episodeId,
                    state = DownloadState.DONE,
                    path = item.path,
                    pinned = true,
                    downloadedAt = clock.now(),
                ),
            )
            addedEpisodes++
        }
        ImportResult(addedPrograms, addedEpisodes, skipped)
    }
}
