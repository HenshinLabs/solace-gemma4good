package com.masterllm.feature.marketplace

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.HfModelFile
import com.masterllm.core.domain.model.HfModelInfo
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.ui.components.EmptyState
import com.masterllm.core.ui.components.LoadingScreen
import com.masterllm.core.ui.components.SizeBadge
import kotlin.math.roundToInt

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
                    0 -> BrowseTab(state = state, onAction = viewModel::onAction)
                    1 -> DownloadedTab(state = state, onAction = viewModel::onAction)
                }
            }
        }

        state.selectedModelDetails?.let { modelDetails ->
            ModelDetailsSheet(
                model = modelDetails,
                isLoading = state.isLoadingModelDetails,
                modelDetailsError = state.modelDetailsError,
                downloading = state.isDownloading,
                activeDownloads = state.activeDownloads,
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
    var showDeviceDetails by remember { mutableStateOf(false) }
    var showDiscoveryControls by remember { mutableStateOf(false) }

    if (showDeviceDetails) {
        DeviceCompatibilityDetailDialog(
            profile = state.deviceProfile,
            onDismiss = { showDeviceDetails = false },
        )
    }

    if (showDiscoveryControls) {
        DiscoveryControlsDialog(
            state = state,
            onAction = onAction,
            onDismiss = { showDiscoveryControls = false },
        )
    }

    Column {
        SearchBarWithStatus(
            state = state,
            onAction = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )

        // Compact action buttons row
        CompactControlsRow(
            profile = state.deviceProfile,
            selectedSort = state.selectedSort,
            activeFiltersCount = countActiveFilters(state),
            onDeviceClick = { showDeviceDetails = true },
            onFiltersClick = { showDiscoveryControls = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        if (state.isSearching && state.searchResults.isEmpty()) {
            LoadingScreen("Searching models...")
        } else if (state.searchResults.isEmpty()) {
            EmptyState(
                icon = Icons.Default.ModelTraining,
                title = if (state.searchQuery.isBlank()) "Enter a search term" else "No models found",
                subtitle = if (state.searchQuery.isBlank()) {
                    "Type to search for models on Hugging Face"
                } else if (state.rawSearchResults.isNotEmpty()) {
                    "Current page has no items matching active filters"
                } else {
                    "Try a different query, sort, or page"
                },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EnhancedPaginationControls(
                        currentPage = state.currentPage,
                        hasNextPage = state.hasNextPage,
                        totalResults = state.totalResults,
                        pageSize = state.pageSize,
                        isLoading = state.isSearching,
                        onPrevious = { onAction(MarketplaceAction.PreviousPage) },
                        onNext = { onAction(MarketplaceAction.NextPage) },
                        onFirst = { onAction(MarketplaceAction.FirstPage) },
                        onLast = { onAction(MarketplaceAction.LastPage) },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                
                items(state.searchResults, key = { it.modelId }) { model ->
                    ModelSearchCard(
                        model = model,
                        downloading = state.isDownloading,
                        activeDownloads = state.activeDownloads,
                        compatibility = state.compatibilityByModelId[model.modelId],
                        onAction = onAction,
                    )
                }

                if (state.currentPage > 0 || state.hasNextPage) {
                    item {
                        EnhancedPaginationControls(
                            currentPage = state.currentPage,
                            hasNextPage = state.hasNextPage,
                            totalResults = state.totalResults,
                            pageSize = state.pageSize,
                            isLoading = state.isSearching,
                            onPrevious = { onAction(MarketplaceAction.PreviousPage) },
                            onNext = { onAction(MarketplaceAction.NextPage) },
                            onFirst = { onAction(MarketplaceAction.FirstPage) },
                            onLast = { onAction(MarketplaceAction.LastPage) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBarWithStatus(
    state: MarketplaceUiState,
    onAction: (MarketplaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onAction(MarketplaceAction.SearchQueryChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search models (e.g. llama, mistral, phi)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isSearchingLive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onAction(MarketplaceAction.SearchQueryChanged("")) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onAction(MarketplaceAction.Search) }),
        )
        
        // Search status text
        if (state.lastSearchResultsCount > 0) {
            Text(
                text = "Found ${state.lastSearchResultsCount} models • Page ${state.currentPage + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun ModelSearchCard(
    model: HfModelInfo,
    downloading: Map<String, Float>,
    activeDownloads: Map<String, DownloadTelemetry>,
    compatibility: ModelCompatibility?,
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
                        Spacer(Modifier.size(4.dp))
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
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = formatCount(model.likes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            compatibility?.let {
                Spacer(Modifier.size(10.dp))
                CompatibilityBadge(compatibility = it)
                Spacer(Modifier.size(4.dp))
                Text(
                    text = it.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }

            val ggufFiles = model.siblings.filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
            val safetensorFiles = model.siblings.filter { it.rfilename.endsWith(".safetensors", ignoreCase = true) }
            val isDiffusersRepo = model.siblings.any {
                it.rfilename.equals("model_index.json", ignoreCase = true) ||
                    it.rfilename.endsWith("/model_index.json", ignoreCase = true)
            } && safetensorFiles.isNotEmpty()
            val recommendedFile = preferredDownloadFile(model.siblings)
            val recommendedTelemetry = recommendedFile?.let {
                activeDownloads["${model.modelId}/${it.rfilename}"]
            }

            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(MarketplaceAction.OpenModelDetails(model)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Details")
                }

                recommendedFile?.let { file ->
                    val downloadKey = "${model.modelId}/${file.rfilename}"
                    val inProgress = activeDownloads[downloadKey] != null || downloading[downloadKey] != null
                    Button(
                        onClick = { onAction(MarketplaceAction.DownloadModel(model, file)) },
                        modifier = Modifier.weight(1f),
                        enabled = !inProgress,
                    ) {
                        if (inProgress) {
                            CircularProgressIndicator(
                                progress = { recommendedTelemetry?.progress ?: downloading[downloadKey] ?: 0f },
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(if (inProgress) "Downloading" else "Download")
                    }
                }
            }

            recommendedTelemetry?.let { telemetry ->
                Spacer(Modifier.size(10.dp))
                DownloadTelemetryBlock(
                    telemetry = telemetry,
                    showPlannedFiles = false,
                )
            }

            if (isDiffusersRepo) {
                Spacer(Modifier.size(12.dp))
                val bundleKey = "${model.modelId}/__diffusers_bundle__"
                val bundleTelemetry = activeDownloads[bundleKey]
                val bundleProgress = bundleTelemetry?.progress ?: downloading[bundleKey]
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

                bundleTelemetry?.let { telemetry ->
                    Spacer(Modifier.size(10.dp))
                    DownloadTelemetryBlock(
                        telemetry = telemetry,
                        showPlannedFiles = true,
                    )
                }
            }

            if (ggufFiles.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "Available files (${ggufFiles.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                ggufFiles.take(5).forEach { file ->
                    val downloadKey = "${model.modelId}/${file.rfilename}"
                    val progress = activeDownloads[downloadKey]?.progress ?: downloading[downloadKey]
                    GgufFileRow(file = file, downloadProgress = progress) {
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
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "SafeTensors files (${safetensorFiles.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.size(8.dp))
                safetensorFiles.take(5).forEach { file ->
                    val downloadKey = "${model.modelId}/${file.rfilename}"
                    val progress = activeDownloads[downloadKey]?.progress ?: downloading[downloadKey]
                    GgufFileRow(file = file, downloadProgress = progress) {
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
    activeDownloads: Map<String, DownloadTelemetry>,
    onAction: (MarketplaceAction) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val downloadableFiles = model.siblings.filter(::isDownloadableFile)
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
                    Spacer(Modifier.size(6.dp))
                    Text("Model Card")
                }
                OutlinedButton(
                    onClick = { onAction(MarketplaceAction.RefreshSelectedModelDetails) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
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
                val telemetry = activeDownloads[downloadKey]
                val progress = telemetry?.progress ?: downloading[downloadKey]
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
                        Spacer(Modifier.size(8.dp))
                        Text("Downloading recommended file")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Download recommended file")
                    }
                }
                telemetry?.let {
                    DownloadTelemetryBlock(
                        telemetry = it,
                        showPlannedFiles = false,
                    )
                }
            }

            if (isDiffusersRepo) {
                val bundleKey = "${model.modelId}/__diffusers_bundle__"
                val bundleTelemetry = activeDownloads[bundleKey]
                val bundleProgress = bundleTelemetry?.progress ?: downloading[bundleKey]
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
                        Spacer(Modifier.size(8.dp))
                        Text("Downloading Diffusers bundle")
                    } else {
                        Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Download Diffusers bundle")
                    }
                }
                bundleTelemetry?.let {
                    DownloadTelemetryBlock(
                        telemetry = it,
                        showPlannedFiles = true,
                    )
                }
            }

            val modelDownloads = activeDownloads.values
                .filter { it.modelId == model.modelId }
                .sortedBy { it.startedAtMs }
            if (modelDownloads.isNotEmpty()) {
                Text(
                    text = "Live download telemetry",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                modelDownloads.forEach { telemetry ->
                    DownloadTelemetryBlock(
                        telemetry = telemetry,
                        showPlannedFiles = true,
                    )
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
                        val key = "${model.modelId}/${file.rfilename}"
                        val progress = activeDownloads[key]?.progress ?: downloading[key]
                        GgufFileRow(file = file, downloadProgress = progress) {
                            onAction(MarketplaceAction.DownloadModel(model, file))
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
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
        Spacer(Modifier.size(8.dp))
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
    if (state.activeDownloads.isNotEmpty()) {
        ActiveDownloadsSection(
            activeDownloads = state.activeDownloads.values.toList(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    if (state.downloadedModels.isEmpty() && state.activeDownloads.isEmpty()) {
        EmptyState(
            icon = Icons.Default.FolderOpen,
            title = "No models downloaded",
            subtitle = "Browse and download models to get started",
            actionLabel = "Browse Models",
            onAction = { onAction(MarketplaceAction.TabChanged(0)) },
        )
    } else if (state.downloadedModels.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Downloads are currently in progress.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Completed models will appear here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.downloadedModels, key = { it.id }) { model ->
                DownloadedModelCard(
                    model = model,
                    onAction = onAction,
                )
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
                Spacer(Modifier.size(12.dp))
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
            Spacer(Modifier.size(8.dp))
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

@Composable
private fun DeviceCompatibilityCard(
    profile: DeviceProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Device Compatibility",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Tap for details",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "RAM ${profile.totalRamGb}GB (free ${profile.availableRamGb}GB) • CPU ${profile.cpuCores} cores",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "GPU ${profile.gpuHint} • Vulkan ${if (profile.vulkanSupported) "Available" else "Not reported"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Text(
                text = profile.recommendationLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DeviceCompatibilityDetailDialog(
    profile: DeviceProfile,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("Device Specifications")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceSpecItem(
                    icon = Icons.Default.Memory,
                    title = "RAM",
                    value = "${profile.totalRamGb} GB Total",
                    subtitle = "${profile.availableRamGb} GB Available",
                )
                DeviceSpecItem(
                    icon = Icons.Default.SmartToy,
                    title = "CPU",
                    value = "${profile.cpuCores} Cores",
                    subtitle = "Runtime available processors",
                )
                DeviceSpecItem(
                    icon = Icons.Default.Visibility,
                    title = "GPU",
                    value = profile.gpuHint,
                    subtitle = if (profile.vulkanSupported) "Vulkan Supported" else "Vulkan Not Available",
                )
                DeviceSpecItem(
                    icon = Icons.Default.Storage,
                    title = "Recommended Model Size",
                    value = "${profile.recommendedMaxParamsB}B Parameters",
                    subtitle = "Based on your device specs",
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                
                Text(
                    text = "Compatibility Guide",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Green badge: Model will run smoothly",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Yellow badge: Model may work with limitations",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Red badge: Model likely too large for this device",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun DeviceSpecItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    subtitle: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun HorizontalDivider(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// Helper to count active filters
private fun countActiveFilters(state: MarketplaceUiState): Int {
    var count = 0
    if (state.selectedSort != MarketplaceSort.TRENDING) count++
    if (state.selectedFormatFilter != ModelFormatFilter.ANY) count++
    if (state.selectedSizeFilter != ParameterSizeFilter.ANY) count++
    if (state.selectedTaskFilter != "All tasks") count++
    return count
}

@Composable
private fun CompactControlsRow(
    profile: DeviceProfile,
    selectedSort: MarketplaceSort,
    activeFiltersCount: Int,
    onDeviceClick: () -> Unit,
    onFiltersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Device Compatibility Button
        AssistChip(
            onClick = onDeviceClick,
            label = { Text("${profile.totalRamGb}GB RAM") },
            leadingIcon = {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                leadingIconContentColor = MaterialTheme.colorScheme.primary,
            ),
        )

        Spacer(modifier = Modifier.weight(1f))

        // Filters Button with badge
        FilterChip(
            selected = activeFiltersCount > 0,
            onClick = onFiltersClick,
            label = {
                Text(
                    if (activeFiltersCount > 0) "Filters ($activeFiltersCount)" else "Filters"
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )

        // Sort Button
        AssistChip(
            onClick = onFiltersClick,
            label = { Text(selectedSort.label) },
            leadingIcon = {
                Icon(
                    when (selectedSort) {
                        MarketplaceSort.TRENDING -> Icons.Default.TrendingUp
                        MarketplaceSort.RECENT -> Icons.Default.Schedule
                        MarketplaceSort.MOST_DOWNLOADED -> Icons.Default.Download
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryControlsDialog(
    state: MarketplaceUiState,
    onAction: (MarketplaceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search Filters",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            // Call the existing DiscoveryControls content
            DiscoveryControlsContent(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.size(24.dp))

            Button(
                onClick = {
                    onAction(MarketplaceAction.Search)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Apply Filters & Search")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryControls(
    state: MarketplaceUiState,
    onAction: (MarketplaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        DiscoveryControlsContent(
            state = state,
            onAction = onAction,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryControlsContent(
    state: MarketplaceUiState,
    onAction: (MarketplaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var taskExpanded by remember { mutableStateOf(false) }
    var showAllFilters by remember { mutableStateOf(false) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search Controls",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = { showAllFilters = !showAllFilters },
                ) {
                    Text(if (showAllFilters) "Show less" else "More filters")
                }
            }

            // Sort Dropdown
            ExposedDropdownMenuBox(
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = !sortExpanded },
            ) {
                OutlinedTextField(
                    value = state.selectedSort.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sort by") },
                    leadingIcon = {
                        Icon(
                            when (state.selectedSort) {
                                MarketplaceSort.TRENDING -> Icons.Default.TrendingUp
                                MarketplaceSort.RECENT -> Icons.Default.Schedule
                                MarketplaceSort.MOST_DOWNLOADED -> Icons.Default.Download
                            },
                            contentDescription = null,
                        )
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                ) {
                    MarketplaceSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            leadingIcon = {
                                Icon(
                                    when (sort) {
                                        MarketplaceSort.TRENDING -> Icons.Default.TrendingUp
                                        MarketplaceSort.RECENT -> Icons.Default.Schedule
                                        MarketplaceSort.MOST_DOWNLOADED -> Icons.Default.Download
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onAction(MarketplaceAction.SortChanged(sort))
                                sortExpanded = false
                            },
                        )
                    }
                }
            }

            // Task Filter Dropdown
            ExposedDropdownMenuBox(
                expanded = taskExpanded,
                onExpandedChange = { taskExpanded = !taskExpanded },
            ) {
                OutlinedTextField(
                    value = state.selectedTaskFilter,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Task type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = taskExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = taskExpanded,
                    onDismissRequest = { taskExpanded = false },
                ) {
                    state.availableTaskFilters.forEach { task ->
                        DropdownMenuItem(
                            text = { Text(task) },
                            onClick = {
                                onAction(MarketplaceAction.TaskFilterChanged(task))
                                taskExpanded = false
                            },
                        )
                    }
                }
            }

            // Format Filter Chips
            Text(
                text = "Format",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ModelFormatFilter.entries) { filter ->
                    FilterChip(
                        selected = state.selectedFormatFilter == filter,
                        onClick = { onAction(MarketplaceAction.FormatFilterChanged(filter)) },
                        label = { Text(filter.label) },
                        leadingIcon = if (state.selectedFormatFilter == filter) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                    )
                }
            }

            // Parameter Size Filter Chips
            Text(
                text = "Parameter size",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ParameterSizeFilter.entries) { filter ->
                    FilterChip(
                        selected = state.selectedSizeFilter == filter,
                        onClick = { onAction(MarketplaceAction.SizeFilterChanged(filter)) },
                        label = { Text(filter.label) },
                        leadingIcon = if (state.selectedSizeFilter == filter) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                    )
                }
            }

            // Enhanced Search Button
            Button(
                onClick = { onAction(MarketplaceAction.Search) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Search Hugging Face")
            }
        }
    }
}

@Composable
private fun PaginationControls(
    currentPage: Int,
    hasNextPage: Boolean,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    EnhancedPaginationControls(
        currentPage = currentPage,
        hasNextPage = hasNextPage,
        totalResults = 0,
        pageSize = 20,
        isLoading = isLoading,
        onPrevious = onPrevious,
        onNext = onNext,
        onFirst = {},
        onLast = {},
    )
}

@Composable
private fun EnhancedPaginationControls(
    currentPage: Int,
    hasNextPage: Boolean,
    totalResults: Int,
    pageSize: Int,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFirst: () -> Unit,
    onLast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Results info
            if (totalResults > 0) {
                val start = currentPage * pageSize + 1
                val end = (start + pageSize - 1).coerceAtMost(totalResults)
                Text(
                    text = "Showing $start-$end of ~$totalResults results",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            
            // Pagination buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // First page button
                IconButton(
                    onClick = onFirst,
                    enabled = currentPage > 0 && !isLoading,
                ) {
                    Icon(Icons.Default.FirstPage, contentDescription = "First page")
                }
                
                // Previous button
                OutlinedButton(
                    onClick = onPrevious,
                    enabled = currentPage > 0 && !isLoading,
                ) {
                    Icon(Icons.Default.NavigateBefore, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Prev")
                }

                // Page indicator with dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${currentPage + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (hasNextPage || totalResults > 0) {
                        Text(
                            text = "/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        val totalPages = if (totalResults > 0) {
                            (totalResults + pageSize - 1) / pageSize
                        } else {
                            "?"
                        }
                        Text(
                            text = totalPages.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }

                // Next button
                FilledTonalButton(
                    onClick = onNext,
                    enabled = hasNextPage && !isLoading,
                ) {
                    Text("Next")
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Default.NavigateNext, contentDescription = null)
                }
                
                // Last page button
                IconButton(
                    onClick = onLast,
                    enabled = hasNextPage && totalResults > 0 && !isLoading,
                ) {
                    Icon(Icons.Default.LastPage, contentDescription = "Last page")
                }
            }
        }
    }
}

@Composable
private fun CompatibilityBadge(
    compatibility: ModelCompatibility,
) {
    val colors = when (compatibility.tier) {
        CompatibilityTier.RECOMMENDED -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        CompatibilityTier.POSSIBLE -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        CompatibilityTier.HEAVY -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
        )

        CompatibilityTier.UNKNOWN -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(compatibility.tier.label) },
        colors = colors,
    )
}

@Composable
private fun ActiveDownloadsSection(
    activeDownloads: List<DownloadTelemetry>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Active Downloads (${activeDownloads.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            activeDownloads
                .sortedBy { it.startedAtMs }
                .forEach { telemetry ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = telemetry.modelId,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            DownloadTelemetryBlock(
                                telemetry = telemetry,
                                showPlannedFiles = true,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun DownloadTelemetryBlock(
    telemetry: DownloadTelemetry,
    showPlannedFiles: Boolean,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header with icon and file info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Downloading,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = telemetry.fileName.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "File ${telemetry.currentFileIndex} of ${telemetry.totalFiles}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // Progress bar with percentage
            Column {
                LinearProgressIndicator(
                    progress = { telemetry.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatPercent(telemetry.progress),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${formatBytes(telemetry.bytesDownloaded)} / ${telemetry.totalBytes?.let(::formatBytes) ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }

            // Speed indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "${formatBytes(telemetry.speedBytesPerSec)}/s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
            }

            // File list if showing planned files
            if (showPlannedFiles && telemetry.plannedFiles.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Files to download (${telemetry.plannedFiles.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    telemetry.plannedFiles.take(5).forEach { name ->
                        val isCurrentFile = name == telemetry.fileName || 
                            name.substringAfterLast("/") == telemetry.fileName.substringAfterLast("/")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        color = if (isCurrentFile) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    ),
                            )
                            Text(
                                text = name.substringAfterLast("/"),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isCurrentFile) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                },
                            )
                        }
                    }
                    if (telemetry.plannedFiles.size > 5) {
                        Text(
                            text = "+${telemetry.plannedFiles.size - 5} more files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gb -> "%.2f GB".format(value / gb)
        value >= mb -> "%.2f MB".format(value / mb)
        value >= kb -> "%.1f KB".format(value / kb)
        else -> "$bytes B"
    }
}

private fun formatPercent(progress: Float): String {
    return "${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%"
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
