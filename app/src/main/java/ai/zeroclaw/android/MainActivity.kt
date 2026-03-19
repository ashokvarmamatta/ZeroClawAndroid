package ai.zeroclaw.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.zeroclaw.android.data.ThemeManager
import ai.zeroclaw.android.ui.AgentsScreen
import ai.zeroclaw.android.ui.AiToolsScreen
import ai.zeroclaw.android.ui.ApiKeysScreen
import ai.zeroclaw.android.ui.HomeScreen
import ai.zeroclaw.android.ui.InfoScreen
import ai.zeroclaw.android.ui.SettingsScreen
import ai.zeroclaw.android.ui.ToolPlaygroundScreen
import ai.zeroclaw.android.ui.theme.ZeroClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by ThemeManager.themeFlow(this)
                .collectAsState(initial = ThemeManager.ThemeMode.SYSTEM)
            ZeroClawTheme(themeMode = themeMode.name.lowercase()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZeroClawNavHost()
                }
            }
        }
    }
}

@Composable
fun ZeroClawNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {

        composable("home") {
            HomeScreen(
                onNavigateToSettings   = { navController.navigate("settings") },
                onNavigateToInfo       = { navController.navigate("info") },
                onNavigateToPlayground = { navController.navigate("tool_playground") },
                onNavigateToAgents     = { navController.navigate("agents") }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack              = { navController.popBackStack() },
                onNavigateToApiKeys = { navController.navigate("api_keys") },
                onNavigateToAiTools = { navController.navigate("ai_tools") },
                onNavigateToInfo    = { sectionId -> navController.navigate("info/$sectionId") }
            )
        }

        composable("ai_tools") {
            AiToolsScreen(
                onBack            = { navController.popBackStack() },
                onNavigateToInfo  = { sectionId -> navController.navigate("info/$sectionId") }
            )
        }

        composable("info") {
            InfoScreen(onBack = { navController.popBackStack() })
        }

        composable("info/{sectionId}") { backStackEntry ->
            InfoScreen(
                onBack = { navController.popBackStack() },
                startSectionId = backStackEntry.arguments?.getString("sectionId")
            )
        }

        composable("api_keys") {
            ApiKeysScreen(onBack = { navController.popBackStack() })
        }

        composable("tool_playground") {
            ToolPlaygroundScreen(onBack = { navController.popBackStack() })
        }

        composable("agents") {
            AgentsScreen(onBack = { navController.popBackStack() })
        }
    }
}
