package com.masterllm.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ConversationRepository
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.data.BundledModelManager
import com.masterllm.core.data.VoskSpeechManager
import com.masterllm.core.data.VoskModelDownloadManager
import com.masterllm.core.data.TtsTextFilter
import com.masterllm.core.data.KittenTtsEngine
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.core.ollama.api.OllamaApiService
import com.masterllm.core.ollama.model.OllamaModelInfo
import kotlinx.coroutines.flow.catch
import com.masterllm.runtime.gguf.GgufEngine
import com.masterllm.runtime.gguf.GgufRuntimeCoordinator
import com.masterllm.runtime.gguf.OpenClawEngine
import com.masterllm.runtime.gguf.ToolRegistry
import com.masterllm.runtime.gguf.PerformanceUsageSampler
import com.masterllm.runtime.imagegen.ImageGenEngine
import com.masterllm.runtime.imagegen.ImageGenProgress
import com.masterllm.runtime.safetensors.SafetensorsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

enum class ChatBackend { LOCAL, OLLAMA }

data class GenerationStats(
    val modelDisplayName: String,
    val backend: String,
    val modelLoadDurationMs: Long,
    val replayedMessages: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val generatedChars: Int,
    val firstTokenLatencyMs: Long,
    val durationMs: Long,
    val promptTokensPerSecond: Double,
    val nativePromptTokensPerSecond: Float,
    val decodeTokensPerSecond: Double,
    val nativeTokensPerSecond: Float,
    val promptCpuUsagePercent: Double,
    val decodeCpuUsagePercent: Double,
    val promptGpuUsagePercent: Double?,
    val decodeGpuUsagePercent: Double?,
    val threadCount: Int,
    val gpuLayers: Int,
    val contextSize: Int,
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
)

enum class ModelLoadStatus {
    IDLE,
    LOADING,
    LOADED,
    ERROR,
}

data class ModelRuntimeInfo(
    val modelId: String? = null,
    val modelDisplayName: String = "No model selected",
    val status: ModelLoadStatus = ModelLoadStatus.IDLE,
    val backend: String = "Not loaded",
    val modelPath: String? = null,
    val loadDurationMs: Long? = null,
    val threadCount: Int = 4,
    val contextSize: Int = 2048,
    val gpuAccelerationEnabled: Boolean = false,
    val gpuOffloadLayers: Int = 0,
    val cpuExecutionEnabled: Boolean = true,
    val offloadSummary: String = "CPU execution",
    val note: String = "Select a model to preload.",
    val lastError: String? = null,
)

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val currentConversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val availableModels: List<LlmModel> = emptyList(),
    val selectedModelId: String? = null,
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val generationStatus: String? = null,
    val showConversationList: Boolean = true,
    val error: String? = null,
    val lastGenerationStats: GenerationStats? = null,
    val inferenceParams: InferenceParams = InferenceParams(),
    val showModelConfig: Boolean = false,
    val selectedModelInfo: LlmModel? = null,
    val modelRuntime: ModelRuntimeInfo = ModelRuntimeInfo(),
    val benchmarkRunning: Boolean = false,
    val benchmarkResult: String? = null,
    val chatBackend: ChatBackend = ChatBackend.LOCAL,
    val ollamaConnected: Boolean = false,
    val ollamaModels: List<OllamaModelInfo> = emptyList(),
    val ollamaSelectedModel: String = "",
    val pendingImageAttachment: String? = null,
    val asrState: VoskSpeechManager.AsrState = VoskSpeechManager.AsrState(),
    val ttsEnabled: Boolean = true,
    val isSpeaking: Boolean = false,
    val voskModelReady: Boolean = false,
)

sealed interface ChatAction {
    data class InputChanged(val text: String) : ChatAction
    data object SendMessage : ChatAction
    data object StopGeneration : ChatAction
    data class SelectConversation(val id: String) : ChatAction
    data object NewConversation : ChatAction
    data class DeleteConversation(val id: String) : ChatAction
    data class SelectModel(val modelId: String) : ChatAction
    data object BackToList : ChatAction
    data object DismissError : ChatAction
    data object ShowModelConfig : ChatAction
    data object RefreshModelRuntime : ChatAction
    data object HideModelConfig : ChatAction
    data class UpdateTemperature(val value: Float) : ChatAction
    data class UpdateTopP(val value: Float) : ChatAction
    data class UpdateTopK(val value: Int) : ChatAction
    data class UpdateRepeatPenalty(val value: Float) : ChatAction
    data class UpdateMaxTokens(val value: Int) : ChatAction
    data class UpdateSystemPrompt(val value: String) : ChatAction
    data object ResetInferenceParams : ChatAction
    data class ApplyTaskTemplate(val systemPrompt: String, val starterPrompt: String) : ChatAction
    data object RunBenchmark : ChatAction
    data object ClearBenchmarkResult : ChatAction
    data object ToggleBackend : ChatAction
    data object CheckOllamaConnection : ChatAction
    data class SelectOllamaModel(val modelName: String) : ChatAction
    data class AttachImage(val imagePath: String) : ChatAction
    data object ClearImageAttachment : ChatAction
    data object StartListening : ChatAction
    data object StopListening : ChatAction
    data object ToggleTts : ChatAction
    data object StopSpeaking : ChatAction
    data object DownloadVoskModel : ChatAction
    data class WebSearch(val query: String) : ChatAction
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val ggufEngine: GgufEngine,
    private val runtimeCoordinator: GgufRuntimeCoordinator,
    private val imageGenEngine: ImageGenEngine,
    private val safetensorsEngine: SafetensorsEngine,
    private val ollamaApiService: OllamaApiService,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // ASR and TTS - created manually
    private val voskSpeechManager = VoskSpeechManager(appContext)
    private val voskModelDownloadManager = VoskModelDownloadManager(appContext)
    private val kittenTtsEngine = KittenTtsEngine()

    // Agent tools - created manually
    private val toolRegistry = ToolRegistry(appContext)
    private val openClawEngine = OpenClawEngine(ggufEngine, toolRegistry)

