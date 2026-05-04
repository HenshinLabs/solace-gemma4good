package com.masterllm.feature.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.domain.model.Message
import com.masterllm.core.domain.model.MessageRole
import com.masterllm.core.domain.model.ModelFormat
import com.masterllm.core.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Show model configuration dialog
    if (state.showModelConfig) {
        ModelConfigurationDialog(
            state = state,
            onAction = viewModel::onAction,
        )
    }

    AnimatedContent(
        targetState = state.showConversationList,
        label = "chat_nav",
        modifier = modifier,
    ) { showList ->
        if (showList) {
            ConversationListPane(state, viewModel::onAction)
        } else {
            ChatPane(state, viewModel::onAction)
        }
    }
}

// ─── Conversation List ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListPane(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
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
                        Text("Chats")
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
ModelDropdownItem(
model = model,
isSelected = state.selectedModelId == model.id,
isLoaded = state.modelRuntime.modelId == model.id && state.modelRuntime.status == ModelLoadStatus.LOADED,
onClick = {
onAction(ChatAction.SelectModel(model.id))
modelMenuExpanded = false
},
)
}
}
                    }
                    IconButton(onClick = { onAction(ChatAction.NewConversation) }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                },
            )
        },
    ) { padding ->
        if (state.conversations.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Chat,
                title = "No conversations yet",
                subtitle = "Start a new chat to begin",
                actionLabel = "New Chat",
                onAction = { onAction(ChatAction.NewConversation) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.conversations, key = { it.id }) { convo ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(ChatAction.SelectConversation(convo.id)) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = convo.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${convo.messageCount} messages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                            IconButton(onClick = { onAction(ChatAction.DeleteConversation(convo.id)) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Chat Pane ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPane(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
) {
    val listState = rememberLazyListState()
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val selectedModel = remember(state.selectedModelId, state.availableModels) {
        state.availableModels.firstOrNull { it.id == state.selectedModelId }
    }

    // Auto-scroll on new messages
    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            val targetIndex = state.messages.size + (if (state.streamingText.isNotEmpty()) 1 else 0)
            if (targetIndex > 0) {
                listState.animateScrollToItem(targetIndex - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onAction(ChatAction.BackToList) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.currentConversation?.title ?: "Chat",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                // Model config button
                IconButton(onClick = { onAction(ChatAction.ShowModelConfig) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Model settings")
                }
                IconButton(onClick = { onAction(ChatAction.RefreshModelRuntime) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh model runtime")
                }
                IconButton(
                    onClick = { onAction(ChatAction.RunBenchmark) },
                    enabled = !state.benchmarkRunning && !state.isGenerating,
                ) {
                    Icon(Icons.Default.Speed, contentDescription = "Run benchmark")
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
ModelDropdownItem(
model = model,
isSelected = state.selectedModelId == model.id,
isLoaded = state.modelRuntime.modelId == model.id && state.modelRuntime.status == ModelLoadStatus.LOADED,
onClick = {
onAction(ChatAction.SelectModel(model.id))
modelMenuExpanded = false
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
                        TextButton(onClick = { onAction(ChatAction.DismissError) }) {
                            Text("Dismiss")
                        }
                    },
                ) {
                    Text(error)
                }
            }
        },
        bottomBar = {
            ChatInputBar(
                text = state.inputText,
                isGenerating = state.isGenerating,
                pendingImageAttachment = state.pendingImageAttachment,
                onTextChange = { onAction(ChatAction.InputChanged(it)) },
                onSend = { onAction(ChatAction.SendMessage) },
                onStop = { onAction(ChatAction.StopGeneration) },
                onAttachImage = { onAction(ChatAction.AttachImage(it)) },
                onClearImage = { onAction(ChatAction.ClearImageAttachment) },
                onApplyTaskTemplate = {
                    onAction(
                        ChatAction.ApplyTaskTemplate(
                            systemPrompt = it.systemPrompt,
                            starterPrompt = it.starterPrompt,
                        )
                    )
                },
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
                    text = { Text("Chat") },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.generationStatus?.let { status ->
                        item(key = "generation_status") {
                            GenerationStatusCard(status)
                        }
                    }

                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }

                    // Streaming response
                    if (state.streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            MessageBubble(
                                message = Message(
                                    id = "streaming",
                                    role = MessageRole.ASSISTANT,
                                    content = state.streamingText,
                                    isStreaming = true,
                                )
                            )
                        }
                    }

                    // Typing indicator
                    if (state.isGenerating && state.streamingText.isEmpty()) {
                        item(key = "typing") {
                            Row(
                                modifier = Modifier.padding(start = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TypingIndicator()
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Generating…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            } else {
                ChatAnalyticsPane(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun ChatAnalyticsPane(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "runtime_status") {
            ModelRuntimeCard(
                runtime = state.modelRuntime,
                onRefresh = { onAction(ChatAction.RefreshModelRuntime) },
            )
        }

        if (state.benchmarkRunning) {
            item(key = "benchmark_running") {
                BenchmarkRunningCard()
            }
        }

        state.benchmarkResult?.let { result ->
            item(key = "benchmark_result") {
                BenchmarkResultCard(
                    result = result,
                    onDismiss = { onAction(ChatAction.ClearBenchmarkResult) },
                )
            }
        }

        state.lastGenerationStats?.let { stats ->
            item(key = "generation_stats_${stats.generatedAtEpochMs}") {
                GenerationStatsCard(stats)
            }
        } ?: item(key = "no_generation_stats") {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Run a prompt to populate prompt/decode CPU and GPU analytics.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GenerationStatusCard(status: String) {
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
private fun ModelRuntimeCard(
    runtime: ModelRuntimeInfo,
    onRefresh: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Model Runtime",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            Text(
                text = "Status: ${runtime.status.label()} • Backend: ${runtime.backend}",
                style = MaterialTheme.typography.bodySmall,
                color = when (runtime.status) {
                    ModelLoadStatus.ERROR -> MaterialTheme.colorScheme.error
                    ModelLoadStatus.LOADED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = "Model: ${runtime.modelDisplayName}",
                style = MaterialTheme.typography.bodySmall,
            )
            runtime.modelPath?.let { path ->
                Text(
                    text = "Path: $path",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "Threads: ${runtime.threadCount} • Context: ${runtime.contextSize}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = runtime.offloadSummary,
                style = MaterialTheme.typography.bodySmall,
            )
            runtime.loadDurationMs?.let {
                Text(
                    text = "Load time: ${it} ms",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = runtime.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            runtime.lastError?.let { err ->
                Text(
                    text = "Error: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (runtime.status == ModelLoadStatus.LOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun ModelLoadStatus.label(): String = when (this) {
ModelLoadStatus.IDLE -> "Idle"
ModelLoadStatus.LOADING -> "Loading"
ModelLoadStatus.LOADED -> "Loaded"
ModelLoadStatus.ERROR -> "Error"
}

@Composable
private fun ModelFormatBadge(format: ModelFormat, modifier: Modifier = Modifier) {
val (label, containerColor, contentColor) = when (format) {
ModelFormat.GGUF -> Triple("GGUF", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
ModelFormat.SAFETENSORS -> Triple("ST", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
ModelFormat.DIFFUSERS -> Triple("IMG", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
}
Surface(
shape = RoundedCornerShape(4.dp),
color = containerColor,
modifier = modifier
) {
Text(
text = label,
style = MaterialTheme.typography.labelSmall,
color = contentColor,
fontWeight = FontWeight.SemiBold,
modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
)
}
}

@Composable
private fun ModelDropdownItem(
model: LlmModel,
isSelected: Boolean,
isLoaded: Boolean,
onClick: () -> Unit,
) {
DropdownMenuItem(
text = {
Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
Row(
horizontalArrangement = Arrangement.spacedBy(6.dp),
verticalAlignment = Alignment.CenterVertically,
) {
ModelFormatBadge(format = model.format)
Text(
text = model.displayName.ifBlank { model.repoId },
style = MaterialTheme.typography.bodyMedium,
fontWeight = FontWeight.Medium,
maxLines = 1,
overflow = TextOverflow.Ellipsis,
modifier = Modifier.weight(1f, fill = false),
)
if (isLoaded) {
Icon(
imageVector = Icons.Default.Circle,
contentDescription = "Loaded",
tint = MaterialTheme.colorScheme.primary,
modifier = Modifier.size(8.dp),
)
}
}
Row(
horizontalArrangement = Arrangement.spacedBy(8.dp),
verticalAlignment = Alignment.CenterVertically,
) {
if (model.quantization.isNotBlank()) {
AssistChip(
onClick = {},
label = { Text(model.quantization, style = MaterialTheme.typography.labelSmall) },
colors = AssistChipDefaults.assistChipColors(
containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
),
border = null,
modifier = Modifier.height(24.dp),
)
}
SizeBadge(sizeBytes = model.sizeBytes)
if (model.parameterCount.isNotBlank()) {
Text(
text = model.parameterCount,
style = MaterialTheme.typography.labelSmall,
color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
)
}
if (model.contextLength > 0) {
Text(
text = "ctx: ${model.contextLength}",
style = MaterialTheme.typography.labelSmall,
color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
)
}
}
}
},
onClick = onClick,
trailingIcon = {
if (isSelected) {
Icon(
imageVector = Icons.Default.Check,
contentDescription = "Selected",
tint = MaterialTheme.colorScheme.primary,
)
}
},
leadingIcon = if (isLoaded) {
{
Icon(
imageVector = Icons.Default.Memory,
contentDescription = "Currently loaded",
tint = MaterialTheme.colorScheme.primary,
modifier = Modifier.size(20.dp),
)
}
} else null,
)
}

@Composable
private fun GenerationStatsCard(stats: GenerationStats) {
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
private fun BenchmarkRunningCard() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
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
                text = "Running benchmark (pp=512, tg=128)...",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BenchmarkResultCard(
    result: String,
    onDismiss: () -> Unit,
) {
    val pp = remember(result) { extractBenchmarkMetric(result, "pp") }
    val tg = remember(result) { extractBenchmarkMetric(result, "tg") }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Benchmark",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss benchmark")
                }
            }
            Text(
                text = "PP (tokens/s): $pp",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "TG (tokens/s): $tg",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Raw output:\n$result",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}

private fun extractBenchmarkMetric(result: String, marker: String): String {
    val line = result.lineSequence().firstOrNull { it.contains("| $marker ") } ?: return "N/A"
    return line
        .split("|")
        .getOrNull(6)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "N/A"
}

// ─── Message Bubble ─────────────────────────────────────────────

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val clipboard = LocalClipboardManager.current
    val imageBitmap = remember(message.attachedImagePath) {
        message.attachedImagePath
            ?.takeIf { it.isNotBlank() }
            ?.let { BitmapFactory.decodeFile(it) }
    }
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isUser)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                imageBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Attached image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    if (message.content.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (message.content.isNotBlank() || imageBitmap == null) {
                    val finalText = message.content.ifBlank {
                        if (message.attachedImagePath != null) "Generated image" else ""
                    }
                    if (!isUser && !message.isStreaming && finalText.isNotBlank()) {
                        MarkdownMessageText(
                            markdown = finalText,
                            textColor = textColor.toArgb(),
                            modifier = Modifier.fillMaxWidth(),
                            onLongClick = {
                                clipboard.setText(AnnotatedString(message.content))
                            },
                        )
                    } else {
                        Text(
                            text = finalText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

// ─── Input Bar ──────────────────────────────────────────────────

private data class QuickTaskTemplate(
    val title: String,
    val systemPrompt: String,
    val starterPrompt: String,
)

private val quickTaskTemplates = listOf(
    QuickTaskTemplate(
        title = "Summarize",
        systemPrompt = "You are a concise summarization assistant. Prefer bullet points and preserve key facts.",
        starterPrompt = "Summarize this in 5 bullet points:\n\n",
    ),
    QuickTaskTemplate(
        title = "Explain",
        systemPrompt = "You explain concepts clearly for a mixed technical audience. Use examples and avoid jargon where possible.",
        starterPrompt = "Explain this step-by-step:\n\n",
    ),
    QuickTaskTemplate(
        title = "Refactor",
        systemPrompt = "You are a senior engineer focused on safe refactors. Keep behavior identical and justify each change.",
        starterPrompt = "Refactor this code and explain the improvements:\n\n",
    ),
    QuickTaskTemplate(
        title = "Debug",
        systemPrompt = "You are a debugging assistant. Identify root cause, propose minimal fix, and suggest verification steps.",
        starterPrompt = "Find the bug and propose a fix for:\n\n",
    ),
)

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    pendingImageAttachment: String?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onClearImage: () -> Unit,
    onApplyTaskTemplate: (QuickTaskTemplate) -> Unit,
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            // Copy to app storage for persistence
            val inputStream = context.contentResolver.openInputStream(it)
            val destFile = java.io.File(context.filesDir, "chat_attachments/${System.currentTimeMillis()}.jpg")
            destFile.parentFile?.mkdirs()
            inputStream?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            onAttachImage(destFile.absolutePath)
        }
    }

    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Pending image attachment preview
            pendingImageAttachment?.let { imagePath ->
                val bitmap = remember(imagePath) {
                    BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
                }
                bitmap?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Image(
                            bitmap = it,
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Image attached",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearImage) {
                            Icon(Icons.Default.Close, contentDescription = "Remove image")
                        }
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickTaskTemplates, key = { it.title }) { template ->
                    AssistChip(
                        onClick = { onApplyTaskTemplate(template) },
                        label = { Text(template.title) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        enabled = !isGenerating,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !isGenerating,
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Attach image")
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message…") },
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
                        enabled = text.isNotBlank() || pendingImageAttachment != null,
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

// ─── Model Configuration Dialog ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelConfigurationDialog(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(ChatAction.HideModelConfig) },
        title = {
            Text(
                text = "Model Configuration",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Selected model info
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
                                    text = model.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Format: ${model.format.name} | Quant: ${model.quantization}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Size: %.2f GB".format(model.sizeBytes / (1024.0 * 1024.0 * 1024.0)),
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
                                text = "Status: ${state.modelRuntime.status.label()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Backend: ${state.modelRuntime.backend}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = state.modelRuntime.offloadSummary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            state.modelRuntime.loadDurationMs?.let {
                                Text(
                                    text = "Load time: ${it} ms",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            state.modelRuntime.lastError?.let { err ->
                                Text(
                                    text = "Error: $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // Temperature
                item {
                    ParameterSlider(
                        label = "Temperature",
                        value = state.inferenceParams.temperature,
                        onValueChange = { onAction(ChatAction.UpdateTemperature(it)) },
                        valueRange = 0.0f..2.0f,
                        steps = 40,
                    )
                }

                // Top P
                item {
                    ParameterSlider(
                        label = "Top P",
                        value = state.inferenceParams.topP,
                        onValueChange = { onAction(ChatAction.UpdateTopP(it)) },
                        valueRange = 0.0f..1.0f,
                        steps = 20,
                    )
                }

                // Top K
                item {
                    ParameterIntSlider(
                        label = "Top K",
                        value = state.inferenceParams.topK,
                        onValueChange = { onAction(ChatAction.UpdateTopK(it)) },
                        valueRange = 1..100,
                    )
                }

                // Repeat Penalty
                item {
                    ParameterSlider(
                        label = "Repeat Penalty",
                        value = state.inferenceParams.repeatPenalty,
                        onValueChange = { onAction(ChatAction.UpdateRepeatPenalty(it)) },
                        valueRange = 1.0f..2.0f,
                        steps = 20,
                    )
                }

                // Max Tokens
                item {
                    ParameterIntSlider(
                        label = "Max Tokens",
                        value = state.inferenceParams.maxTokens,
                        onValueChange = { onAction(ChatAction.UpdateMaxTokens(it)) },
                        valueRange = 64..8192,
                        steps = 100,
                    )
                }

                // System Prompt
                item {
                    OutlinedTextField(
                        value = state.inferenceParams.systemPrompt,
                        onValueChange = { onAction(ChatAction.UpdateSystemPrompt(it)) },
                        label = { Text("System Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAction(ChatAction.HideModelConfig) }) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(ChatAction.ResetInferenceParams) }) {
                Text("Reset to Defaults")
            }
        },
    )
}

@Composable
private fun ParameterSlider(
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
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
private fun ParameterIntSlider(
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = if (steps > 0) steps else (valueRange.last - valueRange.first) / 10,
        )
    }
}
