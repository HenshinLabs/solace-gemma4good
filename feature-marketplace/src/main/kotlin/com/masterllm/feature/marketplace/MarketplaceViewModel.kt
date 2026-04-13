package com.masterllm.feature.marketplace

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.core.network.HuggingFaceApi
import com.masterllm.core.network.toBearerAuthHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

private const val PAGE_SIZE = 20
private const val TASK_FILTER_ALL = "All tasks"
private const val LIVE_SEARCH_DEBOUNCE_MS = 300L
private const val PROGRESS_SAMPLE_INTERVAL_MS = 350L
private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
private const val MAX_FILTER_SCAN_PAGES = 8

enum class MarketplaceSort(
    val label: String,
    val apiSort: String,
    val apiDirection: String,
) {
    TRENDING("Trending", "likes", "-1"),
    RECENT("Recently Updated", "lastModified", "-1"),
    MOST_DOWNLOADED("Most Downloaded", "downloads", "-1"),
}

enum class ModelFormatFilter(val label: String) {
    ANY("Any format"),
    GGUF("GGUF"),
    SAFETENSORS("SafeTensors"),
    DIFFUSERS("Diffusers"),
}

enum class ParameterSizeFilter(val label: String) {
    ANY("Any size"),
    UP_TO_3B("<=3B"),
    BETWEEN_3B_AND_8B("3B-8B"),
    BETWEEN_8B_AND_20B("8B-20B"),
    ABOVE_20B("20B+"),
}

enum class CompatibilityTier(val label: String) {
    RECOMMENDED("Recommended"),
    POSSIBLE("Possible"),
    HEAVY("Heavy"),
    UNKNOWN("Unknown"),
}

data class ModelCompatibility(
    val tier: CompatibilityTier,
    val reason: String,
)

data class DeviceProfile(
    val totalRamGb: Int = 0,
    val availableRamGb: Int = 0,
    val cpuCores: Int = 0,
    val gpuHint: String = "Unknown",
    val vulkanSupported: Boolean = false,
    val recommendedMaxParamsB: Double = 2.0,
    val recommendationLabel: String = "Detecting device profile...",
)

data class DownloadTelemetry(
    val key: String,
    val modelId: String,
    val fileName: String,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long = 0L,
    val currentFileIndex: Int = 1,
    val totalFiles: Int = 1,
    val plannedFiles: List<String> = emptyList(),
    val startedAtMs: Long = System.currentTimeMillis(),
)

data class MarketplaceUiState(
    val searchQuery: String = "",
    val rawSearchResults: List<HfModelInfo> = emptyList(),
    val searchResults: List<HfModelInfo> = emptyList(),
    val downloadedModels: List<LlmModel> = emptyList(),
    val isSearching: Boolean = false,
    val isSearchingLive: Boolean = false, // For showing search indicator while typing
    val isDownloading: Map<String, Float> = emptyMap(), // modelId → progress 0..1
    val activeDownloads: Map<String, DownloadTelemetry> = emptyMap(),
    val selectedSort: MarketplaceSort = MarketplaceSort.TRENDING,
    val selectedFormatFilter: ModelFormatFilter = ModelFormatFilter.ANY,
    val selectedSizeFilter: ParameterSizeFilter = ParameterSizeFilter.ANY,
    val selectedTaskFilter: String = TASK_FILTER_ALL,
    val availableTaskFilters: List<String> = listOf(TASK_FILTER_ALL),
    val currentPage: Int = 0,
    val hasNextPage: Boolean = false,
    val totalResults: Int = 0, // Estimated total results from API
    val pageSize: Int = PAGE_SIZE,
    val deviceProfile: DeviceProfile = DeviceProfile(),
    val compatibilityByModelId: Map<String, ModelCompatibility> = emptyMap(),
    val error: String? = null,
    val selectedTab: Int = 0, // 0=Browse, 1=Downloaded
    val selectedModelDetails: HfModelInfo? = null,
    val isLoadingModelDetails: Boolean = false,
    val modelDetailsError: String? = null,
    val lastSearchResultsCount: Int = 0, // For displaying search status
)

