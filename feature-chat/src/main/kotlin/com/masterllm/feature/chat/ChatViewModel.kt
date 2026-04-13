package com.masterllm.feature.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ConversationRepository
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.runtime.gguf.GgufEngine
import com.masterllm.runtime.imagegen.ImageGenEngine
import com.masterllm.runtime.imagegen.ImageGenProgress
import com.masterllm.runtime.safetensors.SafetensorsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val decodeTokensPerSecond: Double,
    val nativeTokensPerSecond: Float,
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
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val ggufEngine: GgufEngine,
    private val imageGenEngine: ImageGenEngine,
    private val safetensorsEngine: SafetensorsEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private data class EngineReadyResult(
        val model: LlmModel,
        val loadDurationMs: Long,
        val replayedMessages: Int,
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var generationJob: Job? = null
    private var modelProbeJob: Job? = null
    private var loadedModelId: String? = null
    private var loadedImageModelId: String? = null
    private var gpuAccelerationEnabled: Boolean = false
    private val engineMutex = Mutex()

    init {
        // Watch chat conversations
        viewModelScope.launch {
            conversationRepository.getConversationsByMode(ConversationMode.CHAT).collect { convos ->
                _uiState.update { it.copy(conversations = convos) }
            }
        }
        // Watch downloaded models
        viewModelScope.launch {
            modelRepository.getDownloadedModels().collect { models ->
                val filtered = models.filter {
                    it.downloadState == DownloadState.DOWNLOADED &&
                        (
                            it.format == ModelFormat.GGUF ||
                                it.format == ModelFormat.SAFETENSORS ||
                                it.format == ModelFormat.DIFFUSERS
                            )
                }
                var selectedModelId: String? = null
                _uiState.update { state ->
                    val selected = state.selectedModelId?.takeIf { selectedId ->
                        filtered.any { it.id == selectedId }
                    } ?: filtered.firstOrNull { it.format == ModelFormat.GGUF }?.id
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
        viewModelScope.launch {
            combine(
                settingsRepository.getDefaultThreadCount(),
                settingsRepository.getGpuAccelerationEnabled(),
            ) { threadCount, gpuEnabled -> threadCount to gpuEnabled }
                .collect { (threadCount, gpuEnabled) ->
                    gpuAccelerationEnabled = gpuEnabled
                    val normalizedThreads = threadCount.coerceAtLeast(1)
                    // Update inference params with thread count
                    _uiState.update { state ->
                        state.copy(
                            inferenceParams = state.inferenceParams.copy(
                                numThreads = normalizedThreads,
                            ),
                            modelRuntime = state.modelRuntime.copy(
                                threadCount = normalizedThreads,
                                contextSize = state.inferenceParams.contextSize ?: 2048,
                                gpuAccelerationEnabled = gpuEnabled,
                                gpuOffloadLayers = 0,
                                offloadSummary = buildOffloadSummary(gpuEnabled = gpuEnabled, gpuLayers = 0),
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
        }
    }

    private fun selectModel(modelId: String) {
        viewModelScope.launch {
            if (_uiState.value.isGenerating) {
                stopGeneration()
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
                ggufEngine.close()
                loadedModelId = null
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
                val loadDurationMs = engineMutex.withLock {
                    when (model.format) {
                        ModelFormat.GGUF -> {
                            val startedAt = System.nanoTime()
                            ggufEngine.load(modelPath = modelPath, params = _uiState.value.inferenceParams)
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
                        val hasGgufFallback = _uiState.value.availableModels.any {
                            it.format == ModelFormat.GGUF && it.downloadState == DownloadState.DOWNLOADED
                        }
                        if (hasGgufFallback) {
                            "SafeTensors validated. Chat inference will use a GGUF runtime fallback."
                        } else {
                            "SafeTensors validated. Download any GGUF model for on-device text generation."
                        }
                    }

                    ModelFormat.DIFFUSERS -> "Diffusers model is loaded in memory and ready for image generation."
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
                            threadCount = state.inferenceParams.numThreads,
                            contextSize = state.inferenceParams.contextSize ?: 2048,
                            gpuAccelerationEnabled = gpuAccelerationEnabled,
                            gpuOffloadLayers = 0,
                            cpuExecutionEnabled = true,
                            offloadSummary = buildOffloadSummary(gpuEnabled = gpuAccelerationEnabled, gpuLayers = 0),
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
        _uiState.update { state ->
            state.copy(inferenceParams = update(state.inferenceParams))
        }
    }

    private fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

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

            val userMsg = Message(
                id = UUID.randomUUID().toString(),
                conversationId = convo.id,
                role = MessageRole.USER,
                content = text,
            )
            conversationRepository.addMessage(userMsg)

            if (selectedModel.format == ModelFormat.DIFFUSERS) {
                generateImageResponse(conversation = convo, userText = text, imageModel = selectedModel)

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
                )
            }

            try {
                val ready = ensureEngineReady(convo)
                    ?: throw IllegalStateException("Download a GGUF model in Marketplace before chatting.")

                val activeModel = ready.model
                val targetModelId = activeModel.id

                _uiState.update { it.copy(generationStatus = "Processing prompt...") }

                ggufEngine.addUserMessage(text)

                val builder = StringBuilder()
                val promptTokens = ggufEngine.getContextLengthUsed()
                val startedAtNs = System.nanoTime()
                var firstTokenAtNs: Long? = null

                ggufEngine.getResponseAsFlow(text).collect { piece ->
                    if (!_uiState.value.isGenerating) throw CancellationException("Generation stopped")
                    if (firstTokenAtNs == null) {
                        firstTokenAtNs = System.nanoTime()
                        _uiState.update { state -> state.copy(generationStatus = "Generating response...") }
                    }
                    builder.append(piece)
                    _uiState.update { state -> state.copy(streamingText = builder.toString()) }
                }

                val finalText = builder.toString().trim()
                val completedAtNs = System.nanoTime()

                if (finalText.isNotEmpty()) {
                    ggufEngine.addAssistantMessage(finalText)

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
                val generatedTokens = (ggufEngine.getContextLengthUsed() - promptTokens).coerceAtLeast(0)
                val decodeTokensPerSecond = if (decodeElapsedMs > 0) {
                    generatedTokens.toDouble() / (decodeElapsedMs / 1000.0)
                } else {
                    0.0
                }
                val nativeSpeed = ggufEngine.getResponseGenerationSpeed()

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
                            decodeTokensPerSecond = decodeTokensPerSecond,
                            nativeTokensPerSecond = nativeSpeed,
                            threadCount = state.inferenceParams.numThreads,
                            gpuLayers = 0,
                            contextSize = state.inferenceParams.contextSize ?: 2048,
                        ),
                    )
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
        _uiState.update { it.copy(isGenerating = false, generationStatus = null) }
        generationJob?.cancel()
        generationJob = null
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

    private suspend fun ensureEngineReady(conversation: Conversation): EngineReadyResult? = engineMutex.withLock {
        val modelId = _uiState.value.selectedModelId
            ?: conversation.modelId.takeIf { it.isNotBlank() }
            ?: _uiState.value.availableModels.firstOrNull()?.id
            ?: return null

        val model = modelRepository.getModelById(modelId)
            ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            ?: return null

        val textRuntimeModel = when (model.format) {
            ModelFormat.GGUF -> model
            ModelFormat.SAFETENSORS -> {
                val sameRepoFallback = _uiState.value.availableModels.firstOrNull {
                    it.downloadState == DownloadState.DOWNLOADED &&
                        it.format == ModelFormat.GGUF &&
                        it.repoId == model.repoId
                }
                val fallback = sameRepoFallback ?: _uiState.value.availableModels.firstOrNull {
                    it.downloadState == DownloadState.DOWNLOADED &&
                        it.format == ModelFormat.GGUF
                }
                if (fallback != null) {
                    fallback
                } else {
                    val safePath = model.localPath ?: "/data/models/${model.fileName}"
                    safetensorsEngine.loadModel(safePath).getOrElse { throw it }
                    throw IllegalStateException(
                        "SafeTensors weights were validated, but on-device text generation requires GGUF. " +
                            "Download any GGUF model (or convert this model to GGUF) and try again.",
                    )
                }
            }
            ModelFormat.DIFFUSERS -> {
                throw IllegalStateException(
                    "Selected model is DIFFUSERS. Text generation requires GGUF.",
                )
            }
        }

        var loadDurationMs = 0L
        var replayedMessages = 0
        var resolutionNote: String? = null
        if (textRuntimeModel.id != model.id) {
            resolutionNote = "Selected ${model.displayName.ifBlank { model.repoId }} uses GGUF runtime fallback: ${textRuntimeModel.displayName.ifBlank { textRuntimeModel.repoId }}"
        }
        if (loadedModelId != textRuntimeModel.id || !ggufEngine.isModelLoaded()) {
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
            ggufEngine.load(modelPath = path, params = _uiState.value.inferenceParams)
            loadDurationMs = ((System.nanoTime() - loadStartedAtNs) / 1_000_000L).coerceAtLeast(0L)

            replayedMessages = replayConversationContext(
                conversationId = conversation.id,
                configuredSystemPrompt = _uiState.value.inferenceParams.systemPrompt,
            )

            loadedModelId = textRuntimeModel.id

            if (textRuntimeModel.id != model.id) {
                _uiState.update { it.copy(selectedModelId = textRuntimeModel.id) }
            }
        }

        _uiState.update { state ->
            state.copy(
                modelRuntime = state.modelRuntime.copy(
                    modelId = textRuntimeModel.id,
                    modelDisplayName = textRuntimeModel.displayName.ifBlank { textRuntimeModel.repoId },
                    status = ModelLoadStatus.LOADED,
                    backend = resolveBackendLabel(),
                    modelPath = textRuntimeModel.localPath ?: "/data/models/${textRuntimeModel.fileName}",
                    loadDurationMs = if (loadDurationMs > 0L) loadDurationMs else state.modelRuntime.loadDurationMs,
                    threadCount = state.inferenceParams.numThreads,
                    contextSize = state.inferenceParams.contextSize ?: 2048,
                    gpuAccelerationEnabled = gpuAccelerationEnabled,
                    gpuOffloadLayers = 0,
                    cpuExecutionEnabled = true,
                    offloadSummary = buildOffloadSummary(gpuEnabled = gpuAccelerationEnabled, gpuLayers = 0),
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
        return if (gpuEnabled) {
            "GPU acceleration toggle ON • GPU offload layers: $gpuLayers • CPU decode active"
        } else {
            "GPU acceleration toggle OFF • GPU offload layers: $gpuLayers • CPU decode active"
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
}
