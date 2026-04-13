package com.masterllm.feature.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.Message
import com.masterllm.core.domain.model.MessageRole
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
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
                                    onAction(ChatAction.SelectModel(model.id))
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
            ChatInputBar(
                text = state.inputText,
                isGenerating = state.isGenerating,
                onTextChange = { onAction(ChatAction.InputChanged(it)) },
                onSend = { onAction(ChatAction.SendMessage) },
                onStop = { onAction(ChatAction.StopGeneration) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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

            state.lastGenerationStats?.let { stats ->
                item(key = "generation_stats_${stats.generatedAtEpochMs}") {
                    GenerationStatsCard(stats)
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
                text = "Decode speed: ${"%.2f".format(stats.decodeTokensPerSecond)} tok/s | Native speed: ${"%.2f".format(stats.nativeTokensPerSecond)} tok/s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ─── Message Bubble ─────────────────────────────────────────────

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
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
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

// ─── Input Bar ──────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    enabled = text.isNotBlank(),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
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
