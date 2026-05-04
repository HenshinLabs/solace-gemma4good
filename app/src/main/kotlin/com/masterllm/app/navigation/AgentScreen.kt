package com.masterllm.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.masterllm.app.openclaw.AgentMessage
import com.masterllm.app.openclaw.AgentStatus
import com.masterllm.app.openclaw.AgentViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.checkModelStatus()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw Agent") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearSession() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear session")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    StatusBar(state)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = { viewModel.onInputChanged(it) },
                            placeholder = { Text("Ask the agent to do something...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            enabled = !state.isProcessing,
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { viewModel.sendMessage() },
                            enabled = state.inputText.isNotBlank() && !state.isProcessing,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                WelcomeCard(state)
            }

            items(state.messages) { msg ->
                MessageBubble(msg)
            }

            item {
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun StatusBar(state: com.masterllm.app.openclaw.AgentUiState) {
    when {
        state.statusMessage.isNotEmpty() -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun WelcomeCard(state: com.masterllm.app.openclaw.AgentUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "OpenClaw Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Qwen3.5-0.8B with multi-turn tool calling",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.modelReady) "✅ Model ready" else "⚠️ Load model in Chat tab first",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Tools: ${state.tools.joinToString(", ") { it.name }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: AgentMessage) {
    val (containerColor, label) = when {
        msg.role == "user" -> MaterialTheme.colorScheme.primaryContainer to "You"
        msg.isToolCall -> MaterialTheme.colorScheme.tertiaryContainer to "Tool Call"
        msg.isToolResult -> MaterialTheme.colorScheme.surfaceVariant to "Tool Result"
        else -> MaterialTheme.colorScheme.secondaryContainer to "Agent"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyMedium.let {
                    if (msg.isToolResult || msg.isToolCall) it.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    else it
                },
            )
        }
    }
}
