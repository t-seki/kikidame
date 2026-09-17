package dev.tseki.jellyfinradio.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.download.DownloadScheduler
import dev.tseki.jellyfinradio.ui.connect.ConnectScreen
import dev.tseki.jellyfinradio.ui.episodes.EpisodeListScreen
import dev.tseki.jellyfinradio.ui.library.LibraryPickScreen
import dev.tseki.jellyfinradio.ui.player.PlayerScreen
import dev.tseki.jellyfinradio.ui.programs.ProgramListScreen
import dev.tseki.jellyfinradio.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
object ConnectRoute

@Serializable
object LibraryPickRoute

@Serializable
object SettingsRoute

@Serializable
object ProgramListRoute

@Serializable
data class EpisodeListRoute(val programId: Long)

@Serializable
data class PlayerRoute(val episodeId: Long)

@HiltViewModel
class SessionViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    scheduler: DownloadScheduler,
) : ViewModel() {
    val state: StateFlow<SessionState?> = sessionRepository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    init {
        // 起動時に PENDING が残っていれば Worker を起こす（kill 後の再開）。失敗しても起動は妨げない
        viewModelScope.launch { runCatching { scheduler.kick() }.onFailure { Log.w("SessionViewModel", "kick failed", it) } }
    }
}

/**
 * 番組一覧 → 各回一覧 → 再生画面 の 3 階層に、接続画面・ライブラリ選択・設定を足したもの。
 * 起動時はセッション状態で開始画面を決め、その後は状態の**変化**（ログアウト・401・ライブラリ選択）で遷移する。
 * デバッグ用に接続画面から番組一覧へ抜けても、状態が変わらない限り戻されない。
 */
@Composable
fun JellyfinRadioNavHost(sessionViewModel: SessionViewModel = hiltViewModel()) {
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val initial = session ?: return // DataStore を読むまで何も出さない（一瞬）
    val navController = rememberNavController()
    val startDestination = remember { initial.startDestination() }

    LaunchedEffect(session) {
        navController.followSessionChange(session ?: return@LaunchedEffect)
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<ConnectRoute> {
            ConnectScreen(onBrowseLocalOnly = { navController.navigate(ProgramListRoute) { popUpTo(0) } })
        }
        composable<LibraryPickRoute> {
            val fromSettings = navController.previousBackStackEntry != null
            LibraryPickScreen(
                onDone = {
                    if (fromSettings) navController.popIfNotRoot() else navController.navigate(ProgramListRoute) { popUpTo(0) }
                },
                onBack = if (fromSettings) navController::popIfNotRoot else null,
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = navController::popIfNotRoot,
                onChangeLibrary = { navController.navigate(LibraryPickRoute) },
            )
        }
        composable<ProgramListRoute> {
            ProgramListScreen(
                onProgramClick = { navController.navigate(EpisodeListRoute(it.value)) },
                onSettingsClick = { navController.navigate(SettingsRoute) },
            )
        }
        composable<EpisodeListRoute> {
            EpisodeListScreen(
                onEpisodeClick = { navController.navigate(PlayerRoute(it.value)) },
                onBack = navController::popIfNotRoot,
            )
        }
        composable<PlayerRoute> {
            PlayerScreen(onBack = navController::popIfNotRoot)
        }
    }
}

/**
 * 戻り先があるときだけポップする。← の連打などで唯一の画面までポップするとバックスタックが空になり、
 * NavHost が何も描かない（真っ暗な画面）まま Activity が残る。
 */
private fun NavHostController.popIfNotRoot() {
    if (previousBackStackEntry != null) popBackStack()
}

private fun SessionState.startDestination(): Any = when (this) {
    is SessionState.SignedOut -> ConnectRoute
    is SessionState.NeedsLibrary -> LibraryPickRoute
    is SessionState.Ready -> ProgramListRoute
}

/** セッション状態が変わったときだけ呼ぶ。今いる画面と食い違う場合にスタックを作り直す。 */
private fun NavHostController.followSessionChange(state: SessionState) {
    val current = currentBackStackEntry?.destination?.route ?: return
    val onAuthScreen = current.endsWith("ConnectRoute") || current.endsWith("LibraryPickRoute")
    when (state) {
        is SessionState.SignedOut -> if (!current.endsWith("ConnectRoute")) navigate(ConnectRoute) { popUpTo(0) }
        is SessionState.NeedsLibrary -> if (!current.endsWith("LibraryPickRoute")) navigate(LibraryPickRoute) { popUpTo(0) }
        is SessionState.Ready -> if (onAuthScreen && previousBackStackEntry == null) navigate(ProgramListRoute) { popUpTo(0) }
    }
}
