package com.masterllm.app.solace

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
) : ViewModel() {

    data class UiState(
        val status: ModelDownloadManager.DownloadStatus = ModelDownloadManager.DownloadStatus.CheckingLocal,
        val isReady: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        checkAndDownload()
    }

    fun checkAndDownload() {
        viewModelScope.launch {
            downloadManager.ensureModelReady().collect { status ->
                _uiState.update {
                    it.copy(
                        status = status,
                        isReady = status is ModelDownloadManager.DownloadStatus.Ready,
                    )
                }
            }
        }
    }

    fun retry() {
        downloadManager.deleteModel()
        checkAndDownload()
    }

    fun isModelReady(): Boolean = downloadManager.isModelReady()
}

@Composable
fun ModelDownloadScreen(
    onModelReady: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelDownloadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isReady) {
        if (uiState.isReady) {
            onModelReady()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Solace",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your compassionate AI companion",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(48.dp))

            AnimatedContent(
                targetState = uiState.status,
                label = "download_status",
            ) { status ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when (status) {
                        is ModelDownloadManager.DownloadStatus.CheckingLocal -> {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Checking for model...", style = MaterialTheme.typography.bodyMedium)
                        }

                        is ModelDownloadManager.DownloadStatus.Downloading -> {
                            val animatedProgress by animateFloatAsState(
                                targetValue = status.progressPercent / 100f,
                                animationSpec = tween(300),
                                label = "progress",
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Downloading Gemma 4 E2B...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatBytes(status.bytesDownloaded)} / ${formatBytes(status.totalBytes)}  (${status.progressPercent.toInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "This is a one-time download (~3.1 GB).\nPlease keep the app open.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is ModelDownloadManager.DownloadStatus.Verifying -> {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Verifying file integrity...", style = MaterialTheme.typography.bodyMedium)
                        }

                        is ModelDownloadManager.DownloadStatus.Ready -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Model ready", style = MaterialTheme.typography.bodyMedium)
                        }

                        is ModelDownloadManager.DownloadStatus.Error -> {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            if (status.retryable) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { viewModel.retry() }) {
                                    Text("Retry Download")
                                }
                            }

                            val availableBytes = remember {
                                viewModel.let {
                                    // REVIEW: accessing downloadManager from viewModel is indirect;
                                    // consider exposing a method.
                                    -1L
                                }
                            }
                            if (availableBytes in 0L until ModelDownloadManager.MODEL_SIZE_BYTES) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Insufficient storage. Need ~${formatBytes(ModelDownloadManager.MODEL_SIZE_BYTES)}, available: ${formatBytes(availableBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
