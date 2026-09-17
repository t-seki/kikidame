package dev.tseki.jellyfinradio.domain

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** サーバに対する現在の接続状態。1 サーバ・1 ユーザー・1 ライブラリ。 */
sealed interface SessionState {
    /** 一度も接続していない、またはログアウト済み。手元のデータは残っている。 */
    data class SignedOut(val lastServerUrl: String?, val lastUserName: String?) : SessionState

    /** ログイン済みだがライブラリ未選択。 */
    data class NeedsLibrary(val session: Session) : SessionState

    data class Ready(val session: Session, val library: SelectedLibrary, val lastFetchedAt: Instant?) : SessionState
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

/** サーバの一覧を取り込む。取得の起点はログイン直後と「引っ張って更新」だけ（自動化は M3）。 */
interface LibraryRefreshRepository {
    /**
     * ライブラリ全体を取得し、突合して Room に取り込む。
     * 401 は [ServerException.Unauthorized] を投げる（呼び出し側がログアウトへ導く）。
     */
    suspend fun refresh(): RefreshResult
}

data class RefreshResult(
    val programs: Int,
    val episodes: Int,
    val linkedPrograms: Int,
    val linkedEpisodes: Int,
    val fetchedAt: Instant,
)

/** 「別のサーバに接続」。手元の番組・各回・再生位置・セッションを全部消す。ファイルは消さない。 */
interface LocalDataReset {
    suspend fun resetAll()
}
