package com.masterllm.app.navigation

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.masterllm.core.data.BundledModelManager
import com.masterllm.runtime.gguf.GgufEngine
import com.masterllm.runtime.gguf.PerformanceUsageSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

private const val TAG = "AgentScreen"

data class AgentMessage(
    val role: String,
    val content: String,
    val isToolCall: Boolean = false,
)

data class AgentTool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>,
)

private val availableTools = listOf(
    AgentTool(
        name = "fetch_url",
        description = "Fetch content from a URL",
        parameters = mapOf("url" to "string (required) - The URL to fetch"),
    ),
    AgentTool(
        name = "read_file",
        description = "Read contents of a file on device",
        parameters = mapOf("path" to "string (required) - Absolute file path"),
    ),
    AgentTool(
        name = "list_files",
        description = "List files in a directory",
        parameters = mapOf("path" to "string (required) - Directory path"),
    ),
    AgentTool(
        name = "run_shell",
        description = "Execute a shell command",
        parameters = mapOf("command" to "string (required) - Command to run"),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    ggufEngine: GgufEngine,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf(listOf<AgentMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    val toolsJson = remember {
        buildJsonToolList(availableTools)
    }

    LaunchedEffect(Unit) {
        statusText = if (ggufEngine.isModelLoaded() && ggufEngine.getContextLengthUsed() > 0) {
            modelLoaded = true
            "Qwen3.5 ready"
        } else {
            "Load model in Chat tab first, then use Agent"
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
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Ask the agent to do something...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            enabled = !isProcessing,
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotEmpty() && !isProcessing) {
                                    inputText = ""
                                    scope.launch {
                                        processAgentRequest(text, ggufEngine)
                                    }
                                }
                            },
                            enabled = inputText.isNotBlank() && !isProcessing,
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
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "OpenClaw Agent",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Qwen3.5-0.8B with tool calling. Ask me to browse the web, read files, or run commands.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Available tools: ${availableTools.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            items(messages) { msg ->
                MessageBubble(msg)
            }

            item {
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: AgentMessage) {
    val isUser = msg.role == "user"
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (msg.isToolCall) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (isUser) "You" else if (msg.isToolCall) "Tool Call" else "Agent",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private suspend fun processAgentRequest(
    userInput: String,
    ggufEngine: GgufEngine,
) {
    withContext(Dispatchers.Default) {
        if (!ggufEngine.isModelLoaded()) {
            throw IllegalStateException("Model not loaded")
        }

        val systemPrompt = buildAgentSystemPrompt()
        ggufEngine.addSystemPrompt(systemPrompt)
        ggufEngine.addUserMessage(userInput)

        val response = StringBuilder()
        ggufEngine.getResponseAsFlow(userInput, maxTokens = 4096).collect { piece ->
            response.append(piece)
        }

        val fullResponse = response.toString()
        val toolCalls = parseToolCalls(fullResponse)
        val finalResponse = if (toolCalls.isNotEmpty()) {
            val results = mutableListOf<String>()
            for (toolCall in toolCalls) {
                val result = executeTool(toolCall)
                results.add(result)
            }
            ggufEngine.addAssistantMessage(fullResponse)
            ggufEngine.addUserMessage("<tool_response>\n${results.joinToString("\n")}\n</tool_response>")
            val followUp = StringBuilder()
            ggufEngine.getResponseAsFlow("", maxTokens = 2048).collect { piece ->
                followUp.append(piece)
            }
            "${fullResponse}\n\n---\n\n${followUp.toString()}"
        } else {
            fullResponse
        }

        withContext(Dispatchers.Main) {
            Log.i(TAG, "Agent response: $finalResponse")
        }
    }
}

private fun buildAgentSystemPrompt(): String = """
You are OpenClaw Agent running on Android with Qwen3.5-0.8B.
You have access to the following tools:

<tools>
${buildJsonToolList(availableTools)}
</tools>

When you need to use a tool, respond with:
<tool_call>
<function=tool_name>
<parameter=param_name>
value
</parameter>
</function>
</tool_call>

Otherwise respond normally.
""".trimIndent()

private fun buildJsonToolList(tools: List<AgentTool>): String {
    return tools.joinToString(",\n") { tool ->
        """{"name":"${tool.name}","description":"${tool.description}","parameters":${tool.parameters}}"""
    }
}

private fun parseToolCalls(response: String): List<ToolCall> {
    val calls = mutableListOf<ToolCall>()
    val regex = Regex("<tool_call>\\s*<function=([^>]+)>([\\s\\S]*?)</function>\\s*</tool_call>")
    for (match in regex.findAll(response)) {
        val funcName = match.groupValues[1].trim()
        val body = match.groupValues[2].trim()
        val params = mutableMapOf<String, String>()
        val paramRegex = Regex("<parameter=([^>]+)>\\n([\\s\\S]*?)\\n</parameter>")
        for (pm in paramRegex.findAll(body)) {
            params[pm.groupValues[1].trim()] = pm.groupValues[2].trim()
        }
        calls.add(ToolCall(funcName, params))
    }
    return calls
}

private data class ToolCall(val name: String, val params: Map<String, String>)

private suspend fun executeTool(tool: ToolCall): String = withContext(Dispatchers.IO) {
    try {
        when (tool.name) {
            "fetch_url" -> {
                val url = tool.params["url"] ?: return@withContext "Error: url parameter required"
                URL(url).readText().take(5000)
            }
            "read_file" -> {
                val path = tool.params["path"] ?: return@withContext "Error: path parameter required"
                File(path).takeIf { it.exists() }?.readText()?.take(5000) ?: "Error: file not found"
            }
            "list_files" -> {
                val path = tool.params["path"] ?: return@withContext "Error: path parameter required"
                File(path).takeIf { it.isDirectory }?.list()?.joinToString("\n")?.take(2000)
                    ?: "Error: directory not found"
            }
            "run_shell" -> {
                val cmd = tool.params["command"] ?: return@withContext "Error: command parameter required"
                val process = Runtime.getRuntime().exec(cmd)
                process.inputStream.bufferedReader().readText().take(5000)
            }
            else -> "Error: unknown tool '${tool.name}'"
        }
    } catch (e: Exception) {
        "Error executing ${tool.name}: ${e.message}"
    }
}
