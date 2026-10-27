package dev.tseki.kikidame.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.PlaybackStateEntity
import dev.tseki.kikidame.data.db.SharedPlaybackRow
import dev.tseki.kikidame.data.db.toDomain
import dev.tseki.kikidame.data.db.toEntity
import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.ServerCredentials
import dev.tseki.kikidame.data.jellyfin.credentials
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.PlaybackShareStatus
import dev.tseki.kikidame.domain.PlaybackSharingRules
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SharedPlayState
import dev.tseki.kikidame.domain.SharedPlaybackRecord
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SyncOutcome
import dev.tseki.kikidame.domain.Ticks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * **再生の共有**（ADR 0013）。取得元が Jellyfin のブックのライブラリのときだけ、各回の再生位置と再生済みを UserData と分け合う。
 * 音楽のライブラリ・共有フォルダ・未接続では、どの口も取得元に触らない（読みも書きもしない。決定 1）。
 *
 * - 同期（[reconcile]）: 一覧と一緒に読んだ UserData（[SourceEpisode.shared]）を、各回ごとに衝突の規則（[PlaybackSharingRules]）で
 *   手元と突き合わせ、取得元の値を採るか、手元を送る。聴いている回は手元のまま（決定 5）
 * - 送る（[sendPending]）: 一時停止・停止・回の終わり・再生済みの手動切替の後、未送信の回を送る（再生中の回を除く）
 * - ▶ の直前（[refreshBeforePlay]）: 1 回だけ短い時間で読み、取得元の値を採るなら手元に入れて返す
 *
 * 送るときは手元の位置・再生済みと、`LastPlayedDate` に手元の更新時刻を書き、サーバが返した値を「最後に見た値」にする。
 * 書き込みの失敗は手元の再生を止めない。失敗は [status] に残し、設定の 1 行に出す（決定 8）。未送信の印は残り、次の同期で送る。
 */
