package com.masterllm.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.masterllm.feature.auth.AuthScreen
import com.masterllm.feature.chat.ChatScreen
import com.masterllm.feature.image.gen.ImageGenScreen
import com.masterllm.feature.marketplace.MarketplaceScreen
import com.masterllm.feature.model.manager.ModelManagerScreen
import com.masterllm.feature.roleplay.RoleplayScreen
import com.masterllm.feature.settings.SettingsScreen

/** Route identifiers for each top-level destination. */
object Routes {
    const val CHAT = "chat"
    const val MARKETPLACE = "marketplace"
    const val ROLEPLAY = "roleplay"
    const val IMAGE_GEN = "image_gen"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val MODEL_MANAGER = "model_manager"
}

/** Bottom navigation tab definitions. */
enum class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val label: String,
) {
    CHAT(Routes.CHAT, Icons.Default.Home, "Chat"),
    MARKETPLACE(Routes.MARKETPLACE, Icons.Default.Search, "Explore"),
    ROLEPLAY(Routes.ROLEPLAY, Icons.Default.Person, "Roleplay"),
    SETTINGS(Routes.SETTINGS, Icons.Default.Settings, "Settings"),
}

/**
 * The main app shell with bottom navigation and a [NavHost] that hosts
 * every feature screen in the application.
 */
@Composable
fun MasterLLMApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Show bottom bar only on top-level destinations
    val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in topLevelRoutes

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == dest.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    // Pop up to the start destination to avoid building up
                                    // a large back-stack of top-level destinations
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(dest.icon, contentDescription = dest.label)
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Top-level tabs ──────────────────────────────────
            composable(Routes.CHAT) {
                ChatScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.MARKETPLACE) {
                MarketplaceScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.ROLEPLAY) {
                RoleplayScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    modifier = Modifier.fillMaxSize(),
                    onOpenAuth = { navController.navigate(Routes.AUTH) },
                    onOpenModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
                    onOpenImageGen = { navController.navigate(Routes.IMAGE_GEN) },
                )
            }

            // ── Secondary destinations ──────────────────────────
            composable(Routes.IMAGE_GEN) {
                ImageGenScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.AUTH) {
                AuthScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.MODEL_MANAGER) {
                ModelManagerScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
