package dev.tseki.kikidame.domain

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** サーバに対する現在の接続状態。1 サーバ・1 ユーザー・1 ライブラリ。 */
sealed interface SessionState {
    /** 一度も接続していない、またはログアウト済み。手元のデータは残っている。 */
    data class SignedOut(val lastServerUrl: String?, val lastUserName: String?) : SessionState

    /** ログイン済みだがライブラリ未選択。 */
    data class NeedsLibrary(val session: Session) : SessionState

    /**
     * [lastFetchedAt] は最後に全走査が成功した時刻、[lastAttemptedAt] は最後に全走査を試みた時刻（成功・失敗を問わない。#135）。
     * 起動時同期は両方の新しい方から間をあける。
     */
    data class Ready(
        val session: Session,
        val library: SelectedLibrary,
        val lastFetchedAt: Instant?,
        val lastAttemptedAt: Instant? = null,
    ) : SessionState
}

data class Session(
    val serverUrl: String,
    val userName: String,
    val userId: String,
    val accessToken: String,
)

data class SelectedLibrary(val id: ServerItemId, val name: String)

/** サーバ上のライブラリ（`/UserViews` の 1 件）。音楽ライブラリだけが選べる。 */
data class LibraryView(
    val id: ServerItemId,
    val name: String,
    /** 表示用の種別名（"music" / "movies" など）。不明なら null。 */
    val collectionType: String?,
    val isMusic: Boolean,
)

/** サーバとのやり取りの失敗。UI はこれで文言を決める。 */
sealed class ServerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 認証情報が無効（401）。 */
    class Unauthorized(cause: Throwable? = null) : ServerException("unauthorized", cause)

    /** 到達できない（DNS・接続・タイムアウト）。 */
    class Unreachable(cause: Throwable? = null) : ServerException("unreachable", cause)

    /** それ以外（5xx、想定外のレスポンス）。 */
    class Failed(message: String, cause: Throwable? = null) : ServerException(message, cause)
}

interface SessionRepository {
    val state: Flow<SessionState>

    /** ログインしてセッションを保存する。失敗は [ServerException]。 */
    suspend fun signIn(serverUrl: String, userName: String, password: String)

    suspend fun listLibraries(): List<LibraryView>

    suspend fun selectLibrary(library: LibraryView)

    /** 認証情報だけを消す。手元のデータは残す。 */
    suspend fun signOut()
}

/**
 * サーバの一覧を取り込み、同期する。
 * 全走査は同期そのもの（ADR 0004）、番組単位の取得は取り込みだけで削除しない。
 */
interface LibraryRefreshRepository {
    /**
     * ライブラリ全体を取得し、突合して Room に取り込み、[SyncPlanner] の結論を実行する
     * （保持ルールによる削除、サーバから消えた各回の除去、ダウンロードの予約）。
     * [excluded] の各回（再生中の回）は今回は削除しない。
     * 401 は [ServerException.Unauthorized] を投げる（呼び出し側がログアウトへ導く）。
     * サーバに問い合わせる前に試みた時刻（[SessionState.Ready.lastAttemptedAt]）を記録する。失敗しても残る。
     */
    suspend fun refresh(excluded: Set<EpisodeId> = emptySet()): RefreshResult

    /**
     * 1 番組の各回だけを取得して取り込む（#12）。削除も予約もせず、最終同期の時刻も更新しない。
     * 番組がサーバ ID を持たなければ null（呼び出し側は全体の [refresh] にフォールバックする）。
     */
    suspend fun refreshProgram(programId: ProgramId): RefreshResult?

    /**
     * 1 番組だけ同期する。番組の存在を確かめてから（無ければ消失 = 判断保留）その番組の各回一覧を取り、
     * 取り込み → [SyncPlanner] → 除去・削除・予約をこの番組に限って行う。その番組についての一覧は完全なので
     * 削除の権限を持つ（ADR 0004）。最終同期の時刻は更新しない。サーバ ID を持たなければ null。
     */
    suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId> = emptySet()): RefreshResult?
}

data class RefreshResult(
    val programs: Int,
    val episodes: Int,
    val linkedPrograms: Int,
    val linkedEpisodes: Int,
    val fetchedAt: Instant,
    /** 同期で予約したダウンロードの数。 */
    val enqueued: Int = 0,
    /** 保持ルールで消したファイルの数。 */
    val deleted: Int = 0,
    /** サーバの一覧から消えたため除去した各回の数。 */
    val removed: Int = 0,
    /** 判断保留になった番組の数。 */
    val onHold: Int = 0,
    /** 取り込みで新しく手元に増えた各回の数（同期対象でない番組の回も数える）。突合で結び直した既存の回は数えない（#142）。 */
    val newEpisodes: Int = 0,
) {
    /** 手元に変化（新しい回・ダウンロード予約・削除）があったか。判断保留は変化に数えない（#142）。 */
    val hasChanges: Boolean get() = newEpisodes > 0 || enqueued > 0 || deleted + removed > 0
}

/** 「別のサーバに接続」。手元の番組・各回・再生位置・セッションを全部消し、音声ファイルも消す（#50）。 */
interface LocalDataReset {
    suspend fun resetAll()
}
