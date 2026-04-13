package com.masterllm.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ConversationRepository
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.runtime.gguf.GgufEngine
import com.masterllm.runtime.safetensors.SafetensorsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val safetensorsEngine: SafetensorsEngine,
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
    private var loadedModelId: String? = null
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
                        (it.format == ModelFormat.GGUF || it.format == ModelFormat.SAFETENSORS)
                }
                _uiState.update { state ->
                    val selected = state.selectedModelId?.takeIf { selectedId ->
                        filtered.any { it.id == selectedId }
                    } ?: filtered.firstOrNull()?.id
                    state.copy(
                        availableModels = filtered,
                        selectedModelId = selected,
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
                    // Update inference params with thread count
                    _uiState.update { state ->
                        state.copy(
                            inferenceParams = state.inferenceParams.copy(
                                numThreads = threadCount.coerceAtLeast(1)
                            )
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

            _uiState.update { it.copy(inputText = "", currentConversation = convo, error = null) }

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

                val userMsg = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convo.id,
                    role = MessageRole.USER,
                    content = text,
                )
                conversationRepository.addMessage(userMsg)

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
                _uiState.update {
                    it.copy(
                        error = "Generation failed: ${e.message ?: "unknown error"}",
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
                val fallback = _uiState.value.availableModels.firstOrNull {
                    it.downloadState == DownloadState.DOWNLOADED &&
                        it.format == ModelFormat.GGUF &&
                        it.repoId == model.repoId
                }
                if (fallback != null) {
                    fallback
                } else {
                    val safePath = model.localPath ?: "/data/models/${model.fileName}"
                    safetensorsEngine.loadModel(safePath).getOrElse { throw it }
                    throw IllegalStateException(
                        "SafeTensors weights were validated, but on-device text generation requires GGUF. " +
                            "Download or convert a GGUF variant for this model.",
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
        if (loadedModelId != textRuntimeModel.id || !ggufEngine.isModelLoaded()) {
            val path = textRuntimeModel.localPath ?: "/data/models/${textRuntimeModel.fileName}"

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