@Singleton
class PlaybackSharing @Inject constructor(
    private val db: KikidameDatabase,
    private val gateway: JellyfinGateway,
    private val store: SessionStore,
    private val clock: Clock,
    private val statusStore: PlaybackShareStatusStore,
) {
    private val dao get() = db.playbackStateDao()

    /** 再生の共有の最後の送受信（今のライブラリのものだけ。ほかのライブラリのものや共有しない取得元では null）。 */
    val status: Flow<PlaybackShareStatus?> = statusStore.status

    /** 今の取得元が共有するなら、その認証の情報とライブラリの id。共有しないなら null。 */
    private suspend fun target(): Target? {
        val jellyfin = (store.current() as? SessionState.Ready)?.source as? ConnectedSource.Jellyfin ?: return null
        if (jellyfin.library.kind != LibraryKind.BOOKS) return null
        return Target(jellyfin.session.credentials(), jellyfin.library.id)
    }

    private data class Target(val credentials: ServerCredentials, val libraryId: SourceItemId)

    /**
     * 同期の取り込みの後、保持ルールの同期（`SyncPlanner`）の前に呼ぶ（ほかで再生済みになった回も「再生済みなら削除」の対象にする。決定 6）。
     * [episodes] のうち UserData を持つ回（[SourceEpisode.shared] が null でない。ブックのライブラリの一覧だけ）を手元と突き合わせる。
     * [excluded]（聴いている回）は読みも送りもしない（手元のまま。▶ の直前と一時停止で突き合わせる）。
     * 手元の一覧に無い取得元 ID の値は無視する。送れなかった回は未送信のまま残し、同期は止めない。
     */
    suspend fun reconcile(episodes: List<SourceEpisode>, excluded: Set<EpisodeId>) {
        val shared = episodes.mapNotNull { e -> e.shared?.let { e.sourceId.value to it } }.toMap()
        if (shared.isEmpty()) return
        val target = target() ?: return
        val toSend = ArrayList<Pair<SharedPlaybackRow, SharedPlayState>>()
        db.withTransaction {
            for (row in dao.listShared()) {
                val server = shared[row.sourceItemId] ?: continue
                if (EpisodeId(row.episodeId) in excluded) continue
                when (val decision = PlaybackSharingRules.decide(row.toRecord(), server)) {
                    PlaybackSharingRules.Decision.Keep -> Unit
                    is PlaybackSharingRules.Decision.TakeShared -> take(row, decision.shared)
                    is PlaybackSharingRules.Decision.SendLocal -> toSend += row to decision.state
                }
            }
        }
        val failure = send(target, toSend)
        statusStore.record(target.libraryId, clock.now(), failure)
    }

    /**
     * 未送信の回を送る（一時停止・停止・回の終わり・再生済みの手動切替の後。決定 5）。[except]（再生中の回）は送らない。
     * 共有しない取得元では何もしない。送る前に取得元の値は読まない（送るのは手元でたった今変わった値なので、新しい方を採る規則でも手元になる）。
     * 何かを送ろうとしたら true。
     */
    suspend fun sendPending(rows: List<SharedPlaybackRow>, except: Set<EpisodeId>): Boolean {
        val pending = rows.filter { EpisodeId(it.episodeId) !in except && it.state?.sharePending == true }
        if (pending.isEmpty()) return false
        val target = target() ?: return false
        val failure = send(target, pending.mapNotNull { row -> row.state?.let { row to SharedPlayState.of(it.toDomain()) } })
        statusStore.record(target.libraryId, clock.now(), failure)
        return true
    }

    /** 未送信の回。再生の状態が書かれるたびに流れる（送り手が見張る）。 */
    fun observePending(): Flow<List<SharedPlaybackRow>> = dao.observePending()

    /**
     * ▶ の直前に、取得元の値を 1 回だけ読む（決定 5。一時停止からの再開を含む）。[PlaybackSharingRules.READ_BEFORE_PLAY_TIMEOUT] で打ち切り、
     * 失敗したら null（手元の値で鳴らす）。取得元の値を採ると決まったら、手元に入れて、入れた状態を返す（呼び出し側がその位置へ移る）。
     * 手元を送ると決まったとき・変わっていないときは null（送るのは一時停止の後の [sendPending] と同期に任せる）。
     */
    suspend fun refreshBeforePlay(episodeId: EpisodeId): PlaybackState? {
        val target = target() ?: return null
        val row = dao.findShared(episodeId.value) ?: return null
        val server = try {
            gateway.fetchPlayState(target.credentials, SourceItemId(row.sourceItemId), PlaybackSharingRules.READ_BEFORE_PLAY_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "read before play failed; playing from the local position: ${row.sourceItemId}", e)
            return null
        }
        return db.withTransaction {
            // 読んでいる間に手元が変わっていたら、変わった後の手元で決める
            val current = dao.findShared(episodeId.value) ?: return@withTransaction null
            when (val decision = PlaybackSharingRules.decide(current.toRecord(), server)) {
                is PlaybackSharingRules.Decision.TakeShared -> take(current, decision.shared)
                PlaybackSharingRules.Decision.Keep, is PlaybackSharingRules.Decision.SendLocal -> null
            }
        }
    }

    /** トランザクションの中で呼ぶ。取得元の値を手元に入れ、最後に見た値にする。入れた状態を返す（行を作らなかったら null）。 */
    private suspend fun take(row: SharedPlaybackRow, shared: SharedPlayState): PlaybackState? {
        val existing = row.state?.toDomain()
        val taken = PlaybackSharingRules.taken(EpisodeId(row.episodeId), existing, shared, clock.now()) ?: return null
        dao.upsert(
            taken.toEntity().copy(
                sharePending = false,
                sharedPositionTicks = Ticks.fromDuration(shared.position),
                sharedPlayed = shared.played,
                sharedLastPlayedAt = shared.lastPlayedAt,
            ),
        )
        return taken
    }

    /**
     * [items] を順に送る。送れたら最後に見た値を書き、送った後に手元が変わっていなければ未送信の印を下ろす（[dev.tseki.kikidame.data.db.PlaybackStateDao.markSent]）。
     * 届かない・認証が通らないときは残りも送れないので打ち切る。最初の失敗を返す（全部送れたら null）。
     */
    private suspend fun send(target: Target, items: List<Pair<SharedPlaybackRow, SharedPlayState>>): SyncOutcome.Failure? {
        var failure: SyncOutcome.Failure? = null
        for ((row, state) in items) {
            val written = try {
                gateway.writePlayState(target.credentials, SourceItemId(row.sourceItemId), state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "play state write failed: ${row.sourceItemId}", e)
                failure = failure ?: e.toFailure()
                if (e is SourceException.Unreachable || e is SourceException.Unauthorized) break
                continue
            }
            val sent = state.lastPlayedAt ?: continue
            dao.markSent(
                episodeId = row.episodeId,
                position = Ticks.fromDuration(written.position),
                played = written.played,
                lastPlayedAt = written.lastPlayedAt,
                sentPosition = Ticks.fromDuration(state.position),
                sentPlayed = state.played,
                sentUpdatedAt = sent,
            )
        }
        return failure
    }

    private fun Exception.toFailure(): SyncOutcome.Failure = when (this) {
        is SourceException.Unreachable -> SyncOutcome.Unreachable
        is SourceException -> SyncOutcome.FetchFailed(message.orEmpty())
        else -> SyncOutcome.ImportFailed(this::class.simpleName.orEmpty())
    }

    private companion object {
        const val TAG = "PlaybackSharing"
    }
}

