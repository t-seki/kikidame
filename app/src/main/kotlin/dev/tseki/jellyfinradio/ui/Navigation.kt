package dev.tseki.jellyfinradio.ui
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.tseki.jellyfinradio.ui.episodes.EpisodeListScreen
import dev.tseki.jellyfinradio.ui.player.PlayerScreen
import dev.tseki.jellyfinradio.ui.programs.ProgramListScreen
import kotlinx.serialization.Serializable
@Serializable
object ProgramListRoute
@Serializable
data class EpisodeListRoute(val programId: Long)
@Serializable
data class PlayerRoute(val episodeId: Long)
/** 番組一覧 → 各回一覧 → 再生画面 の 3 階層。 */
@Composable
fun JellyfinRadioNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ProgramListRoute) {
        composable<ProgramListRoute> {
            ProgramListScreen(onProgramClick = { navController.navigate(EpisodeListRoute(it.value)) })
        }
        composable<EpisodeListRoute> {
            EpisodeListScreen(
                onEpisodeClick = { navController.navigate(PlayerRoute(it.value)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<PlayerRoute> {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
