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
import ai.zeroclaw.android.ui.AgentResultsScreen
import ai.zeroclaw.android.ui.AgentsScreen
import ai.zeroclaw.android.ui.AiToolsScreen
import ai.zeroclaw.android.ui.ApiKeysScreen
import ai.zeroclaw.android.ui.HomeScreen
import ai.zeroclaw.android.ui.InfoScreen
import ai.zeroclaw.android.ui.SettingsScreen
import ai.zeroclaw.android.ui.ChatScreen
import ai.zeroclaw.android.ui.ToolPlaygroundScreen
import ai.zeroclaw.android.ui.WhatsAppNativeScreen
import ai.zeroclaw.android.ui.theme.ZeroClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Widget / notification deep-link: navigate_to = "agents" | "home" | "agent_results"
        // For "agent_results", agent_id extra is also required.
        val navigateTo = intent?.getStringExtra("navigate_to")
        val agentId = intent?.getStringExtra("agent_id")
        val initialRoute = when {
            navigateTo == "agent_results" && !agentId.isNullOrBlank() -> "agent_results/$agentId"
            else -> navigateTo
        }

        setContent {
            val themeMode by ThemeManager.themeFlow(this)
                .collectAsState(initial = ThemeManager.ThemeMode.SYSTEM)
            ZeroClawTheme(themeMode = themeMode.name.lowercase()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZeroClawNavHost(initialRoute = initialRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Handle widget taps when activity is already running
        // The NavHost startDestination is set on create; subsequent intents
        // would need navController access. For simplicity, re-create works fine
        // since FLAG_ACTIVITY_CLEAR_TOP restarts the activity.
    }
}

@Composable
fun ZeroClawNavHost(initialRoute: String? = null) {
    val navController = rememberNavController()

    // Deep-link from widget: navigate after NavHost is ready
    androidx.compose.runtime.LaunchedEffect(initialRoute) {
        if (initialRoute != null && initialRoute != "home") {
            navController.navigate(initialRoute)
        }
    }

    NavHost(navController = navController, startDestination = "home") {

        composable("home") {
            HomeScreen(
                onNavigateToSettings   = { navController.navigate("settings") },
                onNavigateToInfo       = { navController.navigate("info") },
                onNavigateToPlayground = { navController.navigate("tool_playground") },
                onNavigateToAgents     = { navController.navigate("agents") },
                onNavigateToChat       = { navController.navigate("chat") }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack              = { navController.popBackStack() },
                onNavigateToApiKeys = { navController.navigate("api_keys") },
                onNavigateToAiTools = { navController.navigate("ai_tools") },
                onNavigateToWhatsAppNative = { navController.navigate("whatsapp_native") },
                onNavigateToInfo    = { sectionId -> navController.navigate("info/$sectionId") }
            )
        }

        composable("whatsapp_native") {
            WhatsAppNativeScreen(onBack = { navController.popBackStack() })
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
            AgentsScreen(
                onBack = { navController.popBackStack() },
                onViewResults = { agentId -> navController.navigate("agent_results/$agentId") }
            )
        }

        composable("agent_results/{agentId}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("agentId") ?: ""
            AgentResultsScreen(agentId = id, onBack = { navController.popBackStack() })
        }

        composable("chat") {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAgents = { navController.navigate("agents") }
            )
        }
    }
}
