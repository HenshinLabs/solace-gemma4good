package com.masterllm.feature.marketplace

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
