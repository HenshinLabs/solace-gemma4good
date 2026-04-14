package com.masterllm.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
    const val HOME = "home"
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
    HOME(Routes.HOME, Icons.Default.Home, "Home"),
    CHAT(Routes.CHAT, Icons.Default.Person, "Chat"),
    MARKETPLACE(Routes.MARKETPLACE, Icons.Default.Search, "Explore"),
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
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Top-level tabs ──────────────────────────────────
            composable(Routes.HOME) {
                HomeHubScreen(
                    modifier = Modifier.fillMaxSize(),
                    onOpenChat = {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenRoleplay = {
                        navController.navigate(Routes.ROLEPLAY)
                    },
                    onOpenImageGen = {
                        navController.navigate(Routes.IMAGE_GEN)
                    },
                )
            }
            composable(Routes.CHAT) {
                ChatScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.MARKETPLACE) {
                MarketplaceScreen(modifier = Modifier.fillMaxSize())
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
            composable(Routes.ROLEPLAY) {
                RoleplayScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun HomeHubScreen(
    onOpenChat: () -> Unit,
    onOpenRoleplay: () -> Unit,
    onOpenImageGen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Create with local AI",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Continue your chat session, open roleplay, or generate images from one place.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            HomeQuickActionCard(
                title = "Chat",
                subtitle = "Pick up your existing conversation session",
                icon = Icons.Default.Home,
                onClick = onOpenChat,
            )
        }

        item {
            HomeQuickActionCard(
                title = "Roleplay",
                subtitle = "Open immersive character sessions",
                icon = Icons.Default.Person,
                onClick = onOpenRoleplay,
            )
        }

        item {
            HomeQuickActionCard(
                title = "Image Generation",
                subtitle = "Run Diffusers models directly on-device",
                icon = Icons.Default.Search,
                onClick = onOpenImageGen,
            )
        }
    }
}

@Composable
private fun HomeQuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.KeyboardArrowRight, contentDescription = null) },
        )
    }
}
