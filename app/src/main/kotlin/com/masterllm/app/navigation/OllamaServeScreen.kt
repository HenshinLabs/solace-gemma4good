package com.masterllm.app.navigation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

data class OllamaServeState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val port: String = "11434",
    val ollamaPath: String = "ollama",
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val loadedModels: List<String> = emptyList(),
    val isCheckingStatus: Boolean = false,
)

@HiltViewModel
class OllamaServeViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(OllamaServeState())
    val state: StateFlow<OllamaServeState> = _state.asStateFlow()

    private var serverProcess: Process? = null
    private var logThread: Thread? = null

    init {
        checkServerStatus()
    }

    fun startServer() {
        val currentState = _state.value
        if (currentState.isRunning || currentState.isStarting) return

        _state.update { it.copy(isStarting = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder(
                    currentState.ollamaPath, "serve"
                )
                processBuilder.environment()["OLLAMA_HOST"] = "0.0.0.0:${currentState.port}"
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                serverProcess = process

                logThread = Thread {
                    try {
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val logLine = line ?: continue
                            _state.update {
                                it.copy(logs = (it.logs + logLine).takeLast(500))
                            }
                        }
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(logs = it.logs + "Log reader error: ${e.message}")
                        }
                    }
                }.apply { isDaemon = true; start() }

                Thread.sleep(1000)

                if (process.isAlive) {
                    _state.update {
                        it.copy(isRunning = true, isStarting = false, error = null)
                    }
                    refreshLoadedModels()
                } else {
                    val exitCode = process.waitFor()
                    _state.update {
                        it.copy(
                            isStarting = false,
                            error = "Server exited immediately with code $exitCode"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isStarting = false,
                        error = "Failed to start server: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                serverProcess?.destroy()
                serverProcess?.waitFor()
                serverProcess = null
                logThread?.interrupt()
                logThread = null
                _state.update {
                    it.copy(isRunning = false, isStarting = false, loadedModels = emptyList())
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = "Failed to stop server: ${e.message}")
                }
            }
        }
    }

    fun setPort(port: String) {
        _state.update { it.copy(port = port) }
    }

    fun setOllamaPath(path: String) {
        _state.update { it.copy(ollamaPath = path) }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun checkServerStatus() {
        _state.update { it.copy(isCheckingStatus = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port = _state.value.port
                val url = java.net.URL("http://localhost:$port/")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                val body = try {
                    connection.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    ""
                }

                if (responseCode == 200 && body.contains("Ollama")) {
                    _state.update {
                        it.copy(isRunning = true, isCheckingStatus = false, error = null)
                    }
                    refreshLoadedModels()
                } else {
                    _state.update {
                        it.copy(isRunning = false, isCheckingStatus = false)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isRunning = false, isCheckingStatus = false)
                }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            val port = _state.value.port
            try {
                val url = java.net.URL("http://localhost:$port/api/tags")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    _state.update {
                        it.copy(error = null, logs = it.logs + "Connection test successful on port $port")
                    }
                    refreshLoadedModels()
                } else {
                    _state.update {
                        it.copy(error = "Connection test failed: HTTP $responseCode")
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = "Connection test failed: ${e.message}")
                }
            }
        }
    }

    private fun refreshLoadedModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port = _state.value.port
                val url = java.net.URL("http://localhost:$port/api/tags")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader().readText()
                    val models = parseModels(body)
                    _state.update { it.copy(loadedModels = models) }
                }
            } catch (e: Exception) {
                // Ignore - server may not be ready
            }
        }
    }

    private fun parseModels(json: String): List<String> {
        return try {
            val models = mutableListOf<String>()
            val regex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            regex.findAll(json).forEach { match ->
                models.add(match.groupValues[1])
            }
            models
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_state.value.isRunning) {
            stopServer()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllamaServeScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OllamaServeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val listState = rememberLazyListState()

    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Ollama Server") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ServerStatusCard(
                    isRunning = state.isRunning,
                    isStarting = state.isStarting,
                    port = state.port,
                    onStart = { viewModel.startServer() },
                    onStop = { viewModel.stopServer() },
                    onTestConnection = { viewModel.testConnection() },
                )
            }

            item {
                ServerConfigCard(
                    port = state.port,
                    ollamaPath = state.ollamaPath,
                    onPortChanged = { viewModel.setPort(it) },
                    onPathChanged = { viewModel.setOllamaPath(it) },
                )
            }

            if (state.loadedModels.isNotEmpty()) {
                item {
                    LoadedModelsCard(models = state.loadedModels)
                }
            }

            item {
                LogViewerCard(
                    logs = state.logs,
                    listState = listState,
                    onClearLogs = { viewModel.clearLogs() },
                )
            }

            if (state.error != null) {
                item {
                    ErrorCard(
                        error = state.error,
                        onDismiss = { viewModel.checkServerStatus() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerStatusCard(
    isRunning: Boolean,
    isStarting: Boolean,
    port: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isRunning -> MaterialTheme.colorScheme.primaryContainer
                isStarting -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isRunning -> MaterialTheme.colorScheme.primary
                                isStarting -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                )
                Text(
                    text = when {
                        isRunning -> "Server Running"
                        isStarting -> "Starting..."
                        else -> "Server Stopped"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (isRunning) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Listening on port $port",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Server")
                    }
                    OutlinedButton(
                        onClick = onTestConnection,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Test Connection")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !isStarting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (isStarting) "Starting..." else "Start Server")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerConfigCard(
    port: String,
    ollamaPath: String,
    onPortChanged: (String) -> Unit,
    onPathChanged: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Server Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = ollamaPath,
                onValueChange = onPathChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ollama Binary Path") },
                placeholder = { Text("ollama") },
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = port,
                onValueChange = onPortChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Port") },
                placeholder = { Text("11434") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Set OLLAMA_HOST=0.0.0.0:$port automatically",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadedModelsCard(models: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Available Models (${models.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            models.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogViewerCard(
    logs: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClearLogs: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Server Logs",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (logs.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .let { mod ->
                                mod
                            },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (logs.isEmpty()) {
                Text(
                    text = "No logs yet. Start the server to see output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = when {
                                    logLine.contains("error", ignoreCase = true) ->
                                        MaterialTheme.colorScheme.error
                                    logLine.contains("warn", ignoreCase = true) ->
                                        MaterialTheme.colorScheme.tertiary
                                    else ->
                                        MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${logs.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    error: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .let { it },
            )
        }
    }
}