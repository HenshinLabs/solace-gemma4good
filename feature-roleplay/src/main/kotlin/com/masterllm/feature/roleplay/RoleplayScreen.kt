package com.masterllm.feature.roleplay

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.Message
import com.masterllm.core.domain.model.MessageRole
import com.masterllm.core.domain.model.VisualStyle
import com.masterllm.core.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleplayScreen(
    modifier: Modifier = Modifier,
    viewModel: RoleplayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Setup dialog
    if (state.showSetupDialog) {
        RoleplaySetupDialog(state, viewModel::onAction)
    }

    AnimatedContent(
        targetState = state.showSessionList,
        label = "rp_nav",
        modifier = modifier,
    ) { showList ->
        if (showList) {
            SessionListPane(state, viewModel::onAction)
        } else {
            RoleplayChatPane(state, viewModel::onAction)
        }
    }
}

// ─── Session List ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListPane(
    state: RoleplayUiState,
    onAction: (RoleplayAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎭 Roleplay") },
                actions = {
                    IconButton(onClick = { onAction(RoleplayAction.ShowSetup) }) {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                },
            )
        },
    ) { padding ->
        if (state.sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Default.TheaterComedy,
                title = "No roleplay sessions",
                subtitle = "Create a character and start an adventure",
                actionLabel = "Create Session",
                onAction = { onAction(RoleplayAction.ShowSetup) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(RoleplayAction.SelectSession(session.id)) },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f),
                                        )
                                    )
                                )
                                .padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "${session.genre} · ${session.aiCharacterName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                                IconButton(onClick = { onAction(RoleplayAction.DeleteSession(session.id)) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (session.premise.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = session.premise,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── RP Chat Pane ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleplayChatPane(
    state: RoleplayUiState,
    onAction: (RoleplayAction) -> Unit,
) {
    val listState = rememberLazyListState()
    val session = state.currentSession
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val selectedModel = remember(state.selectedModelId, state.availableModels) {
        state.availableModels.firstOrNull { it.id == state.selectedModelId }
    }

    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + (if (state.streamingText.isNotEmpty()) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onAction(RoleplayAction.BackToList) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = session?.title ?: "Roleplay",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (session != null) {
                            Text(
                                text = "Playing with ${session.aiCharacterName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        selectedModel?.let { model ->
                            Text(
                                text = "Model: ${model.displayName.ifBlank { model.repoId }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.availableModels.isNotEmpty()) {
                        IconButton(onClick = { modelMenuExpanded = true }) {
                            Icon(Icons.Default.ModelTraining, contentDescription = "Select model")
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            state.availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.displayName.ifBlank { model.repoId })
                                            if (model.quantization.isNotBlank()) {
                                                Text(
                                                    text = model.quantization,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onAction(RoleplayAction.SelectModel(model.id))
                                        modelMenuExpanded = false
                                    },
                                    trailingIcon = {
                                        if (state.selectedModelId == model.id) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            RoleplayInputBar(
                text = state.inputText,
                isGenerating = state.isGenerating,
                userName = session?.userCharacterName ?: "You",
                onTextChange = { onAction(RoleplayAction.InputChanged(it)) },
                onSend = { onAction(RoleplayAction.SendMessage) },
                onStop = { onAction(RoleplayAction.StopGeneration) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.generationStatus?.let { status ->
                item(key = "generation_status") {
                    RoleplayGenerationStatusCard(status)
                }
            }

            items(
                state.messages.filter { it.role != MessageRole.SYSTEM },
                key = { it.id }
            ) { msg ->
                RoleplayBubble(msg, session?.aiCharacterName ?: "AI")
            }

            if (state.streamingText.isNotEmpty()) {
                item(key = "streaming") {
                    RoleplayBubble(
                        message = Message(
                            id = "streaming",
                            role = MessageRole.ASSISTANT,
                            content = state.streamingText,
                            isStreaming = true,
                        ),
                        aiName = session?.aiCharacterName ?: "AI",
                    )
                }
            }

            if (state.isGenerating && state.streamingText.isEmpty()) {
                item(key = "typing") {
                    TypingIndicator(modifier = Modifier.padding(start = 40.dp))
                }
            }

            state.lastGenerationStats?.let { stats ->
                item(key = "roleplay_generation_stats_${stats.generatedAtEpochMs}") {
                    RoleplayGenerationStatsCard(stats)
                }
            }
        }
    }
}

@Composable
private fun RoleplayGenerationStatusCard(status: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RoleplayGenerationStatsCard(stats: RoleplayGenerationStats) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Runtime stats",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Model: ${stats.modelDisplayName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Backend: ${stats.backend} | Threads: ${stats.threadCount} | Context: ${stats.contextSize}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Model load: ${stats.modelLoadDurationMs} ms | Replayed context messages: ${stats.replayedMessages}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Prompt tokens: ${stats.promptTokens} | Output tokens: ${stats.generatedTokens} (${stats.generatedChars} chars)",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "First token: ${stats.firstTokenLatencyMs} ms | Total: ${stats.durationMs} ms",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Decode speed: ${"%.2f".format(stats.decodeTokensPerSecond)} tok/s | Native speed: ${"%.2f".format(stats.nativeTokensPerSecond)} tok/s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RoleplayBubble(message: Message, aiName: String) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = aiName.first().toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            if (!isUser) {
                Text(
                    text = aiName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun RoleplayInputBar(
    text: String,
    isGenerating: Boolean,
    userName: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Writing as $userName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What do you do?") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    enabled = !isGenerating,
                )
                Spacer(Modifier.width(8.dp))
                if (isGenerating) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = text.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

// ─── Setup Dialog ───────────────────────────────────────────────

@Composable
private fun RoleplaySetupDialog(
    state: RoleplayUiState,
    onAction: (RoleplayAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(RoleplayAction.DismissSetup) },
        title = { Text("Create Roleplay Session") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.setupTitle,
                    onValueChange = { onAction(RoleplayAction.SetupTitleChanged(it)) },
                    label = { Text("Session Title*") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.setupGenre,
                    onValueChange = { onAction(RoleplayAction.SetupGenreChanged(it)) },
                    label = { Text("Genre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.setupPremise,
                    onValueChange = { onAction(RoleplayAction.SetupPremiseChanged(it)) },
                    label = { Text("Premise / Setting") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                Text(
                    text = "AI Character",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.setupAiName,
                    onValueChange = { onAction(RoleplayAction.SetupAiNameChanged(it)) },
                    label = { Text("Name*") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.setupAiDescription,
                    onValueChange = { onAction(RoleplayAction.SetupAiDescChanged(it)) },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                Text(
                    text = "Your Character",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.setupUserName,
                    onValueChange = { onAction(RoleplayAction.SetupUserNameChanged(it)) },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.setupUserDescription,
                    onValueChange = { onAction(RoleplayAction.SetupUserDescChanged(it)) },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                state.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAction(RoleplayAction.CreateSession) }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(RoleplayAction.DismissSetup) }) {
                Text("Cancel")
            }
        },
    )
}
