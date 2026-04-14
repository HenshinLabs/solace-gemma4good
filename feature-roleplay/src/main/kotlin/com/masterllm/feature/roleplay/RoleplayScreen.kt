package com.masterllm.feature.roleplay

import androidx.compose.animation.*
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

    if (state.showModelConfig) {
        RoleplayModelConfigurationDialog(state, viewModel::onAction)
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
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val selectedModel = remember(state.selectedModelId, state.availableModels) {
        state.availableModels.firstOrNull { it.id == state.selectedModelId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🎭 Roleplay")
                        Text(
                            text = selectedModel?.displayName?.ifBlank { selectedModel.repoId }
                                ?: "No model selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                                            Text(
                                                text = model.format.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            )
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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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
                    IconButton(onClick = { onAction(RoleplayAction.ShowModelConfig) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Model settings")
                    }
                    IconButton(onClick = { onAction(RoleplayAction.RefreshModelRuntime) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh model runtime")
                    }
                    IconButton(
                        onClick = { onAction(RoleplayAction.GenerateSceneImage) },
                        enabled = !state.isGenerating,
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Generate scene image")
                    }
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
                                            Text(
                                                text = if (model.quantization.isNotBlank()) {
                                                    "${model.format.name} • ${model.quantization}"
                                                } else {
                                                    model.format.name
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            )
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
        snackbarHost = {
            state.error?.let { error ->
                Snackbar(
                    action = {
                        TextButton(onClick = { onAction(RoleplayAction.DismissError) }) {
                            Text("Dismiss")
                        }
                    },
                ) {
                    Text(error)
                }
            }
        },
        bottomBar = {
            RoleplayInputBar(
                text = state.inputText,
                isGenerating = state.isGenerating,
                userName = session?.userCharacterName ?: "You",
                onTextChange = { onAction(RoleplayAction.InputChanged(it)) },
                onSend = { onAction(RoleplayAction.SendMessage) },
                onStop = { onAction(RoleplayAction.StopGeneration) },
                onGenerateImage = { onAction(RoleplayAction.GenerateSceneImage) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Story") },
                    icon = { Icon(Icons.Default.AutoStories, contentDescription = null) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Analytics") },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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
                }
            } else {
                RoleplayAnalyticsPane(state = state)
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
private fun RoleplayAnalyticsPane(state: RoleplayUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "roleplay_runtime") {
            RoleplayModelRuntimeCard(runtime = state.modelRuntime)
        }

        state.lastGenerationStats?.let { stats ->
            item(key = "roleplay_generation_stats_${stats.generatedAtEpochMs}") {
                RoleplayGenerationStatsCard(stats)
            }
        } ?: item(key = "roleplay_no_generation_stats") {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Run roleplay generation to populate prompt/decode CPU and GPU analytics.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RoleplayModelRuntimeCard(runtime: RoleplayModelRuntimeInfo) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Model Runtime",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Status: ${runtime.statusLabel}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Model: ${runtime.modelDisplayName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Backend: ${runtime.backend}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Threads: ${runtime.threadCount} | GPU layers: ${runtime.gpuLayers} | Context: ${runtime.contextSize}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "GPU acceleration: ${if (runtime.gpuAccelerationEnabled) "enabled" else "disabled"}",
                style = MaterialTheme.typography.bodySmall,
            )
            runtime.loadDurationMs?.let { loadMs ->
                Text(
                    text = "Load time: ${loadMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = runtime.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            runtime.lastError?.let { err ->
                Text(
                    text = "Error: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                text = "Backend: ${stats.backend} | Threads: ${stats.threadCount} | GPU layers: ${stats.gpuLayers} | Context: ${stats.contextSize}",
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
                text = "Prompt speed: ${"%.2f".format(stats.promptTokensPerSecond)} tok/s | Native prompt: ${"%.2f".format(stats.nativePromptTokensPerSecond)} tok/s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Prompt CPU: ${formatPercent(stats.promptCpuUsagePercent)} | Prompt GPU: ${formatPercent(stats.promptGpuUsagePercent)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Decode speed: ${"%.2f".format(stats.decodeTokensPerSecond)} tok/s | Native decode: ${"%.2f".format(stats.nativeTokensPerSecond)} tok/s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Decode CPU: ${formatPercent(stats.decodeCpuUsagePercent)} | Decode GPU: ${formatPercent(stats.decodeGpuUsagePercent)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun formatPercent(value: Double?): String {
    return value?.let { "${"%.1f".format(it)}%" } ?: "N/A"
}

@Composable
private fun RoleplayBubble(message: Message, aiName: String) {
    val isUser = message.role == MessageRole.USER
    val clipboard = LocalClipboardManager.current
    val imageBitmap = remember(message.attachedImagePath) {
        message.attachedImagePath
            ?.takeIf { it.isNotBlank() }
            ?.let { BitmapFactory.decodeFile(it) }
    }

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
                Column(modifier = Modifier.padding(12.dp)) {
                    imageBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Roleplay generated image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        if (message.content.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (!isUser && !message.isStreaming && message.content.isNotBlank()) {
                        MarkdownMessageText(
                            markdown = message.content,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
                            modifier = Modifier.fillMaxWidth(),
                            onLongClick = {
                                clipboard.setText(AnnotatedString(message.content))
                            },
                        )
                    } else {
                        val finalText = message.content.ifBlank {
                            if (message.attachedImagePath != null) "Generated roleplay scene image" else ""
                        }
                        if (finalText.isNotBlank()) {
                            Text(
                                text = finalText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
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
    onGenerateImage: () -> Unit,
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
                FilledTonalIconButton(
                    onClick = onGenerateImage,
                    enabled = !isGenerating,
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Generate image")
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleplayModelConfigurationDialog(
    state: RoleplayUiState,
    onAction: (RoleplayAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(RoleplayAction.HideModelConfig) },
        title = {
            Text(
                text = "Roleplay Model Configuration",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    state.selectedModelInfo?.let { model ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = model.displayName.ifBlank { model.repoId },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Format: ${model.format.name} | Quant: ${model.quantization.ifBlank { "N/A" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Size: ${"%.2f".format(model.sizeBytes / (1024.0 * 1024.0 * 1024.0))} GB",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Runtime state",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Status: ${state.modelRuntime.statusLabel}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Backend: ${state.modelRuntime.backend}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Threads: ${state.modelRuntime.threadCount} | GPU layers: ${state.modelRuntime.gpuLayers}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Context: ${state.modelRuntime.contextSize}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = state.modelRuntime.note,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item {
                    RoleplayParameterSlider(
                        label = "Temperature",
                        value = state.inferenceParams.temperature,
                        onValueChange = { onAction(RoleplayAction.UpdateTemperature(it)) },
                        valueRange = 0.0f..2.0f,
                        steps = 40,
                    )
                }

                item {
                    RoleplayParameterSlider(
                        label = "Top P",
                        value = state.inferenceParams.topP,
                        onValueChange = { onAction(RoleplayAction.UpdateTopP(it)) },
                        valueRange = 0.0f..1.0f,
                        steps = 20,
                    )
                }

                item {
                    RoleplayParameterIntSlider(
                        label = "Top K",
                        value = state.inferenceParams.topK,
                        onValueChange = { onAction(RoleplayAction.UpdateTopK(it)) },
                        valueRange = 1..100,
                    )
                }

                item {
                    RoleplayParameterSlider(
                        label = "Repeat Penalty",
                        value = state.inferenceParams.repeatPenalty,
                        onValueChange = { onAction(RoleplayAction.UpdateRepeatPenalty(it)) },
                        valueRange = 1.0f..2.0f,
                        steps = 20,
                    )
                }

                item {
                    RoleplayParameterIntSlider(
                        label = "Max Tokens",
                        value = state.inferenceParams.maxTokens,
                        onValueChange = { onAction(RoleplayAction.UpdateMaxTokens(it)) },
                        valueRange = 64..8192,
                        steps = 100,
                    )
                }

                item {
                    OutlinedTextField(
                        value = state.inferenceParams.systemPrompt,
                        onValueChange = { onAction(RoleplayAction.UpdateSystemPrompt(it)) },
                        label = { Text("System Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAction(RoleplayAction.HideModelConfig) }) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(RoleplayAction.ResetInferenceParams) }) {
                Text("Reset to Defaults")
            }
        },
    )
}

@Composable
private fun RoleplayParameterSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("${"%.2f".format(value)}", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun RoleplayParameterIntSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    steps: Int = 0,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(valueRange)) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
        )
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

                Text(
                    text = "Image Style",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(VisualStyle.entries, key = { it.name }) { style ->
                        FilterChip(
                            selected = state.setupVisualStyle == style,
                            onClick = { onAction(RoleplayAction.SetupStyleChanged(style)) },
                            label = { Text(style.displayName) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

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
