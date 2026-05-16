package com.masterllm.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.masterllm.feature.chat.ChatScreen
import com.masterllm.feature.roleplay.RoleplayScreen
import com.masterllm.feature.settings.SettingsScreen
import com.masterllm.app.solace.ModelDownloadScreen

object Routes {
    const val MODEL_DOWNLOAD = "model_download"
    const val HOME = "home"
    const val CHAT = "chat"
    const val ROLEPLAY = "roleplay"
    const val SETTINGS = "settings"
}

enum class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val label: String,
) {
    HOME(Routes.HOME, Icons.Default.Home, "Home"),
    CHAT(Routes.CHAT, Icons.Default.Chat, "Chat"),
    ROLEPLAY(Routes.ROLEPLAY, Icons.Default.SelfImprovement, "Sessions"),
    SETTINGS(Routes.SETTINGS, Icons.Default.Settings, "Settings"),
}

@Composable
fun MasterLLMApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in topLevelRoutes

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    TopLevelDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == dest.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MODEL_DOWNLOAD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.MODEL_DOWNLOAD) {
                ModelDownloadScreen(
                    onModelReady = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.MODEL_DOWNLOAD) { inclusive = true }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable(Routes.HOME) {
                SolaceHomeScreen(
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
                    onOpenSessions = {
                        navController.navigate(Routes.ROLEPLAY) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }

            composable(Routes.CHAT) {
                ChatScreen(modifier = Modifier.fillMaxSize())
            }

            composable(Routes.ROLEPLAY) {
                RoleplayScreen(modifier = Modifier.fillMaxSize())
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SolaceHomeScreen(
    onOpenChat: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero greeting
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Solace",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your compassionate AI companion",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // How are you feeling
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                ) {
                    Text(
                        text = "How are you feeling today?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MoodChip("I need to talk", Modifier.weight(1f), onOpenChat)
                        MoodChip("I'm anxious", Modifier.weight(1f), onOpenChat)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MoodChip("Just checking in", Modifier.weight(1f), onOpenChat)
                        MoodChip("I'm in crisis", Modifier.weight(1f), onOpenChat)
                    }
                }
            }
        }

        // Main action cards
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenChat,
            ) {
                ListItem(
                    headlineContent = {
                        Text("Talk to Solace", fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Text("Open a conversation with your AI companion")
                    },
                    leadingContent = {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                    },
                )
            }
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSessions,
            ) {
                ListItem(
                    headlineContent = {
                        Text("Guided Sessions", fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Text("Therapeutic exercises for anxiety, panic, sleep, and more")
                    },
                    leadingContent = {
                        Icon(Icons.Default.SelfImprovement, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    },
                    trailingContent = {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                    },
                )
            }
        }

        // Crisis helpline
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = "In crisis? You are not alone.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CrisisHelplineButton("988 Suicide & Crisis Lifeline (US)", "988")
                    CrisisHelplineButton("iCall India", "9152987821")
                    CrisisHelplineButton("Vandrevala Foundation", "18602662345")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap a number to call. Your life has value.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoodChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.bodySmall) },
        modifier = modifier,
    )
}

@Composable
private fun CrisisHelplineButton(label: String, phoneNumber: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")))
        },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
    ) {
        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