sealed interface MarketplaceAction {
    data class SearchQueryChanged(val query: String) : MarketplaceAction
    data object Search : MarketplaceAction
    data class SortChanged(val sort: MarketplaceSort) : MarketplaceAction
    data class FormatFilterChanged(val filter: ModelFormatFilter) : MarketplaceAction
    data class SizeFilterChanged(val filter: ParameterSizeFilter) : MarketplaceAction
    data class TaskFilterChanged(val task: String) : MarketplaceAction
    data object NextPage : MarketplaceAction
    data object PreviousPage : MarketplaceAction
    data object FirstPage : MarketplaceAction
    data object LastPage : MarketplaceAction
    data class OpenModelDetails(val modelInfo: HfModelInfo) : MarketplaceAction
    data object CloseModelDetails : MarketplaceAction
    data object RefreshSelectedModelDetails : MarketplaceAction
    data class DownloadModel(val modelInfo: HfModelInfo, val file: HfModelFile) : MarketplaceAction
    data class DownloadDiffusersBundle(val modelInfo: HfModelInfo) : MarketplaceAction
    data class DeleteModel(val modelId: String) : MarketplaceAction
    data class TabChanged(val tab: Int) : MarketplaceAction
    data object DismissError : MarketplaceAction
}

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val huggingFaceApi: HuggingFaceApi,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState: StateFlow<MarketplaceUiState> = _uiState.asStateFlow()

    private var liveSearchJob: Job? = null

    @Volatile
    private var modelStorageRootPath: String = ""

    @Volatile
    private var searchRequestCounter: Long = 0L

    init {
        // Watch downloaded models
        viewModelScope.launch {
            modelRepository.getDownloadedModels().collect { models ->
                _uiState.update { it.copy(downloadedModels = models) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getModelStoragePath().collect { path ->
                modelStorageRootPath = path.trim()
            }
        }
        refreshDeviceProfile()
        _uiState.update { it.copy(searchQuery = "GGUF") }
        searchModels("GGUF", resetPage = true)
    }

    fun onAction(action: MarketplaceAction) {
        when (action) {
            is MarketplaceAction.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = action.query, isSearchingLive = action.query.isNotEmpty()) }
                scheduleLiveSearch(action.query)
            }
            MarketplaceAction.Search -> searchModels(_uiState.value.searchQuery, resetPage = true)
            is MarketplaceAction.SortChanged -> {
                _uiState.update { it.copy(selectedSort = action.sort) }
                searchModels(_uiState.value.searchQuery, resetPage = true)
            }
            is MarketplaceAction.FormatFilterChanged -> {
                _uiState.update { it.copy(selectedFormatFilter = action.filter) }
                applyLocalFiltersToCurrentPage()
            }
            is MarketplaceAction.SizeFilterChanged -> {
                _uiState.update { it.copy(selectedSizeFilter = action.filter) }
                applyLocalFiltersToCurrentPage()
            }
            is MarketplaceAction.TaskFilterChanged -> {
                _uiState.update { it.copy(selectedTaskFilter = action.task) }
                applyLocalFiltersToCurrentPage()
            }
            MarketplaceAction.NextPage -> {
                val state = _uiState.value
                if (state.hasNextPage && !state.isSearching) {
                    searchModels(state.searchQuery, requestedPage = state.currentPage + 1)
                }
            }
            MarketplaceAction.PreviousPage -> {
                val state = _uiState.value
                if (state.currentPage > 0 && !state.isSearching) {
                    searchModels(state.searchQuery, requestedPage = state.currentPage - 1)
                }
            }
            MarketplaceAction.FirstPage -> {
                val state = _uiState.value
                if (!state.isSearching) {
                    searchModels(state.searchQuery, requestedPage = 0)
                }
            }
            MarketplaceAction.LastPage -> {
                val state = _uiState.value
                if (!state.isSearching && state.totalResults > 0) {
                    val lastPage = (state.totalResults - 1) / state.pageSize
                    searchModels(state.searchQuery, requestedPage = lastPage)
                }
            }
            is MarketplaceAction.OpenModelDetails -> openModelDetails(action.modelInfo)
            MarketplaceAction.CloseModelDetails -> {
                _uiState.update {
                    it.copy(
                        selectedModelDetails = null,
                        isLoadingModelDetails = false,
                        modelDetailsError = null,
                    )
                }
            }
            MarketplaceAction.RefreshSelectedModelDetails -> {
                _uiState.value.selectedModelDetails?.let { openModelDetails(it) }
            }
            is MarketplaceAction.DownloadModel -> downloadModel(action.modelInfo, action.file)
            is MarketplaceAction.DownloadDiffusersBundle -> downloadDiffusersBundle(action.modelInfo)
            is MarketplaceAction.DeleteModel -> deleteModel(action.modelId)
            is MarketplaceAction.TabChanged ->
                _uiState.update { it.copy(selectedTab = action.tab) }
            MarketplaceAction.DismissError -> _uiState.update {
                it.copy(error = null, modelDetailsError = null)
            }
        }
    }

    private fun scheduleLiveSearch(query: String) {
        liveSearchJob?.cancel()
        liveSearchJob = viewModelScope.launch {
            delay(LIVE_SEARCH_DEBOUNCE_MS)
            searchModels(query, resetPage = true)
        }
    }

    private fun searchModels(
        query: String,
        resetPage: Boolean = false,
        requestedPage: Int? = null,
    ) {
        val targetPage = requestedPage ?: if (resetPage) 0 else _uiState.value.currentPage
        val requestId = ++searchRequestCounter

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, isSearchingLive = false, error = null) }
            try {
                val stateSnapshot = _uiState.value
                val sort = stateSnapshot.selectedSort
                val requestedPageIndex = targetPage.coerceAtLeast(0)
                val remoteQuery = buildRemoteSearchQuery(
                    baseQuery = query,
                    formatFilter = stateSnapshot.selectedFormatFilter,
                    taskFilter = stateSnapshot.selectedTaskFilter,
                ).ifBlank { "GGUF" }

                val shouldScanExtraPages = hasActiveLocalFilters(stateSnapshot)
                val mapped = mutableListOf<HfModelInfo>()
                val seenModelIds = linkedSetOf<String>()
                var remoteHasNext = false

                if (shouldScanExtraPages) {
                    val requiredFilteredResults = ((requestedPageIndex + 1) * PAGE_SIZE) + PAGE_SIZE
                    var remotePage = 0

                    while (remotePage < MAX_FILTER_SCAN_PAGES) {
                        val results = huggingFaceApi.searchModels(
                            query = remoteQuery,
                            filter = null,
                            sort = sort.apiSort,
                            direction = sort.apiDirection,
                            limit = PAGE_SIZE,
                            offset = (remotePage * PAGE_SIZE),
                        )

                        remoteHasNext = results.size >= PAGE_SIZE
                        if (results.isEmpty()) break

                        val mappedBatch = results
                            .map(::mapModelResponse)
                            .filter { seenModelIds.add(it.modelId) }
                        mapped += mappedBatch

                        val filteredCount = applyLocalFilters(
                            models = mapped,
                            taskFilter = stateSnapshot.selectedTaskFilter,
                            formatFilter = stateSnapshot.selectedFormatFilter,
                            sizeFilter = stateSnapshot.selectedSizeFilter,
                        ).size

                        if (!remoteHasNext || filteredCount >= requiredFilteredResults) {
                            break
                        }

                        remotePage += 1
                    }
                } else {
                    val results = huggingFaceApi.searchModels(
                        query = remoteQuery,
                        filter = null,
                        sort = sort.apiSort,
                        direction = sort.apiDirection,
                        limit = PAGE_SIZE,
                        offset = (requestedPageIndex * PAGE_SIZE),
                    )
                    remoteHasNext = results.size >= PAGE_SIZE
                    mapped += results.map(::mapModelResponse)
                }

                if (requestId != searchRequestCounter) return@launch

                _uiState.update { current ->
                    val taskFilters = buildTaskFilters(mapped)
                    val selectedTask = current.selectedTaskFilter
                        .takeIf { it in taskFilters }
                        ?: TASK_FILTER_ALL

                    val filteredAll = applyLocalFilters(
                        models = mapped,
                        taskFilter = selectedTask,
                        formatFilter = current.selectedFormatFilter,
                        sizeFilter = current.selectedSizeFilter,
                    )

                    val maxPage = if (filteredAll.isEmpty()) 0 else (filteredAll.size - 1) / PAGE_SIZE
                    val resolvedPage = requestedPageIndex.coerceIn(0, maxPage)
                    val pageStart = resolvedPage * PAGE_SIZE
                    val pagedResults = filteredAll.drop(pageStart).take(PAGE_SIZE)

                    val hasLocalNextPage = pageStart + pagedResults.size < filteredAll.size
                    val hasNext = hasLocalNextPage || remoteHasNext
                    val estimatedTotal = if (!hasNext) {
                        pageStart + pagedResults.size
                    } else {
                        maxOf(filteredAll.size, pageStart + pagedResults.size + PAGE_SIZE)
                    }

                    current.copy(
                        isSearching = false,
                        isSearchingLive = false,
                        rawSearchResults = mapped,
                        searchResults = pagedResults,
                        currentPage = resolvedPage,
                        hasNextPage = hasNext,
                        totalResults = estimatedTotal,
                        availableTaskFilters = taskFilters,
                        selectedTaskFilter = selectedTask,
                        compatibilityByModelId = computeCompatibilityMap(pagedResults, current.deviceProfile),
                        lastSearchResultsCount = estimatedTotal,
                    )
                }
            } catch (e: Exception) {
                if (requestId != searchRequestCounter) return@launch
                _uiState.update {
                    it.copy(isSearching = false, isSearchingLive = false, error = "Search failed: ${e.message}")
                }
            }
        }
    }

    private fun applyLocalFiltersToCurrentPage() {
        _uiState.update { current ->
            val availableTasks = buildTaskFilters(current.rawSearchResults)
            val selectedTask = current.selectedTaskFilter
                .takeIf { it in availableTasks }
                ?: TASK_FILTER_ALL
            val filteredAll = applyLocalFilters(
                models = current.rawSearchResults,
                taskFilter = selectedTask,
                formatFilter = current.selectedFormatFilter,
                sizeFilter = current.selectedSizeFilter,
            )

            val maxPage = if (filteredAll.isEmpty()) 0 else (filteredAll.size - 1) / current.pageSize
            val resolvedPage = current.currentPage.coerceIn(0, maxPage)
            val pageStart = resolvedPage * current.pageSize
            val pagedResults = filteredAll.drop(pageStart).take(current.pageSize)
            val hasNext = pageStart + pagedResults.size < filteredAll.size

            current.copy(
                searchResults = pagedResults,
                availableTaskFilters = availableTasks,
                selectedTaskFilter = selectedTask,
                currentPage = resolvedPage,
                hasNextPage = hasNext,
                totalResults = filteredAll.size,
                compatibilityByModelId = computeCompatibilityMap(pagedResults, current.deviceProfile),
                lastSearchResultsCount = filteredAll.size,
            )
        }
    }

    private fun hasActiveLocalFilters(state: MarketplaceUiState): Boolean {
        return state.selectedTaskFilter != TASK_FILTER_ALL ||
            state.selectedFormatFilter != ModelFormatFilter.ANY ||
            state.selectedSizeFilter != ParameterSizeFilter.ANY
    }

    private fun buildRemoteSearchQuery(
        baseQuery: String,
        formatFilter: ModelFormatFilter,
        taskFilter: String,
    ): String {
        val terms = mutableListOf<String>()
        val trimmedBase = baseQuery.trim()
        if (trimmedBase.isNotBlank()) {
            terms += trimmedBase
        }

        when (formatFilter) {
            ModelFormatFilter.ANY -> Unit
            ModelFormatFilter.GGUF -> terms += "gguf"
            ModelFormatFilter.SAFETENSORS -> terms += "safetensors"
            ModelFormatFilter.DIFFUSERS -> terms += "diffusers"
        }

        if (taskFilter != TASK_FILTER_ALL) {
            terms += taskFilter
        }

        return terms
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(separator = " ")
    }

    private fun buildTaskFilters(models: List<HfModelInfo>): List<String> {
        val pipelineTags = models
            .mapNotNull { model ->
                model.pipelineTag
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()
            .sorted()
        return listOf(TASK_FILTER_ALL) + pipelineTags
    }

    private fun applyLocalFilters(
        models: List<HfModelInfo>,
        taskFilter: String,
        formatFilter: ModelFormatFilter,
        sizeFilter: ParameterSizeFilter,
    ): List<HfModelInfo> {
        return models.filter { model ->
            val taskMatch = taskFilter == TASK_FILTER_ALL || model.pipelineTag.equals(taskFilter, ignoreCase = true)
            val formatMatch = when (formatFilter) {
                ModelFormatFilter.ANY -> true
                else -> inferModelFormats(model).contains(formatFilter)
            }
            val sizeMatch = when (sizeFilter) {
                ParameterSizeFilter.ANY -> true
                else -> {
                    val paramsB = extractParamCountInBillions(model)
                    if (paramsB == null) false else {
                        when (sizeFilter) {
                            ParameterSizeFilter.ANY -> true
                            ParameterSizeFilter.UP_TO_3B -> paramsB <= 3.0
                            ParameterSizeFilter.BETWEEN_3B_AND_8B -> paramsB > 3.0 && paramsB <= 8.0
                            ParameterSizeFilter.BETWEEN_8B_AND_20B -> paramsB > 8.0 && paramsB <= 20.0
                            ParameterSizeFilter.ABOVE_20B -> paramsB > 20.0
                        }
                    }
                }
            }

            taskMatch && formatMatch && sizeMatch
        }
    }

    private fun inferModelFormats(model: HfModelInfo): Set<ModelFormatFilter> {
        val files = model.siblings.map { it.rfilename.lowercase() }
        val hasGguf = files.any { it.endsWith(".gguf") }
        val hasSafeTensors = files.any { it.endsWith(".safetensors") }
        val hasDiffusersMarker = files.any { it == "model_index.json" || it.endsWith("/model_index.json") }

        val formats = mutableSetOf<ModelFormatFilter>()
        if (hasGguf) formats += ModelFormatFilter.GGUF
        if (hasSafeTensors && hasDiffusersMarker) {
            formats += ModelFormatFilter.DIFFUSERS
        } else if (hasSafeTensors) {
            formats += ModelFormatFilter.SAFETENSORS
        }
        return formats
    }

    private fun refreshDeviceProfile() {
        val profile = runCatching { detectDeviceProfile() }.getOrElse {
            DeviceProfile(recommendationLabel = "Unable to detect device profile")
        }
        _uiState.update { current ->
            current.copy(
                deviceProfile = profile,
                compatibilityByModelId = computeCompatibilityMap(current.searchResults, profile),
            )
        }
    }

    private fun detectDeviceProfile(): DeviceProfile {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }

        val totalRamGbRaw = memoryInfo.totalMem / BYTES_PER_GIB
        val availableRamGbRaw = memoryInfo.availMem / BYTES_PER_GIB
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val vulkanSupported = isVulkanRuntimeSupported()
        val gpuHint = detectGpuHint()

        val recommendedMaxParams = recommendMaxParamsBudget(
            totalRamGb = totalRamGbRaw,
            cpuCores = cpuCores,
            vulkanSupported = vulkanSupported,
            gpuHint = gpuHint,
        )

        val recommendation = buildString {
            append("Best target: <=")
            append(formatParamBudget(recommendedMaxParams))
            append(" models (Q4/Q5 GGUF)")
        }

        return DeviceProfile(
            totalRamGb = totalRamGbRaw.roundToInt().coerceAtLeast(0),
            availableRamGb = availableRamGbRaw.roundToInt().coerceAtLeast(0),
            cpuCores = cpuCores,
            gpuHint = gpuHint,
            vulkanSupported = vulkanSupported,
            recommendedMaxParamsB = recommendedMaxParams,
            recommendationLabel = recommendation,
        )
    }

    private fun detectGpuHint(): String {
        val socManufacturer = readBuildField("SOC_MANUFACTURER")
        val socModel = readBuildField("SOC_MODEL")
        val fingerprint = listOfNotNull(
            Build.HARDWARE,
            Build.BOARD,
            Build.DEVICE,
            Build.PRODUCT,
            socManufacturer,
            socModel,
        ).joinToString(separator = " ").lowercase()

        return when {
            fingerprint.contains("adreno") || fingerprint.contains("qcom") || fingerprint.contains("qualcomm") -> "Adreno / Qualcomm"
            fingerprint.contains("mali") -> "Mali"
            fingerprint.contains("xclipse") -> "Xclipse"
            fingerprint.contains("powervr") -> "PowerVR"
            else -> "Unknown"
        }
    }

    private fun isVulkanRuntimeSupported(): Boolean {
        val packageManager = appContext.packageManager ?: return false
        return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    private fun readBuildField(fieldName: String): String? {
        return runCatching {
            Build::class.java.getField(fieldName).get(null)?.toString()
        }.getOrNull()
    }

    private fun recommendMaxParamsBudget(
        totalRamGb: Double,
        cpuCores: Int,
        vulkanSupported: Boolean,
        gpuHint: String,
    ): Double {
        var budget = when {
            totalRamGb >= 14.0 -> 13.0
            totalRamGb >= 10.0 -> 8.0
            totalRamGb >= 8.0 -> 7.0
            totalRamGb >= 6.0 -> 4.0
            totalRamGb >= 4.0 -> 2.5
            else -> 1.5
        }

        if (cpuCores < 6) budget *= 0.85
        if (!vulkanSupported) budget *= 0.85
        if (gpuHint.contains("Adreno", ignoreCase = true) && vulkanSupported) budget *= 1.1

        return budget.coerceIn(1.0, 20.0)
    }

    private fun computeCompatibilityMap(
        models: List<HfModelInfo>,
        profile: DeviceProfile,
    ): Map<String, ModelCompatibility> {
        return models.associate { model ->
            model.modelId to evaluateModelCompatibility(model, profile)
        }
    }

    private fun evaluateModelCompatibility(
        model: HfModelInfo,
        profile: DeviceProfile,
    ): ModelCompatibility {
        val paramsB = extractParamCountInBillions(model)
        val hasGguf = model.siblings.any { it.rfilename.endsWith(".gguf", ignoreCase = true) }
        val target = profile.recommendedMaxParamsB

        if (paramsB == null) {
            return ModelCompatibility(
                tier = CompatibilityTier.UNKNOWN,
                reason = "No parameter hint in model metadata",
            )
        }

        var tier = when {
            paramsB <= target -> CompatibilityTier.RECOMMENDED
            paramsB <= target * 1.35 -> CompatibilityTier.POSSIBLE
            else -> CompatibilityTier.HEAVY
        }

        if (!hasGguf && tier == CompatibilityTier.RECOMMENDED) {
            tier = CompatibilityTier.POSSIBLE
        }

        val reason = buildString {
            append(formatParamBudget(paramsB))
            append(" model vs ")
            append(formatParamBudget(target))
            append(" device target")
            if (!hasGguf) {
                append(" (no GGUF file detected)")
            }
        }

        return ModelCompatibility(tier = tier, reason = reason)
    }

    private fun extractParamCountInBillions(modelInfo: HfModelInfo): Double? {
        val searchSpace = buildString {
            append(modelInfo.modelId)
            append(' ')
            append(modelInfo.tags.joinToString(" "))
            append(' ')
            append(modelInfo.cardData.values.joinToString(" "))
        }

        val regex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*[bB]\\b")
        return regex.find(searchSpace)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun formatParamBudget(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) {
            "${rounded.toInt()}B"
        } else {
            "${rounded}B"
        }
    }

    private fun openModelDetails(seedModel: HfModelInfo) {
        val requestedRepo = seedModel.modelId
        if (requestedRepo.isBlank()) {
            _uiState.update { it.copy(modelDetailsError = "Invalid model ID") }
            return
        }

        _uiState.update {
            it.copy(
                selectedModelDetails = seedModel.copy(
                    modelCardUrl = seedModel.modelCardUrl.ifBlank { buildModelCardUrl(requestedRepo) },
                ),
                isLoadingModelDetails = true,
                modelDetailsError = null,
            )
        }

        viewModelScope.launch {
            runCatching { huggingFaceApi.getModelInfo(requestedRepo) }
                .onSuccess { response ->
                    val mapped = mapModelResponse(response)
                    _uiState.update { current ->
                        if (current.selectedModelDetails?.modelId == requestedRepo) {
                            current.copy(
                                selectedModelDetails = mapped,
                                isLoadingModelDetails = false,
                                modelDetailsError = null,
                            )
                        } else {
                            current
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update { current ->
                        if (current.selectedModelDetails?.modelId == requestedRepo) {
                            current.copy(
                                isLoadingModelDetails = false,
                                modelDetailsError = "Unable to load full model details: ${e.message}",
                            )
                        } else {
                            current
                        }
                    }
                }
        }
    }

    private fun downloadModel(modelInfo: HfModelInfo, file: HfModelFile) {
        val downloadKey = "${modelInfo.modelId}/${file.rfilename}"
        viewModelScope.launch {
            if (_uiState.value.isDownloading.containsKey(downloadKey)) return@launch

            startDownloadTelemetry(
                downloadKey = downloadKey,
                modelId = modelInfo.modelId,
                fileName = file.rfilename,
                plannedFiles = listOf(file.rfilename),
                totalFiles = 1,
            )

            var localTargetFile: File? = null
            var persistedModelId: String? = null

            try {
                // Determine quantization from filename
                val quant = extractQuantization(file.rfilename)
                val paramCount = extractParamCount(modelInfo.modelId)
                val format = detectDownloadFormat(modelInfo, file)

                val existing = modelRepository.getModelByRepoAndFile(modelInfo.modelId, file.rfilename)
                val modelId = existing?.id ?: UUID.randomUUID().toString()
                persistedModelId = modelId

                val model = (existing ?: LlmModel(id = modelId)).copy(
                    id = modelId,
                    repoId = modelInfo.modelId,
                    fileName = file.rfilename,
                    displayName = buildDisplayName(modelInfo, format, quant),
                    format = format,
                    sizeBytes = file.size ?: existing?.sizeBytes ?: 0L,
                    quantization = if (format == ModelFormat.GGUF) quant else "",
                    downloadState = DownloadState.DOWNLOADING,
                    parameterCount = paramCount,
                    description = modelInfo.description,
                    downloadedAt = System.currentTimeMillis(),
                )

                modelRepository.saveModel(model)

                val token = settingsRepository.getHfToken().first()
                val authHeader = toBearerAuthHeader(token)

                val response = huggingFaceApi.downloadFile(
                    repoId = modelInfo.modelId,
                    fileName = file.rfilename,
                    auth = authHeader,
                )

                if (!response.isSuccessful) {
                    throw IOException("Download failed with HTTP ${response.code()}")
                }

                val body = response.body() ?: throw IOException("Empty download body")
                localTargetFile = buildModelOutputFile(modelInfo.modelId, file.rfilename)
                writeToDiskWithProgress(
                    downloadKey = downloadKey,
                    modelId = modelInfo.modelId,
                    fileName = file.rfilename,
                    responseBody = body,
                    targetFile = localTargetFile,
                    plannedFiles = listOf(file.rfilename),
                    currentFileIndex = 1,
                    totalFiles = 1,
                )

                modelRepository.updateDownloadState(
                    model.id,
                    DownloadState.DOWNLOADED,
                    localTargetFile.absolutePath,
                )
            } catch (e: Exception) {
                localTargetFile?.takeIf { it.exists() }?.delete()
                persistedModelId?.let { modelRepository.updateDownloadState(it, DownloadState.FAILED, null) }
                _uiState.update {
                    it.copy(
                        error = "Download failed: ${e.message ?: "unknown error"}",
                    )
                }
            } finally {
                finishDownloadTelemetry(downloadKey)
            }
        }
    }

    private fun downloadDiffusersBundle(modelInfo: HfModelInfo) {
        val downloadKey = "${modelInfo.modelId}/__diffusers_bundle__"
        viewModelScope.launch {
            if (_uiState.value.isDownloading.containsKey(downloadKey)) return@launch

            var persistedModelId: String? = null
            var outputRoot: File? = null

            try {
                val fullInfo = huggingFaceApi.getModelInfo(modelInfo.modelId)
                val siblings = fullInfo.siblings?.map { HfModelFile(it.rfilename, it.size) }
                    ?: modelInfo.siblings
                val filesToDownload = selectDiffusersBundleFiles(siblings)
                require(filesToDownload.isNotEmpty()) {
                    "No Diffusers bundle files found for ${modelInfo.modelId}"
                }
                val plannedFiles = filesToDownload.map { it.rfilename }

                startDownloadTelemetry(
                    downloadKey = downloadKey,
                    modelId = modelInfo.modelId,
                    fileName = plannedFiles.first(),
                    plannedFiles = plannedFiles,
                    totalFiles = plannedFiles.size,
                )

                val modelId = "diff_${modelInfo.modelId.hashCode()}"
                persistedModelId = modelId
                outputRoot = buildModelDirectory(modelInfo.modelId)

                val model = LlmModel(
                    id = modelId,
                    repoId = modelInfo.modelId,
                    fileName = "model_index.json",
                    displayName = "${modelInfo.modelId.substringAfterLast("/")} (Diffusers)",
                    format = ModelFormat.DIFFUSERS,
                    sizeBytes = filesToDownload.sumOf { it.size ?: 0L },
                    quantization = "",
                    downloadState = DownloadState.DOWNLOADING,
                    parameterCount = extractParamCount(modelInfo.modelId),
                    localPath = outputRoot.absolutePath,
                    description = modelInfo.description,
                    downloadedAt = System.currentTimeMillis(),
                )
                modelRepository.saveModel(model)

                val token = settingsRepository.getHfToken().first()
                val authHeader = toBearerAuthHeader(token)
                val totalFiles = filesToDownload.size.coerceAtLeast(1)

                filesToDownload.forEachIndexed { index, bundleFile ->
                    val response = huggingFaceApi.downloadFile(
                        repoId = modelInfo.modelId,
                        fileName = bundleFile.rfilename,
                        auth = authHeader,
                    )
                    if (!response.isSuccessful) {
                        throw IOException("Diffusers download failed for ${bundleFile.rfilename}: HTTP ${response.code()}")
                    }

                    val body = response.body() ?: throw IOException("Empty body for ${bundleFile.rfilename}")
                    val targetFile = buildModelOutputFile(modelInfo.modelId, bundleFile.rfilename)

                    writeToDiskWithProgress(
                        downloadKey = downloadKey,
                        modelId = modelInfo.modelId,
                        fileName = bundleFile.rfilename,
                        responseBody = body,
                        targetFile = targetFile,
                        plannedFiles = plannedFiles,
                        currentFileIndex = index + 1,
                        totalFiles = totalFiles,
                        progressPrefix = index.toFloat() / totalFiles.toFloat(),
                        progressSpan = 1f / totalFiles.toFloat(),
                    )
                }

                modelRepository.updateDownloadState(
                    modelId,
                    DownloadState.DOWNLOADED,
                    outputRoot.absolutePath,
                )
            } catch (e: Exception) {
                outputRoot?.takeIf { it.exists() }?.deleteRecursively()
                persistedModelId?.let { modelRepository.updateDownloadState(it, DownloadState.FAILED, null) }
                _uiState.update { it.copy(error = "Diffusers bundle download failed: ${e.message ?: "unknown error"}") }
            } finally {
                finishDownloadTelemetry(downloadKey)
            }
        }
    }

    private fun startDownloadTelemetry(
        downloadKey: String,
        modelId: String,
        fileName: String,
        plannedFiles: List<String>,
        totalFiles: Int,
    ) {
        val now = System.currentTimeMillis()
        val telemetry = DownloadTelemetry(
            key = downloadKey,
            modelId = modelId,
            fileName = fileName,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = null,
            speedBytesPerSec = 0L,
            currentFileIndex = 1,
            totalFiles = totalFiles.coerceAtLeast(1),
            plannedFiles = plannedFiles,
            startedAtMs = now,
        )
        _uiState.update { current ->
            current.copy(
                isDownloading = current.isDownloading + (downloadKey to 0f),
                activeDownloads = current.activeDownloads + (downloadKey to telemetry),
                error = null,
            )
        }
    }

    private fun updateDownloadTelemetry(
        downloadKey: String,
        modelId: String,
        fileName: String,
        overallProgress: Float,
        bytesDownloaded: Long,
        totalBytes: Long?,
        speedBytesPerSec: Long,
        currentFileIndex: Int,
        totalFiles: Int,
        plannedFiles: List<String>,
    ) {
        _uiState.update { current ->
            val existing = current.activeDownloads[downloadKey]
            val telemetry = (existing ?: DownloadTelemetry(
                key = downloadKey,
                modelId = modelId,
                fileName = fileName,
                plannedFiles = plannedFiles,
                totalFiles = totalFiles,
            )).copy(
                modelId = modelId,
                fileName = fileName,
                progress = overallProgress.coerceIn(0f, 1f),
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                speedBytesPerSec = speedBytesPerSec.coerceAtLeast(0L),
                currentFileIndex = currentFileIndex.coerceIn(1, totalFiles.coerceAtLeast(1)),
                totalFiles = totalFiles.coerceAtLeast(1),
                plannedFiles = plannedFiles,
            )

            current.copy(
                isDownloading = current.isDownloading + (downloadKey to telemetry.progress),
                activeDownloads = current.activeDownloads + (downloadKey to telemetry),
            )
        }
    }

    private fun finishDownloadTelemetry(downloadKey: String) {
        _uiState.update { current ->
            current.copy(
                isDownloading = current.isDownloading - downloadKey,
                activeDownloads = current.activeDownloads - downloadKey,
            )
        }
    }

    private fun buildModelDirectory(repoId: String): File {
        val modelsDir = resolveModelsRootDirectory()
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val segments = repoId
            .split('/')
            .map(::sanitizePathSegment)
            .filter { it.isNotBlank() }
        return segments.fold(modelsDir) { current, segment ->
            File(current, segment)
        }.also { it.mkdirs() }
    }

    private fun buildModelOutputFile(repoId: String, remoteFileName: String): File {
        val modelRoot = buildModelDirectory(repoId)
        val normalizedSegments = remoteFileName
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() }
            .map(::sanitizePathSegment)

        val output = normalizedSegments.fold(modelRoot) { current, segment ->
            File(current, segment)
        }
        output.parentFile?.mkdirs()
        return output
    }

    private fun resolveModelsRootDirectory(): File {
        val configuredPath = modelStorageRootPath
        if (configuredPath.isNotBlank()) {
            val configured = File(configuredPath)
            if (configured.exists() || configured.mkdirs()) {
                return configured
            }
        }

        val externalDownloads = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val root = File(externalDownloads ?: appContext.filesDir, "MasterLLM/models/hf")
        if (!root.exists()) root.mkdirs()
        return root
    }

    private fun sanitizePathSegment(segment: String): String {
        return segment
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "_" }
    }

    private fun mapModelResponse(response: com.masterllm.core.network.model.HfModelResponse): HfModelInfo {
        val modelId = response.id ?: response.modelId ?: ""
        return HfModelInfo(
            modelId = modelId,
            author = response.author ?: "",
            sha = response.sha ?: "",
            downloads = response.downloads,
            likes = response.likes,
            tags = response.tags ?: emptyList(),
            pipelineTag = response.pipelineTag ?: "",
            siblings = response.siblings?.map { sibling ->
                HfModelFile(rfilename = sibling.rfilename, size = sibling.size)
            } ?: emptyList(),
            lastModified = response.lastModified ?: "",
            isPrivate = response.isPrivate,
            description = response.description ?: "",
            cardData = response.cardData
                ?.mapNotNull { (key, value) ->
                    val parsed = when (value) {
                        is String -> value
                        is Number, is Boolean -> value.toString()
                        else -> null
                    }
                    parsed?.let { key to it }
                }
                ?.toMap()
                ?: emptyMap(),
            modelCardUrl = buildModelCardUrl(modelId),
        )
    }

    private fun buildModelCardUrl(modelId: String): String {
        if (modelId.isBlank()) return ""
        return "https://huggingface.co/$modelId"
    }

    private suspend fun writeToDiskWithProgress(
        downloadKey: String,
        modelId: String,
        fileName: String,
        responseBody: ResponseBody,
        targetFile: File,
        plannedFiles: List<String>,
        currentFileIndex: Int,
        totalFiles: Int,
        progressPrefix: Float = 0f,
        progressSpan: Float = 1f,
    ) = withContext(Dispatchers.IO) {
        val totalBytes = responseBody.contentLength().takeIf { it > 0L }
        var copiedBytes = 0L
        var lastSampleTimeMs = System.currentTimeMillis()
        var lastSampleBytes = 0L

        responseBody.byteStream().use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copiedBytes += read

                    val nowMs = System.currentTimeMillis()
                    val localProgress = if (totalBytes != null) {
                        (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val overallProgress = (progressPrefix + localProgress * progressSpan).coerceIn(0f, 1f)

                    val shouldSample = (nowMs - lastSampleTimeMs) >= PROGRESS_SAMPLE_INTERVAL_MS
                    if (shouldSample) {
                        val deltaBytes = copiedBytes - lastSampleBytes
                        val deltaMs = (nowMs - lastSampleTimeMs).coerceAtLeast(1L)
                        val speedBytesPerSec = (deltaBytes * 1000L) / deltaMs

                        updateDownloadTelemetry(
                            downloadKey = downloadKey,
                            modelId = modelId,
                            fileName = fileName,
                            overallProgress = overallProgress,
                            bytesDownloaded = copiedBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speedBytesPerSec,
                            currentFileIndex = currentFileIndex,
                            totalFiles = totalFiles,
                            plannedFiles = plannedFiles,
                        )

                        lastSampleTimeMs = nowMs
                        lastSampleBytes = copiedBytes
                    }
                }
                output.flush()
            }
        }

        updateDownloadTelemetry(
            downloadKey = downloadKey,
            modelId = modelId,
            fileName = fileName,
            overallProgress = (progressPrefix + progressSpan).coerceIn(0f, 1f),
            bytesDownloaded = copiedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = 0L,
            currentFileIndex = currentFileIndex,
            totalFiles = totalFiles,
            plannedFiles = plannedFiles,
        )
    }

    private fun deleteModel(id: String) {
        viewModelScope.launch {
            try {
                modelRepository.deleteModel(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    private fun extractQuantization(filename: String): String {
        val regex = Regex("(Q[0-9]+_[A-Z_]+|Q[0-9]+|F16|F32|IQ[0-9]+_[A-Z]+)", RegexOption.IGNORE_CASE)
        return regex.find(filename)?.value ?: "Unknown"
    }

    private fun extractParamCount(modelId: String): String {
        val regex = Regex("([0-9]+\\.?[0-9]*)[Bb]")
        return regex.find(modelId)?.groupValues?.get(1)?.let { "${it}B" } ?: ""
    }

    private fun buildDisplayName(modelInfo: HfModelInfo, format: ModelFormat, quantization: String): String {
        val base = modelInfo.modelId.substringAfterLast("/")
        return when (format) {
            ModelFormat.GGUF -> "$base ($quantization)"
            ModelFormat.SAFETENSORS -> "$base (SafeTensors)"
            ModelFormat.DIFFUSERS -> "$base (Diffusers)"
        }
    }

    private fun detectDownloadFormat(modelInfo: HfModelInfo, file: HfModelFile): ModelFormat {
        val name = file.rfilename.lowercase()
        if (name.endsWith(".gguf")) return ModelFormat.GGUF
        if (name.endsWith(".safetensors")) {
            return if (isDiffusersModel(modelInfo.siblings)) ModelFormat.DIFFUSERS else ModelFormat.SAFETENSORS
        }
        throw IllegalArgumentException("Unsupported model file format: ${file.rfilename}")
    }

    private fun isDiffusersModel(siblings: List<HfModelFile>): Boolean {
        val files = siblings.map { it.rfilename.lowercase() }
        val hasModelIndex = files.any { it == "model_index.json" || it.endsWith("/model_index.json") }
        val hasSafetensors = files.any { it.endsWith(".safetensors") }
        return hasModelIndex && hasSafetensors
    }

    private fun selectDiffusersBundleFiles(siblings: List<HfModelFile>): List<HfModelFile> {
        val includeRegexes = listOf(
            Regex("(^|/)model_index\\.json$", RegexOption.IGNORE_CASE),
            Regex("(^|/)scheduler/.*\\.json$", RegexOption.IGNORE_CASE),
            Regex("(^|/)tokenizer/.*", RegexOption.IGNORE_CASE),
            Regex("(^|/)tokenizer_2/.*", RegexOption.IGNORE_CASE),
            Regex("(^|/)text_encoder/.*", RegexOption.IGNORE_CASE),
            Regex("(^|/)text_encoder_2/.*", RegexOption.IGNORE_CASE),
            Regex("(^|/)unet/.*", RegexOption.IGNORE_CASE),
            Regex("(^|/)vae/.*", RegexOption.IGNORE_CASE),
            Regex("(^|/)safety_checker/.*", RegexOption.IGNORE_CASE),
            Regex("\\.safetensors$", RegexOption.IGNORE_CASE),
            Regex("(^|/)(config|special_tokens_map|tokenizer_config)\\.json$", RegexOption.IGNORE_CASE),
            Regex("(^|/)(vocab|merges)\\.(json|txt)$", RegexOption.IGNORE_CASE),
        )

        return siblings
            .distinctBy { it.rfilename }
            .filter { file -> includeRegexes.any { it.containsMatchIn(file.rfilename) } }
    }
}
