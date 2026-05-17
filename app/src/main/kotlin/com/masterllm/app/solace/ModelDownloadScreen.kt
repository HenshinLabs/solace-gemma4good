package com.masterllm.app.solace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
        val phase: Phase = Phase.CheckingLocal,
        val downloadStatus: ModelDownloadManager.DownloadStatus = ModelDownloadManager.DownloadStatus.CheckingLocal,
        val isReady: Boolean = false,
    )

    enum class Phase {
        CheckingLocal,
        NeedsConsent,
        Downloading,
        Verifying,
        Ready,
        Error,
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        checkLocal()
    }

    private fun checkLocal() {
        viewModelScope.launch {
            if (downloadManager.isModelReady()) {
                _uiState.update { it.copy(phase = Phase.Ready, isReady = true) }
                return@launch
            }
            _uiState.update { it.copy(phase = Phase.NeedsConsent) }
        }
    }

    fun startDownload() {
        _uiState.update { it.copy(phase = Phase.Downloading) }
        viewModelScope.launch {
            // Download main model
            downloadManager.ensureModelReady().collect { status ->
                val phase = when (status) {
                    is ModelDownloadManager.DownloadStatus.CheckingLocal -> Phase.CheckingLocal
                    is ModelDownloadManager.DownloadStatus.Downloading -> Phase.Downloading
                    is ModelDownloadManager.DownloadStatus.Verifying -> Phase.Verifying
                    is ModelDownloadManager.DownloadStatus.Ready -> Phase.Ready
                    is ModelDownloadManager.DownloadStatus.Error -> Phase.Error
                }
                _uiState.update {
                    it.copy(
                        phase = phase,
                        downloadStatus = status,
                        isReady = false,
                    )
                }
            }

            // Download mmproj for vision support (optional, non-blocking)
            if (downloadManager.isModelReady() && !downloadManager.isMmprojReady()) {
                _uiState.update {
                    it.copy(
                        downloadStatus = ModelDownloadManager.DownloadStatus.Downloading(0, ModelDownloadManager.MMPROJ_SIZE_BYTES),
                    )
                }
                downloadManager.ensureMmprojReady().collect { status ->
                    // mmproj download is optional — don't fail the whole flow
                    when (status) {
                        is ModelDownloadManager.DownloadStatus.Ready -> {
                            _uiState.update { it.copy(phase = Phase.Ready, isReady = true) }
                        }
                        is ModelDownloadManager.DownloadStatus.Error -> {
                            // mmproj failed but main model is ready — still proceed
                            _uiState.update { it.copy(phase = Phase.Ready, isReady = true) }
                        }
                        else -> { /* progress updates */ }
                    }
                }
            }

            // All done
            if (downloadManager.isModelReady()) {
                _uiState.update { it.copy(phase = Phase.Ready, isReady = true) }
            }
        }
    }

    fun retry() {
        downloadManager.deleteModel()
        startDownload()
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

            when (uiState.phase) {
                ModelDownloadViewModel.Phase.CheckingLocal -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Checking for model...", style = MaterialTheme.typography.bodyMedium)
                }

                ModelDownloadViewModel.Phase.NeedsConsent -> {
                    ConsentCard(onAllow = { viewModel.startDownload() })
                }

                ModelDownloadViewModel.Phase.Downloading -> {
                    DownloadingContent(status = uiState.downloadStatus as? ModelDownloadManager.DownloadStatus.Downloading)
                }

                ModelDownloadViewModel.Phase.Verifying -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Verifying file integrity...", style = MaterialTheme.typography.bodyMedium)
                }

                ModelDownloadViewModel.Phase.Ready -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Model ready", style = MaterialTheme.typography.bodyMedium)
                }

                ModelDownloadViewModel.Phase.Error -> {
                    val errorStatus = uiState.downloadStatus as? ModelDownloadManager.DownloadStatus.Error
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorStatus?.message ?: "An error occurred",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    if (errorStatus?.retryable != false) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.retry() }) {
                            Text("Retry Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(onAllow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Model Download Required",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Solace needs to download the Gemma 4 E2B AI model to run locally on your device.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Model info
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoRow(label = "Model", value = "Gemma 4 E2B (Q4_K_M)")
                InfoRow(label = "Size", value = "~3.1 GB")
                InfoRow(label = "Vision", value = "mmproj (~941 MB)")
                InfoRow(label = "Source", value = "HuggingFace")
                InfoRow(label = "Context", value = "128K tokens")
                InfoRow(label = "Storage", value = "External files dir")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This is a one-time download (~4 GB total). The model and vision projector will be cached locally. You can delete them later from Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download Model (~3.1 GB)")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Requires internet connection",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DownloadingContent(status: ModelDownloadManager.DownloadStatus.Downloading?) {
    if (status == null) return

    // Smooth progress animation — only the float value animates, not the whole composable
    val animatedProgress by animateFloatAsState(
        targetValue = status.progressPercent / 100f,
        animationSpec = tween(durationMillis = 500),
        label = "download_progress",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Downloading Gemma 4 E2B...",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Progress bar with smooth animation
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Byte count — updates independently, no animation wrapper
        Text(
            text = "${formatBytes(status.bytesDownloaded)} / ${formatBytes(status.totalBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${status.progressPercent.toInt()}%",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Please keep the app open while downloading.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
