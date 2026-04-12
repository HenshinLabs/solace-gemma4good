package com.masterllm.feature.marketplace

import android.content.Context
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

data class MarketplaceUiState(
    val searchQuery: String = "",
    val searchResults: List<HfModelInfo> = emptyList(),
    val downloadedModels: List<LlmModel> = emptyList(),
    val isSearching: Boolean = false,
    val isDownloading: Map<String, Float> = emptyMap(), // modelId → progress 0..1
    val error: String? = null,
    val selectedTab: Int = 0, // 0=Browse, 1=Downloaded
    val selectedModelDetails: HfModelInfo? = null,
    val isLoadingModelDetails: Boolean = false,
    val modelDetailsError: String? = null,
)

sealed interface MarketplaceAction {
    data class SearchQueryChanged(val query: String) : MarketplaceAction
    data object Search : MarketplaceAction
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
    @Volatile
    private var modelStorageRootPath: String = ""

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
        // Load popular GGUF models on start
        searchModels("GGUF")
    }

    fun onAction(action: MarketplaceAction) {
        when (action) {
            is MarketplaceAction.SearchQueryChanged ->
                _uiState.update { it.copy(searchQuery = action.query) }
            MarketplaceAction.Search -> searchModels(_uiState.value.searchQuery)
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

    private fun searchModels(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            try {
                val results = huggingFaceApi.searchModels(
                    query = query,
                    filter = null,
                    limit = 20,
                )
                val mapped = results.map(::mapModelResponse)
                _uiState.update { it.copy(isSearching = false, searchResults = mapped) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSearching = false, error = "Search failed: ${e.message}")
                }
            }
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

            _uiState.update {
                it.copy(isDownloading = it.isDownloading + (downloadKey to 0f), error = null)
            }

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
                writeToDiskWithProgress(downloadKey, body, localTargetFile)

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
                _uiState.update {
                    it.copy(isDownloading = it.isDownloading - downloadKey)
                }
            }
        }
    }

    private fun downloadDiffusersBundle(modelInfo: HfModelInfo) {
        val downloadKey = "${modelInfo.modelId}/__diffusers_bundle__"
        viewModelScope.launch {
            if (_uiState.value.isDownloading.containsKey(downloadKey)) return@launch

            _uiState.update {
                it.copy(isDownloading = it.isDownloading + (downloadKey to 0f), error = null)
            }

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
                        responseBody = body,
                        targetFile = targetFile,
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
                _uiState.update { it.copy(isDownloading = it.isDownloading - downloadKey) }
            }
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
        responseBody: ResponseBody,
        targetFile: File,
        progressPrefix: Float = 0f,
        progressSpan: Float = 1f,
    ) = withContext(Dispatchers.IO) {
        val totalBytes = responseBody.contentLength().takeIf { it > 0L }
        var copiedBytes = 0L

        responseBody.byteStream().use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copiedBytes += read

                    if (totalBytes != null) {
                        val localProgress = (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        val progress = (progressPrefix + localProgress * progressSpan).coerceIn(0f, 1f)
                        _uiState.update {
                            it.copy(isDownloading = it.isDownloading + (downloadKey to progress))
                        }
                    }
                }
                output.flush()
            }
        }
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
