package com.masterllm.feature.marketplace

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.DownloadState
import com.masterllm.core.domain.model.HfModelFile
import com.masterllm.core.domain.model.HfModelInfo
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    modifier: Modifier = Modifier,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            state.error?.let { error ->
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.onAction(MarketplaceAction.DismissError) }) {
                            Text("Dismiss")
                        }
                    }
                ) { Text(error) }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Tabs
            TabRow(selectedTabIndex = state.selectedTab) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.onAction(MarketplaceAction.TabChanged(0)) },
                    text = { Text("Browse") },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.onAction(MarketplaceAction.TabChanged(1)) },
                    text = { Text("Downloaded (${state.downloadedModels.size})") },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                )
            }

            AnimatedContent(
                targetState = state.selectedTab,
                label = "tab_content",
            ) { tab ->
                when (tab) {
                    0 -> BrowseTab(state, viewModel::onAction)
                    1 -> DownloadedTab(state, viewModel::onAction)
                }
            }
        }

        state.selectedModelDetails?.let { modelDetails ->
            ModelDetailsSheet(
                model = modelDetails,
                isLoading = state.isLoadingModelDetails,
                modelDetailsError = state.modelDetailsError,
                downloading = state.isDownloading,
                onAction = viewModel::onAction,
            )
        }
    }
}

@Composable
private fun BrowseTab(
    state: MarketplaceUiState,
    onAction: (MarketplaceAction) -> Unit,
) {
    Column {
        // Search bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onAction(MarketplaceAction.SearchQueryChanged(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Search models (e.g. llama, mistral, phi)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onAction(MarketplaceAction.SearchQueryChanged("")) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onAction(MarketplaceAction.Search) }),
        )

        if (state.isSearching) {
            LoadingScreen("Searching models…")
        } else if (state.searchResults.isEmpty()) {
            EmptyState(
                icon = Icons.Default.ModelTraining,
                title = "No models found",
                subtitle = "Try different search terms",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.searchResults, key = { it.modelId }) { model ->
                    ModelSearchCard(model, state.isDownloading, onAction)
                }
            }
        }
    }
}

