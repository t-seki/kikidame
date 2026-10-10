package dev.tseki.kikidame.ui

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
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.ui.connect.ConnectScreen
import dev.tseki.kikidame.ui.connect.SmbConnectScreen
import dev.tseki.kikidame.ui.source.SourcePickScreen
import dev.tseki.kikidame.ui.episodes.EpisodeDetailsScreen
import dev.tseki.kikidame.ui.episodes.EpisodeListScreen
import dev.tseki.kikidame.ui.library.LibraryPickScreen
import dev.tseki.kikidame.ui.player.PlayerScreen
import dev.tseki.kikidame.ui.programs.ProgramListScreen
import dev.tseki.kikidame.ui.settings.LicensesScreen
import dev.tseki.kikidame.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** 初回の取得元の選択（#198）。 */
@Serializable
object SourcePickRoute

/** Jellyfin への接続。 */
@Serializable
object ConnectRoute

/** NAS の共有フォルダ（SMB）への接続（#198）。 */
@Serializable
object SmbConnectRoute

@Serializable
object LibraryPickRoute

@Serializable
object SettingsRoute
@Serializable
object LicensesRoute

@Serializable
object ProgramListRoute

@Serializable
data class EpisodeListRoute(val programId: Long)
/** 各回の詳細（#43）。番組の各回一覧と「続きから」タブ（#151）の長押しから開く。番組から引くので programId も持つ。 */
@Serializable
data class EpisodeDetailsRoute(val programId: Long, val episodeId: Long)

/** [play] が false なら再生を始めない（ミニプレイヤーから「見に行く」だけの遷移）。載っている回は触らず、載っていなければ積むだけ。 */
@Serializable
data class PlayerRoute(val episodeId: Long, val play: Boolean = true)

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
 * 番組の画面（根。「続きから・よく聴く・番組一覧」のタブ、#151）→ 各回一覧 → 再生画面 の 3 階層に、取得元の選択（#198）・Jellyfin の接続画面・SMB の接続画面（#198）・ライブラリ選択・設定・各回の詳細を足したもの。
 * 「続きから」タブの行からは、各回一覧を経ずに再生画面と各回の詳細へ直接行く。
 * 起動時はセッション状態で開始画面を決め、その後は状態の**変化**（ログアウト・401・ライブラリ選択）で遷移する。
 * デバッグ用に接続画面から番組一覧へ抜けても、状態が変わらない限り戻されない。
 */
@Composable
fun KikidameNavHost(sessionViewModel: SessionViewModel = hiltViewModel()) {
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val initial = session ?: return // DataStore を読むまで何も出さない（一瞬）
    val navController = rememberNavController()
    val startDestination = remember { initial.startDestination() }

    LaunchedEffect(session) {
        navController.followSessionChange(session ?: return@LaunchedEffect)
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<SourcePickRoute> {
            SourcePickScreen(
                onPickJellyfin = { navController.navigate(ConnectRoute) },
                onPickSmb = { navController.navigate(SmbConnectRoute) },
            )
        }
        composable<ConnectRoute> {
            ConnectScreen(
                onBack = if (navController.previousBackStackEntry != null) navController::popIfNotRoot else null,
                onSourceChanged = { navController.navigate(SourcePickRoute) { popUpTo(0) } },
            )
        }
        composable<SmbConnectRoute> {
            SmbConnectScreen(
                onBack = if (navController.previousBackStackEntry != null) navController::popIfNotRoot else null,
                onSourceChanged = { navController.navigate(SourcePickRoute) { popUpTo(0) } },
            )
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
                onOpenLicenses = { navController.navigate(LicensesRoute) },
            )
        }
        composable<LicensesRoute> {
            LicensesScreen(onBack = navController::popIfNotRoot)
        }
        composable<ProgramListRoute> {
            ProgramListScreen(
                onProgramClick = { navController.navigate(EpisodeListRoute(it.value)) },
                onSettingsClick = { navController.navigate(SettingsRoute) },
                onNowPlayingClick = { navController.navigate(PlayerRoute(it.value, play = false)) },
                // 「続きから」タブ（#151）の行は各回一覧の行と同じ行き先
                onEpisodeClick = { navController.navigate(PlayerRoute(it.value)) },
                onEpisodeDetails = { programId, episodeId -> navController.navigate(EpisodeDetailsRoute(programId.value, episodeId.value)) },
            )
        }
        composable<EpisodeListRoute> {
            EpisodeListScreen(
                onEpisodeClick = { navController.navigate(PlayerRoute(it.value)) },
                onNowPlayingClick = { navController.navigate(PlayerRoute(it.value, play = false)) },
                onEpisodeDetails = { programId, episodeId -> navController.navigate(EpisodeDetailsRoute(programId.value, episodeId.value)) },
                onBack = navController::popIfNotRoot,
            )
        }
        composable<EpisodeDetailsRoute> {
            EpisodeDetailsScreen(onBack = navController::popIfNotRoot)
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
    is SessionState.SignedOut -> signedOutDestination()
    is SessionState.NeedsLibrary -> LibraryPickRoute
    is SessionState.Ready -> ProgramListRoute
}

/** ログアウト中の行き先: 直前に使っていた取得元の接続画面。一度も接続していなければ取得元の選択。 */
private fun SessionState.SignedOut.signedOutDestination(): Any = when {
    lastSmb != null -> SmbConnectRoute
    lastServerUrl != null -> ConnectRoute
    else -> SourcePickRoute
}

/** 取得元を選んで接続する画面（まだ番組一覧に入っていない）。 */
private val SIGN_IN_ROUTES = listOf("SourcePickRoute", "ConnectRoute", "SmbConnectRoute")

/** セッション状態が変わったときだけ呼ぶ。今いる画面と食い違う場合にスタックを作り直す。 */
private fun NavHostController.followSessionChange(state: SessionState) {
    val current = currentBackStackEntry?.destination?.route ?: return
    // route は完全修飾名なので末尾で比べる（"SmbConnectRoute" は "ConnectRoute" で終わらないが、念のため完全一致にする）
    val name = current.substringAfterLast('.')
    val onSignInScreen = name in SIGN_IN_ROUTES
    when (state) {
        // 取得元を選んでいる途中の画面にいる間は動かさない
        is SessionState.SignedOut -> if (!onSignInScreen) navigate(state.signedOutDestination()) { popUpTo(0) }
        is SessionState.NeedsLibrary -> if (name != "LibraryPickRoute") navigate(LibraryPickRoute) { popUpTo(0) }
        is SessionState.Ready ->
            if (onSignInScreen || (name == "LibraryPickRoute" && previousBackStackEntry == null)) navigate(ProgramListRoute) { popUpTo(0) }
    }
}
