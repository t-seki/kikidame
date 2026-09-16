package dev.tseki.jellyfinradio.sync

import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.RefreshResult
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「引っ張って更新」の共通入口。同時に 1 つしか走らせず、結果を文言にして画面へ流す。
 * 401 はログアウトと同じ処理をする（セッション状態が変わり、画面側が接続画面へ導く）。
 */
@Singleton
class LibraryRefresher @Inject constructor(
    private val refreshRepository: LibraryRefreshRepository,
    private val sessionRepository: SessionRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    /** 画面が消えても最後まで走らせたいとき（ライブラリ選択直後の初回取得）。 */
    fun launchRefresh() {
        applicationScope.launch { refresh() }
    }

    suspend fun refresh(): RefreshResult? {
        if (!mutex.tryLock()) return null
        _isRefreshing.value = true
        try {
            val result = refreshRepository.refresh()
            _messages.tryEmit("番組 ${result.programs} / 各回 ${result.episodes} を取得しました")
            return result
        } catch (e: ServerException.Unauthorized) {
            _messages.tryEmit("サーバの認証が切れました。もう一度ログインしてください")
            sessionRepository.signOut()
            return null
        } catch (e: ServerException.Unreachable) {
            _messages.tryEmit("サーバに接続できません。手元の一覧を表示しています")
            return null
        } catch (e: ServerException.Failed) {
            _messages.tryEmit("取得に失敗しました: ${e.message}")
            return null
        } finally {
            _isRefreshing.value = false
            mutex.unlock()
        }
    }
}

/** 接続画面の文言。 */
fun ServerException.toUserMessage(): String = when (this) {
    is ServerException.Unauthorized -> "ユーザー名またはパスワードが違います"
    is ServerException.Unreachable -> "サーバに接続できません。URL とネットワークを確認してください"
    is ServerException.Failed -> "サーバがエラーを返しました: $message"
}