/** [SharedPlaybackRow] を衝突の規則の入力にする。 */
internal fun SharedPlaybackRow.toRecord(): SharedPlaybackRecord = SharedPlaybackRecord(
    local = state?.toDomain(),
    pending = state?.sharePending == true,
    lastSeen = state?.lastSeen(),
)

private fun PlaybackStateEntity.lastSeen(): SharedPlayState? {
    val position = sharedPositionTicks ?: return null
    return SharedPlayState(Ticks.toDuration(position), sharedPlayed == true, sharedLastPlayedAt)
}

/**
 * 再生の共有の最後の送受信（[PlaybackShareStatus]）の置き場（Preferences DataStore、ファイルは `playback_share`）。
 * ライブラリの id と一緒に持ち、今のライブラリと違うものは読まない（別のライブラリに選び直した後に、前の状態を出さない）。
 */
@Singleton
class PlaybackShareStatusStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val store: SessionStore,
) {
    @Inject constructor(@ApplicationContext context: Context, store: SessionStore) :
        this(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("playback_share") }, store)

    val status: Flow<PlaybackShareStatus?> = combine(dataStore.data, store.state) { prefs, session ->
        val jellyfin = (session as? SessionState.Ready)?.source as? ConnectedSource.Jellyfin
        val library = jellyfin?.library?.takeIf { it.kind == LibraryKind.BOOKS } ?: return@combine null
        if (prefs[LIBRARY_ID] != library.id.value) return@combine PlaybackShareStatus(null, null, null)
        PlaybackShareStatus(
            lastSucceededAt = prefs[SUCCEEDED_AT]?.let(Instant::fromEpochMilliseconds),
            failedAt = prefs[FAILED_AT]?.let(Instant::fromEpochMilliseconds),
            failure = prefs.failure(),
        )
    }

    /** 送受信の結果を残す。[failure] が null なら成功。 */
    suspend fun record(libraryId: SourceItemId, at: Instant, failure: SyncOutcome.Failure?) {
        dataStore.edit {
            if (it[LIBRARY_ID] != libraryId.value) {
                it.clear()
                it[LIBRARY_ID] = libraryId.value
            }
            if (failure == null) {
                it[SUCCEEDED_AT] = at.toEpochMilliseconds()
            } else {
                it[FAILED_AT] = at.toEpochMilliseconds()
                when (failure) {
                    SyncOutcome.Unreachable -> {
                        it[FAILURE_KIND] = KIND_UNREACHABLE
                        it.remove(FAILURE_DETAIL)
                    }
                    is SyncOutcome.FetchFailed -> {
                        it[FAILURE_KIND] = KIND_FETCH
                        it[FAILURE_DETAIL] = failure.message
                    }
                    is SyncOutcome.ImportFailed -> {
                        it[FAILURE_KIND] = KIND_IMPORT
                        it[FAILURE_DETAIL] = failure.exceptionName
                    }
                }
            }
        }
    }

    private fun Preferences.failure(): SyncOutcome.Failure? = when (this[FAILURE_KIND]) {
        KIND_UNREACHABLE -> SyncOutcome.Unreachable
        KIND_FETCH -> SyncOutcome.FetchFailed(this[FAILURE_DETAIL].orEmpty())
        KIND_IMPORT -> SyncOutcome.ImportFailed(this[FAILURE_DETAIL].orEmpty())
        else -> null
    }

    private companion object {
        val LIBRARY_ID = stringPreferencesKey("library_id")
        val SUCCEEDED_AT = longPreferencesKey("succeeded_at")
        val FAILED_AT = longPreferencesKey("failed_at")
        val FAILURE_KIND = stringPreferencesKey("failure_kind")
        val FAILURE_DETAIL = stringPreferencesKey("failure_detail")
        const val KIND_UNREACHABLE = "unreachable"
        const val KIND_FETCH = "fetch"
        const val KIND_IMPORT = "import"
    }
}