@Composable
private fun ModelSearchCard(
    model: HfModelInfo,
    downloading: Map<String, Float>,
    onAction: (MarketplaceAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.modelId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.author.isNotEmpty()) {
                        Text(
                            text = "by ${model.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatCount(model.downloads),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatCount(model.likes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            val ggufFiles = model.siblings.filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
            val safetensorFiles = model.siblings.filter { it.rfilename.endsWith(".safetensors", ignoreCase = true) }
            val isDiffusersRepo = model.siblings.any {
                it.rfilename.equals("model_index.json", ignoreCase = true) ||
                    it.rfilename.endsWith("/model_index.json", ignoreCase = true)
            } && safetensorFiles.isNotEmpty()
            val recommendedFile = preferredDownloadFile(model.siblings)

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(MarketplaceAction.OpenModelDetails(model)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Details")
                }

                recommendedFile?.let { file ->
                    val downloadKey = "${model.modelId}/${file.rfilename}"
                    val inProgress = downloading[downloadKey] != null
                    Button(
                        onClick = { onAction(MarketplaceAction.DownloadModel(model, file)) },
                        modifier = Modifier.weight(1f),
                        enabled = !inProgress,
                    ) {
                        if (inProgress) {
                            CircularProgressIndicator(
                                progress = { downloading[downloadKey] ?: 0f },
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (inProgress) "Downloading" else "Download")
                    }
                }
            }

            if (isDiffusersRepo) {
                Spacer(Modifier.height(12.dp))
                val bundleKey = "${model.modelId}/__diffusers_bundle__"
                val bundleProgress = downloading[bundleKey]
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Diffusers Bundle",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = "Downloads model_index + UNet/VAE/tokenizer assets",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }

                        if (bundleProgress != null) {
                            CircularProgressIndicator(
                                progress = { bundleProgress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            FilledTonalButton(
                                onClick = { onAction(MarketplaceAction.DownloadDiffusersBundle(model)) },
                            ) {
                                Text("Download")
                            }
                        }
                    }
                }
            }

            // GGUF files
            if (ggufFiles.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Available files (${ggufFiles.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                ggufFiles.take(5).forEach { file ->
                    val downloadKey = "${model.modelId}/${file.rfilename}"
                    val progress = downloading[downloadKey]
                    GgufFileRow(file, progress) {
                        onAction(MarketplaceAction.DownloadModel(model, file))
                    }
                }
                if (ggufFiles.size > 5) {
                    Text(
                        text = "+${ggufFiles.size - 5} more files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (safetensorFiles.isNotEmpty() && !isDiffusersRepo) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "SafeTensors files (${safetensorFiles.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                safetensorFiles.take(5).forEach { file ->
                    val downloadKey = "${model.modelId}/${file.rfilename}"
                    val progress = downloading[downloadKey]
                    GgufFileRow(file, progress) {
                        onAction(MarketplaceAction.DownloadModel(model, file))
                    }
                }
                if (safetensorFiles.size > 5) {
                    Text(
                        text = "+${safetensorFiles.size - 5} more files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailsSheet(
    model: HfModelInfo,
    isLoading: Boolean,
    modelDetailsError: String?,
    downloading: Map<String, Float>,
    onAction: (MarketplaceAction) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val downloadableFiles = model.siblings.filter(::isDownloadableFile)
    val ggufFiles = downloadableFiles.filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
    val safetensorFiles = downloadableFiles.filter { it.rfilename.endsWith(".safetensors", ignoreCase = true) }
    val isDiffusersRepo = model.siblings.any {
        it.rfilename.equals("model_index.json", ignoreCase = true) ||
            it.rfilename.endsWith("/model_index.json", ignoreCase = true)
    } && safetensorFiles.isNotEmpty()
    val recommended = preferredDownloadFile(downloadableFiles)

    ModalBottomSheet(
        onDismissRequest = { onAction(MarketplaceAction.CloseModelDetails) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = model.modelId,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (model.author.isNotBlank()) {
                Text(
                    text = "Author: ${model.author}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Downloads: ${formatCount(model.downloads)}", style = MaterialTheme.typography.bodySmall)
                Text("Likes: ${formatCount(model.likes)}", style = MaterialTheme.typography.bodySmall)
                Text("Files: ${model.siblings.size}", style = MaterialTheme.typography.bodySmall)
            }

            if (model.description.isNotBlank()) {
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (model.modelCardUrl.isNotBlank()) {
                            uriHandler.openUri(model.modelCardUrl)
                        }
                    },
                    enabled = model.modelCardUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Model Card")
                }
                OutlinedButton(
                    onClick = { onAction(MarketplaceAction.RefreshSelectedModelDetails) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            modelDetailsError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            recommended?.let { file ->
                val downloadKey = "${model.modelId}/${file.rfilename}"
                val progress = downloading[downloadKey]
                FilledTonalButton(
                    onClick = { onAction(MarketplaceAction.DownloadModel(model, file)) },
                    enabled = progress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (progress != null) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading recommended file")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download recommended file")
                    }
                }
            }

            if (isDiffusersRepo) {
                val bundleKey = "${model.modelId}/__diffusers_bundle__"
                val bundleProgress = downloading[bundleKey]
                FilledTonalButton(
                    onClick = { onAction(MarketplaceAction.DownloadDiffusersBundle(model)) },
                    enabled = bundleProgress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (bundleProgress != null) {
                        CircularProgressIndicator(
                            progress = { bundleProgress },
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading Diffusers bundle")
                    } else {
                        Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download Diffusers bundle")
                    }
                }
            }

            if (model.cardData.isNotEmpty()) {
                Text(
                    text = "Model metadata",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                model.cardData.entries.take(6).forEach { (key, value) ->
                    Text(
                        text = "$key: $value",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (downloadableFiles.isEmpty()) {
                Text(
                    text = "No GGUF or SafeTensors files were found in this repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    text = "Downloadable files (${downloadableFiles.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(downloadableFiles, key = { it.rfilename }) { file ->
                        val progress = downloading["${model.modelId}/${file.rfilename}"]
                        GgufFileRow(file = file, downloadProgress = progress) {
                            onAction(MarketplaceAction.DownloadModel(model, file))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GgufFileRow(
    file: HfModelFile,
    downloadProgress: Float?,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.rfilename,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            file.size?.let {
                SizeBadge(sizeBytes = it)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (downloadProgress != null) {
            CircularProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DownloadedTab(
    state: MarketplaceUiState,
    onAction: (MarketplaceAction) -> Unit,
) {
    if (state.downloadedModels.isEmpty()) {
        EmptyState(
            icon = Icons.Default.FolderOpen,
            title = "No models downloaded",
            subtitle = "Browse and download models to get started",
            actionLabel = "Browse Models",
            onAction = { onAction(MarketplaceAction.TabChanged(0)) },
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.downloadedModels, key = { it.id }) { model ->
                DownloadedModelCard(model, onAction)
            }
        }
    }
}

@Composable
private fun DownloadedModelCard(
    model: LlmModel,
    onAction: (MarketplaceAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = model.repoId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                IconButton(onClick = { onAction(MarketplaceAction.DeleteModel(model.id)) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SizeBadge(sizeBytes = model.sizeBytes)
                if (model.quantization.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = model.quantization,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (model.parameterCount.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = model.parameterCount,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun isDownloadableFile(file: HfModelFile): Boolean {
    val lower = file.rfilename.lowercase()
    return lower.endsWith(".gguf") || lower.endsWith(".safetensors")
}

private fun preferredDownloadFile(files: List<HfModelFile>): HfModelFile? {
    if (files.isEmpty()) return null

    val gguf = files.filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
    if (gguf.isNotEmpty()) {
        return gguf.minByOrNull { ggufPreferenceScore(it.rfilename) }
    }

    val safetensors = files.filter { it.rfilename.endsWith(".safetensors", ignoreCase = true) }
    if (safetensors.isNotEmpty()) {
        return safetensors.minByOrNull { it.size ?: Long.MAX_VALUE } ?: safetensors.first()
    }

    return null
}

private fun ggufPreferenceScore(fileName: String): Int {
    val lower = fileName.lowercase()
    return when {
        "q4_k_m" in lower -> 0
        "q4_k_s" in lower -> 1
        "q5_k_m" in lower -> 2
        "q5_k_s" in lower -> 3
        "q6_k" in lower -> 4
        "q8_0" in lower -> 5
        "f16" in lower -> 6
        "f32" in lower -> 7
        else -> 20
    }
}