    private data class EngineReadyResult(
        val model: LlmModel,
        val loadDurationMs: Long,
        val replayedMessages: Int,
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var generationJob: Job? = null
    private var benchmarkJob: Job? = null
    private var modelProbeJob: Job? = null
    private var loadedModelId: String? = null
    private var loadedInferenceSignature: InferenceParams? = null
    private var loadedImageModelId: String? = null
    private var gpuAccelerationEnabled: Boolean = false


    init {
        // Watch chat conversations (independent)
        viewModelScope.launch {
            conversationRepository.getConversationsByMode(ConversationMode.CHAT).collect { convos ->
                _uiState.update { it.copy(conversations = convos) }
            }
        }

        // Initialize ASR
        if (voskModelDownloadManager.isModelReady()) {
            voskSpeechManager.initModel { ready ->
                _uiState.update { it.copy(voskModelReady = ready) }
            }
        }

        // Observe ASR state
        viewModelScope.launch {
            voskSpeechManager.asrState.collect { asrState ->
                _uiState.update { it.copy(asrState = asrState) }
                if (asrState.state == VoskSpeechManager.ListeningState.IDLE &&
                    asrState.finalTranscript.isNotEmpty()
                ) {
                    _uiState.update { it.copy(inputText = asrState.finalTranscript) }
                }
            }
        }

        // Initialize TTS
        viewModelScope.launch {
            kittenTtsEngine.initialize(appContext)
        }

        // Read settings FIRST, then watch models to avoid race conditions
        viewModelScope.launch {
            // 1. Read initial settings synchronously before any model loads
            val initialThreadCount = settingsRepository.getDefaultThreadCount().first()
            val initialGpuEnabled = settingsRepository.getGpuAccelerationEnabled().first()
            gpuAccelerationEnabled = initialGpuEnabled
            val normalizedThreads = initialThreadCount.coerceAtLeast(1)
            val desiredGpuLayers = resolveDesiredGpuLayers()

            _uiState.update { state ->
                state.copy(
                    inferenceParams = state.inferenceParams.copy(numThreads = normalizedThreads),
                    modelRuntime = state.modelRuntime.copy(
                        threadCount = normalizedThreads,
                        contextSize = state.inferenceParams.contextSize ?: 2048,
                        gpuAccelerationEnabled = initialGpuEnabled,
                        gpuOffloadLayers = desiredGpuLayers,
                        offloadSummary = buildOffloadSummary(gpuEnabled = initialGpuEnabled, gpuLayers = desiredGpuLayers),
                    ),
                )
            }

            // 2. Now watch downloaded models (settings are already applied)
            launch {
                modelRepository.getDownloadedModels().collect { models ->
                    val filtered = models.filter {
                        it.downloadState == DownloadState.DOWNLOADED &&
                            (
                                it.format == ModelFormat.GGUF ||
                                    it.format == ModelFormat.SAFETENSORS ||
                                    it.format == ModelFormat.DIFFUSERS
                                )
                    }.toMutableList()
                    val bundled = getBundledModel()
                    if (bundled != null && filtered.none { it.id == bundled.id }) {
                        filtered.add(0, bundled)
                    }
                    var selectedModelId: String? = null
                    _uiState.update { state ->
                        val keepSelected = state.selectedModelId?.let { id ->
                            filtered.firstOrNull { it.id == id }
                        }
                        val selected = keepSelected?.id
                            ?: filtered.firstOrNull { it.format == ModelFormat.GGUF }?.id
                            ?: filtered.firstOrNull { it.format == ModelFormat.SAFETENSORS }?.id
                            ?: filtered.firstOrNull()?.id
                        selectedModelId = selected
                        state.copy(
                            availableModels = filtered,
                            selectedModelId = selected,
                        )
                    }
                    selectedModelId?.let { selected ->
                        val runtime = _uiState.value.modelRuntime
                        if (runtime.modelId != selected || runtime.status == ModelLoadStatus.ERROR) {
                            probeSelectedModel(selected, force = false)
                        }
                    }
                }
            }

            // 3. Continue watching settings for live changes
            combine(
                settingsRepository.getDefaultThreadCount(),
                settingsRepository.getGpuAccelerationEnabled(),
            ) { threadCount, gpuEnabled -> threadCount to gpuEnabled }
                .collect { (threadCount, gpuEnabled) ->
                    gpuAccelerationEnabled = gpuEnabled
                    val threads = threadCount.coerceAtLeast(1)
                    val layers = resolveDesiredGpuLayers()
                    _uiState.update { state ->
                        state.copy(
                            inferenceParams = state.inferenceParams.copy(numThreads = threads),
                            modelRuntime = state.modelRuntime.copy(
                                threadCount = threads,
                                contextSize = state.inferenceParams.contextSize ?: 2048,
                                gpuAccelerationEnabled = gpuEnabled,
                                gpuOffloadLayers = layers,
                                offloadSummary = buildOffloadSummary(gpuEnabled = gpuEnabled, gpuLayers = layers),
                            ),
                        )
                    }
                }
        }
    }

    fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.InputChanged -> _uiState.update { it.copy(inputText = action.text) }
            ChatAction.SendMessage -> sendMessage()
            ChatAction.StopGeneration -> stopGeneration()
            is ChatAction.SelectConversation -> selectConversation(action.id)
            ChatAction.NewConversation -> newConversation()
            is ChatAction.DeleteConversation -> deleteConversation(action.id)
            is ChatAction.SelectModel -> selectModel(action.modelId)
            ChatAction.BackToList -> _uiState.update {
                it.copy(showConversationList = true, currentConversation = null, messages = emptyList())
            }
            ChatAction.DismissError -> _uiState.update { it.copy(error = null) }
            ChatAction.ShowModelConfig -> showModelConfig()
            ChatAction.RefreshModelRuntime -> {
                _uiState.value.selectedModelId?.let { selected ->
                    probeSelectedModel(selected, force = true)
                }
            }
            ChatAction.HideModelConfig -> _uiState.update { it.copy(showModelConfig = false) }
            is ChatAction.UpdateTemperature -> updateInferenceParams { it.copy(temperature = action.value) }
            is ChatAction.UpdateTopP -> updateInferenceParams { it.copy(topP = action.value) }
            is ChatAction.UpdateTopK -> updateInferenceParams { it.copy(topK = action.value) }
            is ChatAction.UpdateRepeatPenalty -> updateInferenceParams { it.copy(repeatPenalty = action.value) }
            is ChatAction.UpdateMaxTokens -> updateInferenceParams { it.copy(maxTokens = action.value) }
            is ChatAction.UpdateSystemPrompt -> updateInferenceParams { it.copy(systemPrompt = action.value) }
            ChatAction.ResetInferenceParams -> _uiState.update { it.copy(inferenceParams = InferenceParams()) }
            is ChatAction.ApplyTaskTemplate -> applyTaskTemplate(action.systemPrompt, action.starterPrompt)
            ChatAction.RunBenchmark -> runBenchmark()
            ChatAction.ClearBenchmarkResult -> _uiState.update { it.copy(benchmarkResult = null) }
            ChatAction.ToggleBackend -> toggleBackend()
            ChatAction.CheckOllamaConnection -> checkOllamaConnection()
            is ChatAction.SelectOllamaModel -> selectOllamaModel(action.modelName)
            is ChatAction.AttachImage -> _uiState.update { it.copy(pendingImageAttachment = action.imagePath) }
            ChatAction.ClearImageAttachment -> _uiState.update { it.copy(pendingImageAttachment = null) }
            ChatAction.StartListening -> startListening()
            ChatAction.StopListening -> voskSpeechManager.stopListening()
            ChatAction.ToggleTts -> _uiState.update { it.copy(ttsEnabled = !it.ttsEnabled) }
            ChatAction.StopSpeaking -> {
                kittenTtsEngine.stop()
                _uiState.update { it.copy(isSpeaking = false) }
            }
            ChatAction.DownloadVoskModel -> downloadVoskModel()
            is ChatAction.WebSearch -> performWebSearch(action.query)
        }
    }

    private fun selectModel(modelId: String) {
        viewModelScope.launch {
            if (_uiState.value.isGenerating) {
                cancelGenerationIfRunning()
            }

            val model = modelRepository.getModelById(modelId)
                ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            _uiState.update {
                it.copy(
                    selectedModelId = modelId,
                    selectedModelInfo = model,
                )
            }

            _uiState.value.currentConversation?.let { conversation ->
                if (conversation.modelId != modelId) {
                    val updatedConversation = conversation.copy(
                        modelId = modelId,
                        updatedAt = System.currentTimeMillis(),
                    )
                    conversationRepository.updateConversation(updatedConversation)
                    _uiState.update { state -> state.copy(currentConversation = updatedConversation) }
                }
            }

            // Close current model so next generation loads the new one
            if (loadedModelId != null && loadedModelId != modelId) {
                runtimeCoordinator.withEngineLock {
                    ggufEngine.close()
                    loadedModelId = null
                    loadedInferenceSignature = null
                }
            }
            if (loadedImageModelId != null && loadedImageModelId != modelId) {
                imageGenEngine.unloadModel()
                loadedImageModelId = null
            }

            probeSelectedModel(modelId, force = true)
        }
    }

    private fun probeSelectedModel(modelId: String, force: Boolean) {
        val currentRuntime = _uiState.value.modelRuntime
        if (!force && currentRuntime.modelId == modelId && currentRuntime.status == ModelLoadStatus.LOADED) {
            return
        }

        modelProbeJob?.cancel()
        modelProbeJob = viewModelScope.launch {
            val model = modelRepository.getModelById(modelId)
                ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
                ?: return@launch

            val modelPath = model.localPath ?: "/data/models/${model.fileName}"
            _uiState.update { state ->
                state.copy(
                    selectedModelInfo = model,
                    modelRuntime = state.modelRuntime.copy(
                        modelId = model.id,
                        modelDisplayName = model.displayName.ifBlank { model.repoId },
                        status = ModelLoadStatus.LOADING,
                        backend = runtimeBackendLabelForModel(model.format),
                        modelPath = modelPath,
                        loadDurationMs = null,
                        note = "Loading model into memory...",
                        lastError = null,
                    ),
                )
            }

            try {
                val loadDurationMs = runtimeCoordinator.withEngineLock {
                    when (model.format) {
                        ModelFormat.GGUF -> {
                            val startedAt = System.nanoTime()
                            ggufEngine.load(
                                modelPath = modelPath,
                                params = _uiState.value.inferenceParams,
                                gpuAccelerationEnabled = gpuAccelerationEnabled,
                                gpuOffloadLayers = resolveDesiredGpuLayers(model),
                            )
                            loadedModelId = model.id
                            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                        }

                        ModelFormat.SAFETENSORS -> {
                            val startedAt = System.nanoTime()
                            safetensorsEngine.loadModel(modelPath).getOrElse { throw it }
                            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                        }

                        ModelFormat.DIFFUSERS -> {
                            val startedAt = System.nanoTime()
                            imageGenEngine.loadModel(modelPath).getOrElse { throw it }
                            loadedImageModelId = model.id
                            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                        }
                    }
                }

                val note = when (model.format) {
                    ModelFormat.GGUF -> "Model is loaded in memory and ready for text generation."
                    ModelFormat.SAFETENSORS -> {
                        val sameRepoFallback = TextRuntimeModelResolver.findSameRepoGgufFallback(
                            selectedModel = model,
                            availableModels = _uiState.value.availableModels,
                        )
                        if (sameRepoFallback != null) {
                            val fallbackName = sameRepoFallback.displayName.ifBlank { sameRepoFallback.repoId }
                            "SafeTensors validated. Chat inference will use same-repo GGUF fallback: $fallbackName"
                        } else {
                            "SafeTensors validated. Download a GGUF variant from the same repo, or convert this model offline with llama.cpp convert_hf_to_gguf.py."
                        }
                    }

                    ModelFormat.DIFFUSERS -> "Diffusers model is loaded in memory and ready for image generation."
                }

                val activeThreads = if (model.format == ModelFormat.GGUF && ggufEngine.isModelLoaded()) {
                    ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
                } else {
                    _uiState.value.inferenceParams.numThreads
                }
                val activeGpuLayers = if (model.format == ModelFormat.GGUF && ggufEngine.isModelLoaded()) {
                    ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)
                } else {
                    0
                }

                _uiState.update { state ->
                    state.copy(
                        modelRuntime = state.modelRuntime.copy(
                            modelId = model.id,
                            modelDisplayName = model.displayName.ifBlank { model.repoId },
                            status = ModelLoadStatus.LOADED,
                            backend = when (model.format) {
                                ModelFormat.GGUF -> resolveBackendLabel()
                                ModelFormat.SAFETENSORS -> "SafeTensors/validator"
                                ModelFormat.DIFFUSERS -> "Diffusers/ImageGenEngine"
                            },
                            modelPath = modelPath,
                            loadDurationMs = loadDurationMs,
                            threadCount = activeThreads,
                            contextSize = state.inferenceParams.contextSize ?: 2048,
                            gpuAccelerationEnabled = gpuAccelerationEnabled,
                            gpuOffloadLayers = activeGpuLayers,
                            cpuExecutionEnabled = true,
                            offloadSummary = buildOffloadSummary(gpuEnabled = gpuAccelerationEnabled, gpuLayers = activeGpuLayers),
                            note = note,
                            lastError = null,
                        ),
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "unknown error"
                _uiState.update { state ->
                    state.copy(
                        error = "Model load failed: $message",
                        modelRuntime = state.modelRuntime.copy(
                            modelId = model.id,
                            modelDisplayName = model.displayName.ifBlank { model.repoId },
                            status = ModelLoadStatus.ERROR,
                            backend = runtimeBackendLabelForModel(model.format),
                            modelPath = modelPath,
                            loadDurationMs = null,
                            note = "Model failed to load.",
                            lastError = message,
                        ),
                    )
                }
            }
        }
    }

    private fun showModelConfig() {
        viewModelScope.launch {
            val modelId = _uiState.value.selectedModelId
            val model = modelId?.let { modelRepository.getModelById(it) }
            _uiState.update {
                it.copy(
                    showModelConfig = true,
                    selectedModelInfo = model,
                )
            }
        }
    }

    private fun updateInferenceParams(update: (InferenceParams) -> InferenceParams) {
        var paramsChanged = false
        _uiState.update { state ->
            val newParams = update(state.inferenceParams)
            paramsChanged = newParams != state.inferenceParams
            state.copy(
                inferenceParams = newParams,
                modelRuntime = if (paramsChanged && state.modelRuntime.status == ModelLoadStatus.LOADED) {
                    state.modelRuntime.copy(
                        status = ModelLoadStatus.IDLE,
                        note = "Inference settings changed. Runtime will reload on next generation.",
                    )
                } else {
                    state.modelRuntime
                },
            )
        }
        if (paramsChanged) {
            loadedInferenceSignature = null
        }
    }

    private fun applyTaskTemplate(systemPrompt: String, starterPrompt: String) {
        _uiState.update { state ->
            state.copy(
                inputText = starterPrompt,
                inferenceParams = state.inferenceParams.copy(systemPrompt = systemPrompt),
            )
        }
    }

    private fun runBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = viewModelScope.launch {
            if (_uiState.value.isGenerating) {
                _uiState.update { it.copy(error = "Stop active generation before running benchmark.") }
                return@launch
            }

            val selectedModelId = _uiState.value.selectedModelId
            val selectedModel = selectedModelId
                ?.let { modelRepository.getModelById(it) }
                ?: _uiState.value.availableModels.firstOrNull { it.id == selectedModelId }

            if (selectedModel == null) {
                _uiState.update { it.copy(error = "Select a model before running benchmark.") }
                return@launch
            }

            if (selectedModel.format != ModelFormat.GGUF) {
                _uiState.update {
                    it.copy(error = "Benchmark is available only for GGUF text models.")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    benchmarkRunning = true,
                    benchmarkResult = null,
                    error = null,
                )
            }

            try {
                val benchmarkResult = withContext(Dispatchers.Default) {
                    runtimeCoordinator.withEngineLock {
                        if (
                            !ggufEngine.isModelLoaded() ||
                                loadedModelId != selectedModel.id ||
                                loadedInferenceSignature != _uiState.value.inferenceParams
                        ) {
                            val modelPath = selectedModel.localPath ?: "/data/models/${selectedModel.fileName}"
                            ggufEngine.load(
                                modelPath = modelPath,
                                params = _uiState.value.inferenceParams,
                                gpuAccelerationEnabled = gpuAccelerationEnabled,
                                gpuOffloadLayers = resolveDesiredGpuLayers(selectedModel),
                            )
                            loadedModelId = selectedModel.id
                            loadedInferenceSignature = _uiState.value.inferenceParams
                        }

                        ggufEngine.benchModel(pp = 512, tg = 128, pl = 1, nr = 1).trim()
                    }
                }

                val activeThreads = ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
                val activeGpuLayers = ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)

                _uiState.update { state ->
                    state.copy(
                        benchmarkRunning = false,
                        benchmarkResult = benchmarkResult,
                        modelRuntime = state.modelRuntime.copy(
                            modelId = selectedModel.id,
                            modelDisplayName = selectedModel.displayName.ifBlank { selectedModel.repoId },
                            status = ModelLoadStatus.LOADED,
                            backend = resolveBackendLabel(),
                            threadCount = activeThreads,
                            gpuOffloadLayers = activeGpuLayers,
                            offloadSummary = buildOffloadSummary(gpuEnabled = gpuAccelerationEnabled, gpuLayers = activeGpuLayers),
                            note = "Benchmark completed (pp=512, tg=128).",
                            lastError = null,
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        benchmarkRunning = false,
                        error = "Benchmark failed: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    private fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        if (_uiState.value.chatBackend == ChatBackend.OLLAMA) {
            ollamaChat(text, _uiState.value.pendingImageAttachment)
            return
        }

        generationJob?.cancel()

        generationJob = viewModelScope.launch {
            val convo = _uiState.value.currentConversation ?: createNewConversation().also {
                observeConversation(it.id)
            }

            val selectedModel = resolveSelectedModel(convo)
                ?: run {
                    _uiState.update {
                        it.copy(error = "Download a model in Marketplace before chatting.")
                    }
                    return@launch
                }

            _uiState.update { it.copy(inputText = "", currentConversation = convo, error = null) }

            val pendingImage = _uiState.value.pendingImageAttachment

            if (selectedModel.format == ModelFormat.DIFFUSERS) {
                val userMsg = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convo.id,
                    role = MessageRole.USER,
                    content = text,
                    attachedImagePath = pendingImage,
                )
                conversationRepository.addMessage(userMsg)

                generateImageResponse(conversation = convo, userText = text, imageModel = selectedModel)

                _uiState.update { it.copy(pendingImageAttachment = null) }
                if (convo.title == "New Conversation") {
                    val title = text.take(40) + if (text.length > 40) "…" else ""
                    conversationRepository.updateConversation(convo.copy(title = title))
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingText = "",
                    generationStatus = "Loading model...",
                    pendingImageAttachment = null,
                )
            }

            try {
                val ready = ensureEngineReady(convo)
                    ?: throw IllegalStateException("Model not ready. Please wait for the model to download.")

                val hasVision = ggufEngine.supportsVision()

                if (pendingImage != null && File(pendingImage).exists()) {
                    if (hasVision) {
                        // Use multimodal image input
                        conversationRepository.addMessage(
                            Message(
                                id = UUID.randomUUID().toString(),
                                conversationId = convo.id,
                                role = MessageRole.USER,
                                content = text,
                                attachedImagePath = pendingImage,
                            ),
                        )
                    } else {
                        // Fallback: describe image in text
                        conversationRepository.addMessage(
                            Message(
                                id = UUID.randomUUID().toString(),
                                conversationId = convo.id,
                                role = MessageRole.SYSTEM,
                                content = "The user has attached an image at: $pendingImage. Describe what you see in the image and respond to the user's message in context of the image.",
                            ),
                        )
                    }
                }

                val activeModel = ready.model
                val targetModelId = activeModel.id

                // Persist the user message after replay/load completes to avoid duplicate native context entries.
                val userMsg = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convo.id,
                    role = MessageRole.USER,
                    content = text,
                    attachedImagePath = pendingImage,
                )
                conversationRepository.addMessage(userMsg)

                _uiState.update { it.copy(generationStatus = "Processing prompt...") }

                val builder = StringBuilder()
                var promptTokens = 0
                val generationStartSnapshot = PerformanceUsageSampler.captureSnapshot()
                val startedAtNs = System.nanoTime()
                var firstTokenAtNs: Long? = null
                var firstTokenSnapshot: PerformanceUsageSampler.Snapshot? = null
                var streamError: Throwable? = null

                try {
                    runtimeCoordinator.withEngineLock {
                        if (!ggufEngine.isModelLoaded() || loadedModelId != targetModelId) {
                            ensureEngineReadyInternal(convo)
                        }
                        promptTokens = ggufEngine.getContextLengthUsed()

                        // Check if we should use multimodal image input
                        val imageFile = pendingImage?.let { File(it) }
                        if (hasVision && imageFile != null && imageFile.exists()) {
                            // Use multimodal image input
                            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                            if (bitmap != null) {
                                val rgbBytes = bitmapToRgbBytes(bitmap)
                                ggufEngine.startCompletionWithImage(text, rgbBytes, bitmap.width, bitmap.height)
                                bitmap.recycle()

                                // Token generation loop
                                while (_uiState.value.isGenerating) {
                                    val piece = ggufEngine.completionLoop()
                                    if (piece == "[EOG]") break
                                    if (piece.isNotEmpty()) {
                                        if (firstTokenAtNs == null) {
                                            firstTokenAtNs = System.nanoTime()
                                            firstTokenSnapshot = PerformanceUsageSampler.captureSnapshot()
                                            _uiState.update { state -> state.copy(generationStatus = "Generating response...") }
                                        }
                                        builder.append(piece)
                                        _uiState.update { state -> state.copy(streamingText = builder.toString()) }
                                    }
                                }
                                ggufEngine.stopCompletion()
                            } else {
                                // Fallback to text-only if bitmap decode fails
                                ggufEngine.getResponseAsFlow(text, maxTokens = _uiState.value.inferenceParams.maxTokens).collect { piece ->
                                    if (!_uiState.value.isGenerating) throw CancellationException("Generation stopped")
                                    if (firstTokenAtNs == null) {
                                        firstTokenAtNs = System.nanoTime()
                                        firstTokenSnapshot = PerformanceUsageSampler.captureSnapshot()
                                        _uiState.update { state -> state.copy(generationStatus = "Generating response...") }
                                    }
                                    builder.append(piece)
                                    _uiState.update { state -> state.copy(streamingText = builder.toString()) }
                                }
                            }
                        } else {
                            // Text-only inference
                            ggufEngine.getResponseAsFlow(
                                text,
                                maxTokens = _uiState.value.inferenceParams.maxTokens,
                            ).collect { piece ->
                                if (!_uiState.value.isGenerating) throw CancellationException("Generation stopped")
                                if (firstTokenAtNs == null) {
                                    firstTokenAtNs = System.nanoTime()
                                    firstTokenSnapshot = PerformanceUsageSampler.captureSnapshot()
                                    _uiState.update { state -> state.copy(generationStatus = "Generating response...") }
                                }
                                builder.append(piece)
                                _uiState.update { state -> state.copy(streamingText = builder.toString()) }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    streamError = failure
                    _uiState.update { state ->
                        state.copy(generationStatus = "Finalizing partial response...")
                    }
                }

                var finalText = sanitizeGeneratedText(builder.toString())
                val responseWasTruncated = streamError != null && finalText.isNotEmpty()
                if (responseWasTruncated) {
                    finalText = appendPartialMarker(finalText)
                }
                val completedAtNs = System.nanoTime()
                val generationEndSnapshot = PerformanceUsageSampler.captureSnapshot()

                if (streamError != null && finalText.isEmpty()) {
                    throw streamError as Throwable
                }

                if (finalText.isNotEmpty()) {
                    val assistantMsg = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convo.id,
                        role = MessageRole.ASSISTANT,
                        content = finalText,
                    )
                    conversationRepository.addMessage(assistantMsg)
                }

                val totalElapsedMs = ((completedAtNs - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
                val firstTokenLatencyMs = (((firstTokenAtNs ?: completedAtNs) - startedAtNs) / 1_000_000L)
                    .coerceAtLeast(0L)
                val decodeElapsedMs = firstTokenAtNs
                    ?.let { ((completedAtNs - it) / 1_000_000L).coerceAtLeast(1L) }
                    ?: totalElapsedMs
                var generatedTokens = 0
                var nativeSpeed = 0f
                var nativePromptSpeed = 0f
                var activeThreads = _uiState.value.inferenceParams.numThreads
                var activeGpuLayers = resolveDesiredGpuLayers()
                runtimeCoordinator.withEngineLock {
                    generatedTokens = (ggufEngine.getContextLengthUsed() - promptTokens).coerceAtLeast(0)
                    nativeSpeed = ggufEngine.getResponseGenerationSpeed()
                    nativePromptSpeed = ggufEngine.getPromptProcessingSpeed()
                    activeThreads = ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
                    activeGpuLayers = ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)
                }
                val promptTokensPerSecond = if (firstTokenLatencyMs > 0) {
                    promptTokens.toDouble() / (firstTokenLatencyMs / 1000.0)
                } else {
                    0.0
                }
                val decodeTokensPerSecond = if (decodeElapsedMs > 0) {
                    generatedTokens.toDouble() / (decodeElapsedMs / 1000.0)
                } else {
                    0.0
                }
                val promptUsage = PerformanceUsageSampler.computeUsage(
                    start = generationStartSnapshot,
                    end = firstTokenSnapshot ?: generationEndSnapshot,
                )
                val decodeUsage = firstTokenSnapshot?.let {
                    PerformanceUsageSampler.computeUsage(start = it, end = generationEndSnapshot)
                }

                _uiState.update { state ->
                    state.copy(
                        lastGenerationStats = GenerationStats(
                            modelDisplayName = activeModel.displayName.ifBlank { activeModel.repoId },
                            backend = resolveBackendLabel(),
                            modelLoadDurationMs = ready.loadDurationMs,
                            replayedMessages = ready.replayedMessages,
                            promptTokens = promptTokens,
                            generatedTokens = generatedTokens,
                            generatedChars = finalText.length,
                            firstTokenLatencyMs = firstTokenLatencyMs,
                            durationMs = totalElapsedMs,
                            promptTokensPerSecond = promptTokensPerSecond,
                            nativePromptTokensPerSecond = nativePromptSpeed,
                            decodeTokensPerSecond = decodeTokensPerSecond,
                            nativeTokensPerSecond = nativeSpeed,
                            promptCpuUsagePercent = promptUsage.cpuPercent,
                            decodeCpuUsagePercent = decodeUsage?.cpuPercent ?: 0.0,
                            promptGpuUsagePercent = promptUsage.gpuPercent,
                            decodeGpuUsagePercent = decodeUsage?.gpuPercent,
                            threadCount = activeThreads,
                            gpuLayers = activeGpuLayers,
                            contextSize = state.inferenceParams.contextSize ?: 2048,
                        ),
                    )
                }

                if (responseWasTruncated) {
                    val reason = streamError?.message?.takeIf { it.isNotBlank() } ?: "runtime limit reached"
                    _uiState.update {
                        it.copy(error = "Response was partially returned ($reason).")
                    }
                }

                if (convo.modelId != targetModelId) {
                    conversationRepository.updateConversation(
                        convo.copy(modelId = targetModelId, updatedAt = System.currentTimeMillis()),
                    )
                }
            } catch (_: CancellationException) {
                // Explicit stop; keep partial stream out of persisted history.
            } catch (e: Exception) {
                val details = e.message ?: "unknown error"
                appendSystemErrorMessage(convo.id, "Generation error: $details")
                _uiState.update {
                    it.copy(
                        error = "Generation failed: $details",
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        generationStatus = null,
                    )
                }

                // TTS playback for AI response
                if (_uiState.value.ttsEnabled && kittenTtsEngine.isAvailable()) {
                    val lastAiMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (lastAiMessage != null) {
                        val filteredText = TtsTextFilter.filter(lastAiMessage.content)
                        if (filteredText.isNotBlank()) {
                            _uiState.update { it.copy(isSpeaking = true) }
                            kittenTtsEngine.speak(filteredText)
                            _uiState.update { it.copy(isSpeaking = false) }
                        }
                    }
                }
            }

            // Update conversation title from first message
            if (convo.title == "New Conversation") {
                val title = text.take(40) + if (text.length > 40) "…" else ""
                conversationRepository.updateConversation(convo.copy(title = title))
            }
        }
    }

    private suspend fun generateImageResponse(
        conversation: Conversation,
        userText: String,
        imageModel: LlmModel,
    ) {
        _uiState.update {
            it.copy(
                isGenerating = true,
                streamingText = "",
                generationStatus = "Preparing image model...",
                lastGenerationStats = null,
            )
        }

        try {
            if (conversation.modelId != imageModel.id) {
                val updated = conversation.copy(
                    modelId = imageModel.id,
                    updatedAt = System.currentTimeMillis(),
                )
                conversationRepository.updateConversation(updated)
                _uiState.update { state -> state.copy(currentConversation = updated) }
            }

            ensureImageEngineReady(imageModel)

            var generatedBitmap: Bitmap? = null
            imageGenEngine.generate(
                prompt = userText,
                negativePrompt = "",
                steps = 24,
                cfgScale = 7.5f,
                width = 512,
                height = 512,
            ).collect { progress ->
                when (progress) {
                    is ImageGenProgress.Step -> {
                        _uiState.update {
                            it.copy(generationStatus = "Generating image... ${progress.current}/${progress.total}")
                        }
                    }

                    is ImageGenProgress.Complete -> {
                        generatedBitmap = progress.bitmap
                    }
                }
            }

            val finalBitmap = generatedBitmap
                ?: throw IllegalStateException("Image generation finished without output")

            val imagePath = persistGeneratedImage(finalBitmap, conversation.id)
            val imageMessage = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = MessageRole.IMAGE_GEN,
                content = "Generated image for: $userText",
                attachedImagePath = imagePath,
            )
            conversationRepository.addMessage(imageMessage)
        } catch (_: CancellationException) {
            // Explicit stop from UI.
        } catch (e: Exception) {
            val details = e.message ?: "unknown error"
            appendSystemErrorMessage(conversation.id, "Image generation error: $details")
            _uiState.update {
                it.copy(
                    error = "Image generation failed: $details",
                )
            }
        } finally {
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                    generationStatus = null,
                )
            }
        }
    }

    private suspend fun resolveSelectedModel(conversation: Conversation): LlmModel? {
        val modelId = _uiState.value.selectedModelId
            ?: conversation.modelId.takeIf { it.isNotBlank() }
            ?: _uiState.value.availableModels.firstOrNull()?.id
            ?: return null

        return modelRepository.getModelById(modelId)
            ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
    }

    private suspend fun ensureImageEngineReady(model: LlmModel) {
        if (loadedImageModelId == model.id && imageGenEngine.isAvailable()) return

        val path = model.localPath ?: "/data/models/${model.fileName}"
        imageGenEngine.loadModel(path).getOrElse { throw it }
        loadedImageModelId = model.id
    }

    private suspend fun persistGeneratedImage(bitmap: Bitmap, conversationId: String): String =
        withContext(Dispatchers.IO) {
            val outputDir = File(appContext.filesDir, "generated/chat/$conversationId").apply { mkdirs() }
            val outputFile = File(outputDir, "img_${System.currentTimeMillis()}.png")
            outputFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outputFile.absolutePath
        }

    private suspend fun appendSystemErrorMessage(conversationId: String, details: String) {
        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = MessageRole.SYSTEM,
                content = details,
            ),
        )
    }

    private fun stopGeneration() {
        viewModelScope.launch {
            cancelGenerationIfRunning()
        }
    }

    /**
     * Look for mmproj GGUF file. Checks ModelDownloadManager first, then searches models directories.
     */
    private fun findMmprojFile(): String? {
        // Check ModelDownloadManager's known location first
        val externalDir = appContext.getExternalFilesDir(null)?.let { java.io.File(it, "models") }
        val internalDir = java.io.File(appContext.filesDir, "models")

        val searchDirs = listOfNotNull(externalDir, internalDir)
        for (dir in searchDirs) {
            if (!dir.isDirectory) continue
            val mmprojFile = dir.listFiles()?.firstOrNull { file ->
                file.name.contains("mmproj", ignoreCase = true) &&
                    file.name.endsWith(".gguf", ignoreCase = true) &&
                    file.length() > 0
            }
            if (mmprojFile != null) {
                Log.i("ChatVM", "Found mmproj: ${mmprojFile.absolutePath}")
                return mmprojFile.absolutePath
            }
        }
        return null
    }

    /**
     * Convert Android Bitmap to raw RGB byte array for llama.cpp multimodal input.
     */
    private fun bitmapToRgbBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val bytes = ByteArray(width * height * 3)
        var offset = 0
        for (pixel in pixels) {
            bytes[offset++] = (pixel shr 16 and 0xFF).toByte()  // R
            bytes[offset++] = (pixel shr 8 and 0xFF).toByte()   // G
            bytes[offset++] = (pixel and 0xFF).toByte()          // B
        }
        return bytes
    }

    private fun startListening() {
        voskSpeechManager.startListening { transcript ->
            _uiState.update { it.copy(inputText = transcript) }
        }
    }

    private fun performWebSearch(query: String) {
        viewModelScope.launch {
            try {
                val searchTool = toolRegistry.getTool("web_search")
                if (searchTool != null) {
                    val result = searchTool.executor(mapOf("query" to query))
                    // Add search result as system message
                    val convo = _uiState.value.currentConversation ?: return@launch
                    conversationRepository.addMessage(
                        Message(
                            id = UUID.randomUUID().toString(),
                            conversationId = convo.id,
                            role = MessageRole.SYSTEM,
                            content = "Web search results:\n$result",
                        ),
                    )
                    // Trigger generation with search context
                    sendMessage()
                } else {
                    _uiState.update { it.copy(error = "Web search tool not available") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Search failed: ${e.message}") }
            }
        }
    }

    private fun downloadVoskModel() {
        viewModelScope.launch {
            voskModelDownloadManager.ensureModelReady().collect { status ->
                when (status) {
                    is VoskModelDownloadManager.DownloadStatus.Ready -> {
                        voskSpeechManager.initModel { ready ->
                            _uiState.update { it.copy(voskModelReady = ready) }
                        }
                    }
                    is VoskModelDownloadManager.DownloadStatus.Error -> {
                        _uiState.update { it.copy(error = status.message) }
                    }
                    else -> { /* Download in progress */ }
                }
            }
        }
    }

    private suspend fun cancelGenerationIfRunning() {
        val job = generationJob ?: return
        job.cancelAndJoin()
        if (generationJob === job) {
            generationJob = null
        }
        _uiState.update { it.copy(isGenerating = false, generationStatus = null) }
    }

    private suspend fun createNewConversation(): Conversation {
        val convo = Conversation(
            id = UUID.randomUUID().toString(),
            mode = ConversationMode.CHAT,
            modelId = _uiState.value.selectedModelId.orEmpty(),
        )
        conversationRepository.createConversation(convo)
        return convo
    }

    private fun selectConversation(id: String) {
        viewModelScope.launch {
            val convo = conversationRepository.getConversationById(id) ?: return@launch
            _uiState.update {
                it.copy(
                    currentConversation = convo,
                    showConversationList = false,
                    selectedModelId = convo.modelId.ifBlank { it.selectedModelId },
                )
            }
            observeConversation(id)
        }
    }

    private fun newConversation() {
        viewModelScope.launch {
            val convo = createNewConversation()
            _uiState.update {
                it.copy(
                    currentConversation = convo,
                    showConversationList = false,
                    messages = emptyList(),
                )
            }
            observeConversation(convo.id)
        }
    }

    private fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
            if (_uiState.value.currentConversation?.id == id) {
                messagesJob?.cancel()
                _uiState.update {
                    it.copy(
                        currentConversation = null,
                        showConversationList = true,
                        messages = emptyList(),
                    )
                }
            }
        }
    }

    private fun observeConversation(conversationId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            conversationRepository.getMessagesForConversation(conversationId).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    private suspend fun ensureEngineReady(conversation: Conversation): EngineReadyResult? = runtimeCoordinator.withEngineLock {
        ensureEngineReadyInternal(conversation)
    }

    private suspend fun ensureEngineReadyInternal(conversation: Conversation): EngineReadyResult? {
        val inferenceParams = _uiState.value.inferenceParams
        val modelId = _uiState.value.selectedModelId
            ?: conversation.modelId.takeIf { it.isNotBlank() }
            ?: _uiState.value.availableModels.firstOrNull()?.id
            ?: return null

        val model = modelRepository.getModelById(modelId)
            ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            ?: return null

        val resolution = TextRuntimeModelResolver.resolveForTextGeneration(
            selectedModel = model,
            availableModels = _uiState.value.availableModels,
            contextLabel = "chat text inference",
        )
        val textRuntimeModel = resolution.runtimeModel

        var loadDurationMs = 0L
        var replayedMessages = 0
        var resolutionNote: String? = resolution.resolutionNote
        val requiresReload =
            loadedModelId != textRuntimeModel.id ||
                !ggufEngine.isModelLoaded() ||
                loadedInferenceSignature != inferenceParams

        if (requiresReload) {
            val path = textRuntimeModel.localPath ?: "/data/models/${textRuntimeModel.fileName}"

            _uiState.update { state ->
                state.copy(
                    modelRuntime = state.modelRuntime.copy(
                        modelId = textRuntimeModel.id,
                        modelDisplayName = textRuntimeModel.displayName.ifBlank { textRuntimeModel.repoId },
                        status = ModelLoadStatus.LOADING,
                        backend = "GGUF/${GgufEngine.getLoadedNativeLibraryName()}",
                        modelPath = path,
                        loadDurationMs = null,
                        note = "Loading model into memory...",
                        lastError = null,
                    ),
                )
            }

            val loadStartedAtNs = System.nanoTime()
            ggufEngine.load(
                modelPath = path,
                params = inferenceParams,
                gpuAccelerationEnabled = gpuAccelerationEnabled,
                gpuOffloadLayers = resolveDesiredGpuLayers(textRuntimeModel),
            )
            loadDurationMs = ((System.nanoTime() - loadStartedAtNs) / 1_000_000L).coerceAtLeast(0L)

            // Try to load mmproj for multimodal vision support
            val mmprojPath = findMmprojFile()
            if (mmprojPath != null) {
                try {
                    val visionLoaded = ggufEngine.loadMmproj(mmprojPath)
                    if (visionLoaded) {
                        Log.i("ChatVM", "mmproj loaded: vision support enabled")
                    }
                } catch (e: Exception) {
                    Log.w("ChatVM", "mmproj load failed (vision disabled): ${e.message}")
                }
            }

            replayedMessages = replayConversationContext(
                conversationId = conversation.id,
                configuredSystemPrompt = _uiState.value.inferenceParams.systemPrompt,
            )

            loadedModelId = textRuntimeModel.id
            loadedInferenceSignature = inferenceParams

            if (textRuntimeModel.id != model.id) {
                _uiState.update { it.copy(selectedModelId = textRuntimeModel.id) }
            }
        }

        _uiState.update { state ->
            val activeThreads = if (ggufEngine.isModelLoaded()) {
                ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
            } else {
                state.inferenceParams.numThreads
            }
            val activeGpuLayers = if (ggufEngine.isModelLoaded()) {
                ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)
            } else {
                resolveDesiredGpuLayers()
            }
            state.copy(
                modelRuntime = state.modelRuntime.copy(
                    modelId = textRuntimeModel.id,
                    modelDisplayName = textRuntimeModel.displayName.ifBlank { textRuntimeModel.repoId },
                    status = ModelLoadStatus.LOADED,
                    backend = resolveBackendLabel(),
                    modelPath = textRuntimeModel.localPath ?: "/data/models/${textRuntimeModel.fileName}",
                    loadDurationMs = if (loadDurationMs > 0L) loadDurationMs else state.modelRuntime.loadDurationMs,
                    threadCount = activeThreads,
                    contextSize = state.inferenceParams.contextSize ?: 2048,
                    gpuAccelerationEnabled = gpuAccelerationEnabled,
                    gpuOffloadLayers = activeGpuLayers,
                    cpuExecutionEnabled = true,
                    offloadSummary = buildOffloadSummary(gpuEnabled = gpuAccelerationEnabled, gpuLayers = activeGpuLayers),
                    note = resolutionNote ?: "Model is loaded in memory and ready for text generation.",
                    lastError = null,
                ),
            )
        }

        return EngineReadyResult(
            model = textRuntimeModel,
            loadDurationMs = loadDurationMs,
            replayedMessages = replayedMessages,
        )
    }

    private fun runtimeBackendLabelForModel(format: ModelFormat): String {
        return when (format) {
            ModelFormat.GGUF -> "GGUF/${GgufEngine.getLoadedNativeLibraryName()}"
            ModelFormat.SAFETENSORS -> "SafeTensors/validator"
            ModelFormat.DIFFUSERS -> "Diffusers/ImageGenEngine"
        }
    }

    private fun buildOffloadSummary(gpuEnabled: Boolean, gpuLayers: Int): String {
        return if (gpuEnabled && gpuLayers > 0) {
            "GPU acceleration ON • offloaded layers: $gpuLayers • hybrid CPU+GPU decode"
        } else if (gpuEnabled) {
            "GPU acceleration ON • no layers offloaded (CPU fallback)"
        } else {
            "GPU acceleration OFF • CPU decode active"
        }
    }

    private fun resolveDesiredGpuLayers(model: LlmModel? = _uiState.value.selectedModelInfo): Int {
        if (!gpuAccelerationEnabled) return 0

        val modelSizeBytes = model?.sizeBytes ?: 0L
        if (modelSizeBytes <= 0L) return 28

        val gib = 1024L * 1024L * 1024L
        return when {
            modelSizeBytes <= 2L * gib -> 48
            modelSizeBytes <= 4L * gib -> 36
            modelSizeBytes <= 8L * gib -> 24
            else -> 16
        }
    }

    private fun sanitizeGeneratedText(raw: String): String {
        return raw
            // Gemma 4 turn delimiters
            .replace("<|turn>", "")
            .replace("<turn|>", "")
            // Legacy ChatML delimiters (for non-Gemma models)
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("</s>", "")
            .replace("<s>", "")
            .replace("[EOG]", "")
            .replace("[STOP]", "")
            .replace("[ERROR]", "")
            .replace("\u0000", "")
            .trim()
    }

    private fun appendPartialMarker(text: String): String {
        val normalized = text.trimEnd()
        return if (normalized.endsWith("...") || normalized.endsWith("…")) {
            normalized
        } else {
            "$normalized ..."
        }
    }

    private suspend fun replayConversationContext(
        conversationId: String,
        configuredSystemPrompt: String,
    ): Int {
        val history = conversationRepository
            .getMessagesForConversation(conversationId)
            .first()
            .filter { message ->
                !message.isStreaming &&
                    (message.role == MessageRole.SYSTEM ||
                        message.role == MessageRole.USER ||
                        message.role == MessageRole.ASSISTANT)
            }
            .sortedBy { it.timestamp }

        val trimmedSystemPrompt = configuredSystemPrompt.trim()
        if (
            trimmedSystemPrompt.isNotEmpty() &&
            history.none { it.role == MessageRole.SYSTEM && it.content == trimmedSystemPrompt }
        ) {
            ggufEngine.addSystemPrompt(trimmedSystemPrompt)
        }

        var replayed = 0
        history.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> ggufEngine.addSystemPrompt(message.content)
                MessageRole.USER -> ggufEngine.addUserMessage(message.content)
                MessageRole.ASSISTANT -> ggufEngine.addAssistantMessage(message.content)
                else -> return@forEach
            }
            replayed += 1
        }
        return replayed
    }

    private fun resolveBackendLabel(): String {
        return if (ggufEngine.isModelLoaded()) {
            "GGUF/${GgufEngine.getLoadedNativeLibraryName()}"
        } else {
            "Not loaded"
        }
    }

    private fun toggleBackend() {
        viewModelScope.launch {
            val current = _uiState.value.chatBackend
            val next = if (current == ChatBackend.LOCAL) ChatBackend.OLLAMA else ChatBackend.LOCAL
            _uiState.update { it.copy(chatBackend = next) }
            if (next == ChatBackend.OLLAMA) {
                checkOllamaConnection()
            }
        }
    }

    private fun checkOllamaConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(ollamaConnected = false) }
            ollamaApiService.validateHost().let { result ->
                if (result.isAvailable) {
                    ollamaApiService.listModels().onSuccess { models ->
                        _uiState.update {
                            it.copy(
                                ollamaConnected = true,
                                ollamaModels = models,
                                ollamaSelectedModel = models.firstOrNull()?.name ?: "",
                            )
                        }
                    }.onFailure {
                        _uiState.update { it.copy(ollamaConnected = true, ollamaModels = emptyList()) }
                    }
                } else {
                    _uiState.update {
                        it.copy(error = "Ollama not available: ${result.error ?: "unknown"}")
                    }
                }
            }
        }
    }

    private fun getBundledModel(): LlmModel? {
        val info = BundledModelManager.initialize(appContext) ?: return null
        return LlmModel(
            id = info.id,
            repoId = "google/gemma-4-E2B-it",
            fileName = BundledModelManager.MODEL_FILENAME,
            displayName = info.displayName,
            format = ModelFormat.GGUF,
            sizeBytes = java.io.File(info.localPath).length(),
            downloadState = DownloadState.DOWNLOADED,
            localPath = info.localPath,
            quantization = "Q4_K_M",
        )
    }

    private fun selectOllamaModel(modelName: String) {
        _uiState.update { it.copy(ollamaSelectedModel = modelName) }
    }

    private fun buildPrompt(userText: String): String {
        val history = _uiState.value.messages
            .takeLast(10)
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT || it.role == MessageRole.SYSTEM }
            .joinToString("\n") { "${it.role.name.lowercase()}: ${it.content}" }

        return buildString {
            appendLine("You are Master LLM, an on-device helpful assistant.")
            if (history.isNotBlank()) {
                appendLine("Conversation history:")
                appendLine(history)
            }
            appendLine("user: $userText")
            append("assistant:")
        }
    }

    private fun ollamaChat(userText: String, imagePath: String? = null) {
        val convo = _uiState.value.currentConversation
        if (convo == null) {
            _uiState.update { it.copy(error = "Start or select a conversation first") }
            return
        }
        val modelName = _uiState.value.ollamaSelectedModel.ifBlank {
            _uiState.update { it.copy(error = "Select an Ollama model first") }
            return
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    inputText = "",
                    isGenerating = true,
                    streamingText = "",
                    generationStatus = "Connecting to Ollama...",
                    error = null,
                    pendingImageAttachment = null,
                )
            }

            try {
                // Encode attached image as base64 if present
                val imageBase64 = imagePath?.takeIf { File(it).exists() }?.let { path ->
                    try {
                        val bytes = File(path).readBytes()
                        java.util.Base64.getEncoder().encodeToString(bytes)
                    } catch (e: Exception) { null }
                }

                val userMsg = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convo.id,
                    role = MessageRole.USER,
                    content = userText,
                    attachedImagePath = imagePath,
                )
                conversationRepository.addMessage(userMsg)

                val systemPrompt = _uiState.value.inferenceParams.systemPrompt
                val messages = mutableListOf<com.masterllm.core.ollama.model.ChatMessage>()
                if (systemPrompt.isNotBlank()) {
                    messages.add(com.masterllm.core.ollama.model.ChatMessage(role = "system", content = systemPrompt))
                }
                messages.add(com.masterllm.core.ollama.model.ChatMessage(
                    role = "user",
                    content = userText,
                    images = imageBase64?.let { listOf(it) },
                ))

                val request = com.masterllm.core.ollama.model.ChatRequest(
                    model = modelName,
                    messages = messages,
                    stream = true,
                )

                val builder = StringBuilder()
                _uiState.update { it.copy(generationStatus = "Generating...") }

                ollamaApiService.chatStream(request).collect { piece ->
                    builder.append(piece)
                    _uiState.update { it.copy(streamingText = builder.toString()) }
                }

                val finalText = builder.toString()
                if (finalText.isNotEmpty()) {
                    val assistantMsg = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convo.id,
                        role = MessageRole.ASSISTANT,
                        content = finalText,
                    )
                    conversationRepository.addMessage(assistantMsg)
                }

            } catch (_: CancellationException) {
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Ollama error: ${e.message}") }
            } finally {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        generationStatus = null,
                    )
                }
            }

            if (convo.title == "New Conversation") {
                val title = userText.take(40) + if (userText.length > 40) "…" else ""
                conversationRepository.updateConversation(convo.copy(title = title))
            }
        }
    }
}
