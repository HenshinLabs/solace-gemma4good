package com.masterllm.feature.roleplay

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.Conversation
import com.masterllm.core.domain.model.ConversationMode
import com.masterllm.core.domain.model.DownloadState
import com.masterllm.core.domain.model.InferenceParams
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.domain.model.Message
import com.masterllm.core.domain.model.MessageRole
import com.masterllm.core.domain.model.ModelFormat
import com.masterllm.core.domain.model.RoleplaySession
import com.masterllm.core.domain.model.TextRuntimeModelResolver
import com.masterllm.core.domain.model.VisualStyle
import com.masterllm.core.domain.repository.ConversationRepository
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.RoleplayRepository
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.runtime.gguf.GgufEngine
import com.masterllm.runtime.gguf.GgufRuntimeCoordinator
import com.masterllm.runtime.gguf.PerformanceUsageSampler
import com.masterllm.runtime.imagegen.ImageGenEngine
import com.masterllm.runtime.imagegen.ImageGenProgress
import com.masterllm.runtime.safetensors.SafetensorsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RoleplayGenerationStats(
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

data class RoleplayModelRuntimeInfo(
    val modelId: String? = null,
    val modelDisplayName: String = "No model selected",
    val backend: String = "Not loaded",
    val statusLabel: String = "Idle",
    val threadCount: Int = 4,
    val gpuLayers: Int = 0,
    val contextSize: Int = 2048,
    val gpuAccelerationEnabled: Boolean = false,
    val loadDurationMs: Long? = null,
    val note: String = "Select a model to start roleplay inference.",
    val lastError: String? = null,
)

data class RoleplayUiState(
    val sessions: List<RoleplaySession> = emptyList(),
    val currentSession: RoleplaySession? = null,
    val messages: List<Message> = emptyList(),
    val availableModels: List<LlmModel> = emptyList(),
    val selectedModelId: String? = null,
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val generationStatus: String? = null,
    val showSessionList: Boolean = true,
    val showSetupDialog: Boolean = false,
    val showModelConfig: Boolean = false,
    val setupTitle: String = "",
    val setupGenre: String = "Fantasy",
    val setupPremise: String = "",
    val setupAiName: String = "",
    val setupAiDescription: String = "",
    val setupUserName: String = "",
    val setupUserDescription: String = "",
    val setupVisualStyle: VisualStyle = VisualStyle.FANTASY_ART,
    val selectedModelInfo: LlmModel? = null,
    val modelRuntime: RoleplayModelRuntimeInfo = RoleplayModelRuntimeInfo(),
    val error: String? = null,
    val inferenceParams: InferenceParams = InferenceParams(),
    val lastGenerationStats: RoleplayGenerationStats? = null,
)

sealed interface RoleplayAction {
    data class InputChanged(val text: String) : RoleplayAction
    data object SendMessage : RoleplayAction
    data object StopGeneration : RoleplayAction
    data class SelectSession(val id: String) : RoleplayAction
    data object NewSession : RoleplayAction
    data class DeleteSession(val id: String) : RoleplayAction
    data class SelectModel(val modelId: String) : RoleplayAction
    data object BackToList : RoleplayAction
    data object ShowSetup : RoleplayAction
    data object DismissSetup : RoleplayAction
    data object ShowModelConfig : RoleplayAction
    data object HideModelConfig : RoleplayAction
    data object RefreshModelRuntime : RoleplayAction
    data object GenerateSceneImage : RoleplayAction
    data class SetupTitleChanged(val v: String) : RoleplayAction
    data class SetupGenreChanged(val v: String) : RoleplayAction
    data class SetupPremiseChanged(val v: String) : RoleplayAction
    data class SetupAiNameChanged(val v: String) : RoleplayAction
    data class SetupAiDescChanged(val v: String) : RoleplayAction
    data class SetupUserNameChanged(val v: String) : RoleplayAction
    data class SetupUserDescChanged(val v: String) : RoleplayAction
    data class SetupStyleChanged(val v: VisualStyle) : RoleplayAction
    data object CreateSession : RoleplayAction
    data object DismissError : RoleplayAction
    data class UpdateTemperature(val value: Float) : RoleplayAction
    data class UpdateTopP(val value: Float) : RoleplayAction
    data class UpdateTopK(val value: Int) : RoleplayAction
    data class UpdateRepeatPenalty(val value: Float) : RoleplayAction
    data class UpdateMaxTokens(val value: Int) : RoleplayAction
    data class UpdateSystemPrompt(val value: String) : RoleplayAction
    data object ResetInferenceParams : RoleplayAction
}

@HiltViewModel
class RoleplayViewModel @Inject constructor(
    private val roleplayRepository: RoleplayRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val ggufEngine: GgufEngine,
    private val runtimeCoordinator: GgufRuntimeCoordinator,
    private val imageGenEngine: ImageGenEngine,
    private val safetensorsEngine: SafetensorsEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private data class EngineReadyResult(
        val model: LlmModel,
        val loadDurationMs: Long,
        val replayedMessages: Int,
    )

    private val _uiState = MutableStateFlow(RoleplayUiState())
    val uiState: StateFlow<RoleplayUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var generationJob: Job? = null
    private var loadedModelId: String? = null
    private var loadedInferenceSignature: InferenceParams? = null
    private var loadedImageModelId: String? = null
    private var gpuAccelerationEnabled: Boolean = false
    private val engineMutex = runtimeCoordinator.engineMutex

    init {
        viewModelScope.launch {
            roleplayRepository.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }

        viewModelScope.launch {
            modelRepository.getDownloadedModels().collect { models ->
                val filtered = models.filter { model ->
                    model.downloadState == DownloadState.DOWNLOADED &&
                        (
                            model.format == ModelFormat.GGUF ||
                                model.format == ModelFormat.SAFETENSORS ||
                                model.format == ModelFormat.DIFFUSERS
                            )
                }

                _uiState.update { state ->
                    val selected = state.selectedModelId?.takeIf { selectedId ->
                        filtered.any { it.id == selectedId }
                    } ?: filtered.firstOrNull { it.format == ModelFormat.GGUF }?.id
                        ?: filtered.firstOrNull { it.format == ModelFormat.SAFETENSORS }?.id
                        ?: filtered.firstOrNull { it.format == ModelFormat.DIFFUSERS }?.id
                    val selectedModel = filtered.firstOrNull { it.id == selected }

                    state.copy(
                        availableModels = filtered,
                        selectedModelId = selected,
                        selectedModelInfo = selectedModel,
                        modelRuntime = state.modelRuntime.copy(
                            modelId = selectedModel?.id,
                            modelDisplayName = selectedModel?.displayName?.ifBlank { selectedModel.repoId }
                                ?: "No model selected",
                            backend = runtimeBackendLabelForModel(selectedModel?.format),
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                settingsRepository.getDefaultThreadCount(),
                settingsRepository.getGpuAccelerationEnabled(),
            ) { threadCount, gpuEnabled -> threadCount to gpuEnabled }
                .collect { (threadCount, gpuEnabled) ->
                    gpuAccelerationEnabled = gpuEnabled
                    _uiState.update { state ->
                        state.copy(
                            inferenceParams = state.inferenceParams.copy(
                                numThreads = threadCount.coerceAtLeast(1),
                            ),
                        )
                    }
                    refreshModelRuntime()
                }
        }
    }

    fun onAction(action: RoleplayAction) {
        when (action) {
            is RoleplayAction.InputChanged -> _uiState.update { it.copy(inputText = action.text) }
            RoleplayAction.SendMessage -> sendMessage()
            RoleplayAction.StopGeneration -> stopGeneration()
            is RoleplayAction.SelectSession -> selectSession(action.id)
            RoleplayAction.NewSession -> _uiState.update { it.copy(showSetupDialog = true) }
            is RoleplayAction.DeleteSession -> deleteSession(action.id)
            is RoleplayAction.SelectModel -> selectModel(action.modelId)
            RoleplayAction.BackToList -> _uiState.update {
                it.copy(showSessionList = true, currentSession = null, messages = emptyList())
            }
            RoleplayAction.ShowSetup -> _uiState.update { it.copy(showSetupDialog = true) }
            RoleplayAction.DismissSetup -> _uiState.update { it.copy(showSetupDialog = false) }
            RoleplayAction.ShowModelConfig -> showModelConfig()
            RoleplayAction.HideModelConfig -> _uiState.update { it.copy(showModelConfig = false) }
            RoleplayAction.RefreshModelRuntime -> refreshModelRuntime()
            RoleplayAction.GenerateSceneImage -> generateSceneImage()
            is RoleplayAction.SetupTitleChanged -> _uiState.update { it.copy(setupTitle = action.v) }
            is RoleplayAction.SetupGenreChanged -> _uiState.update { it.copy(setupGenre = action.v) }
            is RoleplayAction.SetupPremiseChanged -> _uiState.update { it.copy(setupPremise = action.v) }
            is RoleplayAction.SetupAiNameChanged -> _uiState.update { it.copy(setupAiName = action.v) }
            is RoleplayAction.SetupAiDescChanged -> _uiState.update { it.copy(setupAiDescription = action.v) }
            is RoleplayAction.SetupUserNameChanged -> _uiState.update { it.copy(setupUserName = action.v) }
            is RoleplayAction.SetupUserDescChanged -> _uiState.update { it.copy(setupUserDescription = action.v) }
            is RoleplayAction.SetupStyleChanged -> _uiState.update { it.copy(setupVisualStyle = action.v) }
            RoleplayAction.CreateSession -> createSession()
            RoleplayAction.DismissError -> _uiState.update { it.copy(error = null) }
            is RoleplayAction.UpdateTemperature -> updateInferenceParams { it.copy(temperature = action.value) }
            is RoleplayAction.UpdateTopP -> updateInferenceParams { it.copy(topP = action.value) }
            is RoleplayAction.UpdateTopK -> updateInferenceParams { it.copy(topK = action.value) }
            is RoleplayAction.UpdateRepeatPenalty -> updateInferenceParams { it.copy(repeatPenalty = action.value) }
            is RoleplayAction.UpdateMaxTokens -> updateInferenceParams { it.copy(maxTokens = action.value) }
            is RoleplayAction.UpdateSystemPrompt -> updateInferenceParams { it.copy(systemPrompt = action.value) }
            RoleplayAction.ResetInferenceParams -> _uiState.update { it.copy(inferenceParams = InferenceParams()) }
        }
    }

    private fun updateInferenceParams(update: (InferenceParams) -> InferenceParams) {
        var paramsChanged = false
        _uiState.update { state ->
            val newParams = update(state.inferenceParams)
            paramsChanged = newParams != state.inferenceParams
            state.copy(
                inferenceParams = newParams,
                modelRuntime = if (paramsChanged && state.modelRuntime.statusLabel == "Loaded") {
                    state.modelRuntime.copy(
                        statusLabel = "Idle",
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

    private fun showModelConfig() {
        viewModelScope.launch {
            val model = _uiState.value.selectedModelId
                ?.let { modelRepository.getModelById(it) }
                ?: _uiState.value.availableModels.firstOrNull { it.id == _uiState.value.selectedModelId }
            _uiState.update {
                it.copy(
                    showModelConfig = true,
                    selectedModelInfo = model,
                )
            }
            refreshModelRuntime()
        }
    }

    private fun refreshModelRuntime() {
        val state = _uiState.value
        val selectedModel = state.selectedModelInfo
            ?: state.availableModels.firstOrNull { it.id == state.selectedModelId }

        val ggufLoaded = selectedModel?.format == ModelFormat.GGUF &&
            selectedModel.id == loadedModelId &&
            ggufEngine.isModelLoaded()
        val imageLoaded = selectedModel?.format == ModelFormat.DIFFUSERS &&
            selectedModel.id == loadedImageModelId &&
            imageGenEngine.isAvailable()

        val activeThreads = if (ggufLoaded) {
            ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
        } else {
            state.inferenceParams.numThreads.coerceAtLeast(1)
        }
        val activeGpuLayers = if (ggufLoaded) {
            ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)
        } else {
            resolveDesiredGpuLayers(selectedModel)
        }

        val statusLabel = when {
            ggufLoaded || imageLoaded -> "Loaded"
            selectedModel != null -> "Idle"
            else -> "Idle"
        }

        val note = when {
            selectedModel == null -> "Download a model from Marketplace to start roleplay."
            selectedModel.format == ModelFormat.DIFFUSERS -> "Diffusers model selected for scene image generation."
            ggufLoaded -> "Text runtime loaded and ready."
            else -> "Model selected. Send a prompt to load runtime."
        }

        _uiState.update {
            it.copy(
                modelRuntime = it.modelRuntime.copy(
                    modelId = selectedModel?.id,
                    modelDisplayName = selectedModel?.displayName?.ifBlank { selectedModel.repoId }
                        ?: "No model selected",
                    backend = runtimeBackendLabelForModel(selectedModel?.format),
                    statusLabel = statusLabel,
                    threadCount = activeThreads,
                    gpuLayers = activeGpuLayers,
                    contextSize = it.inferenceParams.contextSize ?: 2048,
                    gpuAccelerationEnabled = gpuAccelerationEnabled,
                    note = note,
                    lastError = null,
                ),
            )
        }
    }

    private fun generateSceneImage() {
        val session = _uiState.value.currentSession
        if (session == null) {
            _uiState.update { it.copy(error = "Open a roleplay session before generating images.") }
            return
        }

        val prompt = _uiState.value.inputText.trim().ifBlank {
            _uiState.value.messages
                .lastOrNull { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                ?.content
                ?.take(180)
                .orEmpty()
                .ifBlank {
                    session.premise.take(180)
                }
        }

        if (prompt.isBlank()) {
            _uiState.update {
                it.copy(error = "Type a scene prompt (or add conversation context) before generating an image.")
            }
            return
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    generationStatus = "Preparing image model...",
                    error = null,
                )
            }

            try {
                val imageModel = resolveImageModel()
                    ?: throw IllegalStateException("Download a Diffusers model in Marketplace to generate roleplay images.")

                ensureImageEngineReady(imageModel)

                val styledPrompt = if (session.visualStyle == VisualStyle.CUSTOM) {
                    prompt
                } else {
                    "$prompt, ${session.visualStyle.displayName}"
                }

                var generatedBitmap: Bitmap? = null
                imageGenEngine.generate(
                    prompt = styledPrompt,
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
                val imagePath = persistGeneratedImage(finalBitmap, session.id)

                conversationRepository.addMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.conversationId,
                        role = MessageRole.IMAGE_GEN,
                        content = "Generated roleplay scene image for: $styledPrompt",
                        attachedImagePath = imagePath,
                    ),
                )

                _uiState.update { it.copy(inputText = "") }
            } catch (_: CancellationException) {
                // Explicit stop requested by user.
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Roleplay image generation failed: ${e.message ?: "unknown error"}")
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generationStatus = null,
                    )
                }
                refreshModelRuntime()
            }
        }
    }

    private fun selectModel(modelId: String) {
        viewModelScope.launch {
            if (_uiState.value.isGenerating) {
                cancelGenerationIfRunning()
            }

            val selectedModel = modelRepository.getModelById(modelId)
                ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }

            _uiState.update {
                it.copy(
                    selectedModelId = modelId,
                    selectedModelInfo = selectedModel,
                    modelRuntime = it.modelRuntime.copy(
                        modelId = selectedModel?.id,
                        modelDisplayName = selectedModel?.displayName?.ifBlank { selectedModel.repoId }
                            ?: "No model selected",
                        backend = runtimeBackendLabelForModel(selectedModel?.format),
                        note = "Model updated. Send a prompt or refresh runtime.",
                        lastError = null,
                    ),
                )
            }

            _uiState.value.currentSession?.let { session ->
                val conversation = conversationRepository.getConversationById(session.conversationId)
                if (conversation != null && conversation.modelId != modelId) {
                    conversationRepository.updateConversation(
                        conversation.copy(
                            modelId = modelId,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            if (loadedModelId != null && loadedModelId != modelId) {
                engineMutex.withLock {
                    ggufEngine.close()
                    loadedModelId = null
                    loadedInferenceSignature = null
                }
            }
            if (loadedImageModelId != null && loadedImageModelId != modelId) {
                imageGenEngine.unloadModel()
                loadedImageModelId = null
            }

            refreshModelRuntime()
        }
    }

    private fun createSession() {
        val state = _uiState.value
        if (state.setupTitle.isBlank() || state.setupAiName.isBlank()) {
            _uiState.update { it.copy(error = "Title and AI character name required") }
            return
        }

        viewModelScope.launch {
            val selectedModelId = _uiState.value.selectedModelId
                ?: resolveDefaultTextModelId()
                .orEmpty()
            val conversationId = UUID.randomUUID().toString()

            val conversation = Conversation(
                id = conversationId,
                title = state.setupTitle,
                mode = ConversationMode.ROLEPLAY,
                modelId = selectedModelId,
            )
            conversationRepository.createConversation(conversation)

            val session = RoleplaySession(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                title = state.setupTitle,
                genre = state.setupGenre,
                premise = state.setupPremise,
                aiCharacterName = state.setupAiName,
                aiCharacterDescription = state.setupAiDescription,
                userCharacterName = state.setupUserName,
                userCharacterDescription = state.setupUserDescription,
                visualStyle = state.setupVisualStyle,
            )
            roleplayRepository.createSession(session)

            val systemMsg = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = MessageRole.SYSTEM,
                content = buildSystemPrompt(session),
            )
            conversationRepository.addMessage(systemMsg)

            _uiState.update {
                it.copy(
                    showSetupDialog = false,
                    currentSession = session,
                    showSessionList = false,
                    setupTitle = "",
                    setupPremise = "",
                    setupAiName = "",
                    setupAiDescription = "",
                    setupUserName = "",
                    setupUserDescription = "",
                )
            }

            observeMessages(conversationId)
        }
    }

    private fun selectSession(id: String) {
        viewModelScope.launch {
            val session = roleplayRepository.getSessionById(id) ?: return@launch
            val conversation = conversationRepository.getConversationById(session.conversationId)
            val selectedModelId = conversation?.modelId?.takeIf { modelId -> modelId.isNotBlank() }
                ?: _uiState.value.selectedModelId
            val selectedModel = selectedModelId?.let { modelRepository.getModelById(it) }
            _uiState.update {
                it.copy(
                    currentSession = session,
                    showSessionList = false,
                    selectedModelId = selectedModelId,
                    selectedModelInfo = selectedModel ?: it.selectedModelInfo,
                )
            }
            observeMessages(session.conversationId)
            refreshModelRuntime()
        }
    }

    private fun deleteSession(id: String) {
        viewModelScope.launch {
            val session = roleplayRepository.getSessionById(id) ?: return@launch
            conversationRepository.deleteConversation(session.conversationId)
            roleplayRepository.deleteSession(id)
            if (_uiState.value.currentSession?.id == id) {
                messagesJob?.cancel()
                _uiState.update {
                    it.copy(
                        currentSession = null,
                        messages = emptyList(),
                        showSessionList = true,
                    )
                }
            }
        }
    }

    private fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        val session = _uiState.value.currentSession ?: return

        generationJob?.cancel()

        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(inputText = "") }

            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingText = "",
                    generationStatus = "Loading model...",
                )
            }

            try {
                val ready = ensureEngineReady(session)
                    ?: throw IllegalStateException("Download a GGUF model in Marketplace before roleplaying.")

                val activeModel = ready.model

                val userMsg = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = session.conversationId,
                    role = MessageRole.USER,
                    content = text,
                )
                conversationRepository.addMessage(userMsg)

                _uiState.update { it.copy(generationStatus = "Processing prompt...") }

                val builder = StringBuilder()
                val promptTokens = engineMutex.withLock {
                    ggufEngine.getContextLengthUsed()
                }
                val generationStartSnapshot = PerformanceUsageSampler.captureSnapshot()
                val startedAtNs = System.nanoTime()
                var firstTokenAtNs: Long? = null
                var firstTokenSnapshot: PerformanceUsageSampler.Snapshot? = null
                var streamError: Throwable? = null

                try {
                    engineMutex.withLock {
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
                            _uiState.update { it.copy(streamingText = builder.toString()) }
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
                    val aiMsg = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.conversationId,
                        role = MessageRole.ASSISTANT,
                        content = finalText,
                    )
                    conversationRepository.addMessage(aiMsg)
                }

                val totalElapsedMs = ((completedAtNs - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
                val firstTokenLatencyMs = (((firstTokenAtNs ?: completedAtNs) - startedAtNs) / 1_000_000L)
                    .coerceAtLeast(0L)
                val decodeElapsedMs = firstTokenAtNs
                    ?.let { ((completedAtNs - it) / 1_000_000L).coerceAtLeast(1L) }
                    ?: totalElapsedMs
                var generatedTokens = 0
                var nativePromptSpeed = 0f
                var nativeDecodeSpeed = 0f
                var activeThreads = _uiState.value.inferenceParams.numThreads
                var activeGpuLayers = resolveDesiredGpuLayers(activeModel)
                engineMutex.withLock {
                    generatedTokens = (ggufEngine.getContextLengthUsed() - promptTokens).coerceAtLeast(0)
                    nativePromptSpeed = ggufEngine.getPromptProcessingSpeed()
                    nativeDecodeSpeed = ggufEngine.getResponseGenerationSpeed()
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
                        lastGenerationStats = RoleplayGenerationStats(
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
                            nativeTokensPerSecond = nativeDecodeSpeed,
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
                        it.copy(error = "Roleplay response was partially returned ($reason).")
                    }
                }
            } catch (_: CancellationException) {
                // Explicit stop from UI.
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Roleplay generation failed: ${e.message ?: "unknown error"}")
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        generationStatus = null,
                    )
                }
                refreshModelRuntime()
            }
        }
    }

    private fun stopGeneration() {
        viewModelScope.launch {
            cancelGenerationIfRunning()
        }
    }

    private suspend fun cancelGenerationIfRunning() {
        val job = generationJob ?: return
        _uiState.update { it.copy(isGenerating = false, generationStatus = null) }
        job.cancelAndJoin()
        if (generationJob === job) {
            generationJob = null
        }
    }

    private fun observeMessages(conversationId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            conversationRepository.getMessagesForConversation(conversationId).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    private suspend fun ensureEngineReady(session: RoleplaySession): EngineReadyResult? = engineMutex.withLock {
        val inferenceParams = _uiState.value.inferenceParams
        val conversation = conversationRepository.getConversationById(session.conversationId)
        val modelId = _uiState.value.selectedModelId
            ?: conversation?.modelId?.takeIf { it.isNotBlank() }
            ?: _uiState.value.availableModels.firstOrNull()?.id
            ?: return null

        val model = modelRepository.getModelById(modelId)
            ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            ?: return null

        val resolution = TextRuntimeModelResolver.resolveForTextGeneration(
            selectedModel = model,
            availableModels = _uiState.value.availableModels,
            contextLabel = "roleplay text inference",
        )
        val textRuntimeModel = resolution.runtimeModel

        if (conversation != null && conversation.modelId != textRuntimeModel.id) {
            conversationRepository.updateConversation(
                conversation.copy(modelId = textRuntimeModel.id, updatedAt = System.currentTimeMillis()),
            )
        }

        if (_uiState.value.selectedModelId != textRuntimeModel.id) {
            _uiState.update { it.copy(selectedModelId = textRuntimeModel.id) }
        }

        var loadDurationMs = 0L
        var replayedMessages = 0
        val requiresReload =
            loadedModelId != textRuntimeModel.id ||
                !ggufEngine.isModelLoaded() ||
                loadedInferenceSignature != inferenceParams

        if (requiresReload) {
            val path = textRuntimeModel.localPath ?: modelFallbackPath(textRuntimeModel.fileName)

            val loadStartedAtNs = System.nanoTime()
            ggufEngine.load(
                modelPath = path,
                params = inferenceParams,
                gpuAccelerationEnabled = gpuAccelerationEnabled,
                gpuOffloadLayers = resolveDesiredGpuLayers(textRuntimeModel),
            )
            loadDurationMs = ((System.nanoTime() - loadStartedAtNs) / 1_000_000L).coerceAtLeast(0L)

            replayedMessages = replayConversationContext(
                conversationId = session.conversationId,
                configuredSystemPrompt = inferenceParams.systemPrompt,
            )

            loadedModelId = textRuntimeModel.id
            loadedInferenceSignature = inferenceParams
        }

        val activeThreads = if (ggufEngine.isModelLoaded()) {
            ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
        } else {
            _uiState.value.inferenceParams.numThreads.coerceAtLeast(1)
        }
        val activeGpuLayers = if (ggufEngine.isModelLoaded()) {
            ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)
        } else {
            resolveDesiredGpuLayers(textRuntimeModel)
        }

        _uiState.update {
            it.copy(
                selectedModelId = textRuntimeModel.id,
                selectedModelInfo = textRuntimeModel,
                modelRuntime = it.modelRuntime.copy(
                    modelId = textRuntimeModel.id,
                    modelDisplayName = textRuntimeModel.displayName.ifBlank { textRuntimeModel.repoId },
                    backend = resolveBackendLabel(),
                    statusLabel = "Loaded",
                    threadCount = activeThreads,
                    gpuLayers = activeGpuLayers,
                    contextSize = it.inferenceParams.contextSize ?: 2048,
                    gpuAccelerationEnabled = gpuAccelerationEnabled,
                    loadDurationMs = if (loadDurationMs > 0L) loadDurationMs else it.modelRuntime.loadDurationMs,
                    note = "Text runtime loaded and ready.",
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

    private fun modelFallbackPath(fileName: String): String {
        val defaultDir = appContext.getExternalFilesDir(null)?.absolutePath ?: appContext.filesDir.absolutePath
        return "$defaultDir/models/$fileName"
    }

    private fun runtimeBackendLabelForModel(format: ModelFormat?): String {
        return when (format) {
            ModelFormat.GGUF -> "GGUF/${GgufEngine.getLoadedNativeLibraryName()}"
            ModelFormat.SAFETENSORS -> "SafeTensors/validator"
            ModelFormat.DIFFUSERS -> "Diffusers/ImageGenEngine"
            null -> "Not loaded"
        }
    }

    private fun resolveImageModel(): LlmModel? {
        val selected = _uiState.value.selectedModelInfo
        if (selected?.format == ModelFormat.DIFFUSERS) {
            return selected
        }
        return _uiState.value.availableModels.firstOrNull { it.format == ModelFormat.DIFFUSERS }
    }

    private suspend fun ensureImageEngineReady(model: LlmModel) {
        if (loadedImageModelId == model.id && imageGenEngine.isAvailable()) return

        val path = model.localPath ?: modelFallbackPath(model.fileName)
        imageGenEngine.loadModel(path).getOrElse { throw it }
        loadedImageModelId = model.id
    }

    private suspend fun persistGeneratedImage(bitmap: Bitmap, sessionId: String): String =
        withContext(Dispatchers.IO) {
            val outputDir = File(appContext.filesDir, "generated/roleplay/$sessionId").apply { mkdirs() }
            val outputFile = File(outputDir, "img_${System.currentTimeMillis()}.png")
            outputFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outputFile.absolutePath
        }

    private fun resolveDefaultTextModelId(): String? {
        return _uiState.value.availableModels.firstOrNull { it.format == ModelFormat.GGUF }?.id
            ?: _uiState.value.availableModels.firstOrNull { it.format == ModelFormat.SAFETENSORS }?.id
    }

    private fun resolveDesiredGpuLayers(model: LlmModel? = _uiState.value.availableModels.firstOrNull {
        it.id == _uiState.value.selectedModelId
    }): Int {
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

    private fun buildSystemPrompt(session: RoleplaySession): String {
        return buildString {
            appendLine("You are roleplaying as ${session.aiCharacterName}.")
            if (session.aiCharacterDescription.isNotEmpty()) {
                appendLine("Character: ${session.aiCharacterDescription}")
            }
            if (session.genre.isNotEmpty()) {
                appendLine("Genre: ${session.genre}")
            }
            if (session.premise.isNotEmpty()) {
                appendLine("Premise: ${session.premise}")
            }
            if (session.userCharacterName.isNotEmpty()) {
                appendLine("The user plays as ${session.userCharacterName}.")
            }
            if (session.userCharacterDescription.isNotEmpty()) {
                appendLine("User character: ${session.userCharacterDescription}")
            }
            appendLine("Write in third person, use *actions* for physical actions.")
            appendLine("Keep responses immersive and in-character.")
        }
    }
}
