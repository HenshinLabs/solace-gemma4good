package com.masterllm.feature.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
                title = {
                    Text(
                        text = "Solace",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = { onAction(ChatAction.NewConversation) }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Crisis resource banner at the top
            CrisisResourceBanner(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
}

// ─── Chat Pane ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPane(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
) {
    val listState = rememberLazyListState()

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
                    Text(
                        text = state.currentConversation?.title ?: "Chat",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { onAction(ChatAction.ShowModelConfig) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Model settings")
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
                isListening = state.asrState.state == com.masterllm.core.data.VoskSpeechManager.ListeningState.LISTENING,
                isSpeaking = state.isSpeaking,
                voskModelReady = state.voskModelReady,
                onTextChange = { onAction(ChatAction.InputChanged(it)) },
                onSend = { onAction(ChatAction.SendMessage) },
                onStop = { onAction(ChatAction.StopGeneration) },
                onAttachImage = { onAction(ChatAction.AttachImage(it)) },
                onClearImage = { onAction(ChatAction.ClearImageAttachment) },
                onStartListening = { onAction(ChatAction.StartListening) },
                onStopListening = { onAction(ChatAction.StopListening) },
                onStopSpeaking = { onAction(ChatAction.StopSpeaking) },
                onDownloadVoskModel = { onAction(ChatAction.DownloadVoskModel) },
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
                            text = "Generating\u2026",
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

    // Extract thinking content for collapsible section
    val thinkingContent = remember(message.content) {
        extractThinkingContent(message.content)
    }
    val displayContent = remember(message.content, thinkingContent) {
        if (thinkingContent != null) {
            removeThinkingTags(message.content)
        } else {
            message.content
        }
    }

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
                // Collapsible thinking section for assistant messages
                if (!isUser && thinkingContent != null && !message.isStreaming) {
                    ThinkingSection(thinking = thinkingContent)
                    if (displayContent.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                imageBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Attached image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    if (displayContent.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (displayContent.isNotBlank() || imageBitmap == null) {
                    val finalText = displayContent.ifBlank {
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

/**
 * Collapsible card that shows thinking/reasoning content with muted styling.
 */
@Composable
private fun ThinkingSection(thinking: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = thinking.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Extracts content between <thinking>...</thinking> tags.
 * Returns null if no thinking tags are found.
 */
private fun extractThinkingContent(content: String): String? {
    // Try angle-bracket thinking tags
    val thinkPattern = Regex("""<thinking>(.*?)</thinking>""", RegexOption.DOT_MATCHES_ALL)
    val match = thinkPattern.find(content)
    if (match != null) {
        return match.groupValues[1].trim()
    }

    // Also check for <think>...</think> (common in some model outputs)
    val angleThinkPattern = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
    val angleMatch = angleThinkPattern.find(content)
    if (angleMatch != null) {
        return angleMatch.groupValues[1].trim()
    }

    return null
}

/**
 * Removes thinking tags from content, returning only the visible response text.
 */
private fun removeThinkingTags(content: String): String {
    return content
        .replace(Regex("""<thinking>.*?</thinking>""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
        .trim()
}

// ─── Input Bar ──────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    pendingImageAttachment: String?,
    isListening: Boolean = false,
    isSpeaking: Boolean = false,
    voskModelReady: Boolean = false,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onClearImage: () -> Unit,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onDownloadVoskModel: () -> Unit = {},
) {
    val context = LocalContext.current
    var webSearchEnabled by remember { mutableStateOf(false) }
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
                    placeholder = { Text("Type a message\u2026") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    enabled = !isGenerating,
                )
                Spacer(Modifier.width(4.dp))
                // Web search toggle
                IconButton(
                    onClick = { webSearchEnabled = !webSearchEnabled },
                    enabled = !isGenerating,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Toggle web search",
                        tint = if (webSearchEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                    )
                }
                // Mic button for voice input
                IconButton(
                    onClick = {
                        if (!voskModelReady) {
                            onDownloadVoskModel()
                        } else if (isListening) {
                            onStopListening()
                        } else {
                            onStartListening()
                        }
                    },
                    enabled = !isGenerating,
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Voice input",
                        tint = if (isListening) MaterialTheme.colorScheme.error
                            else if (voskModelReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }

                // TTS stop button (visible when speaking)
                if (isSpeaking) {
                    IconButton(onClick = onStopSpeaking) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = "Stop speaking",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
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

private fun ModelLoadStatus.label(): String = when (this) {
    ModelLoadStatus.IDLE -> "Idle"
    ModelLoadStatus.LOADING -> "Loading"
    ModelLoadStatus.LOADED -> "Loaded"
    ModelLoadStatus.ERROR -> "Error"
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
