package com.masterllm.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ConversationRepository
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.runtime.gguf.GgufEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

data class GenerationStats(
    val modelDisplayName: String,
    val backend: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val generatedChars: Int,
    val durationMs: Long,
    val tokensPerSecond: Double,
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
) : ViewModel() {

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
                val filtered = models.filter { it.format == ModelFormat.GGUF && it.downloadState == DownloadState.DOWNLOADED }
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
            val model = modelRepository.getModelById(modelId)
                ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            _uiState.update {
                it.copy(
                    selectedModelId = modelId,
                    selectedModelInfo = model,
                )
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

            // Add user message
            val userMsg = Message(
                id = UUID.randomUUID().toString(),
                conversationId = convo.id,
                role = MessageRole.USER,
                content = text,
            )
            conversationRepository.addMessage(userMsg)

            _uiState.update { it.copy(isGenerating = true, streamingText = "") }

try {
            val activeModel = ensureEngineReady(convo)
                ?: throw IllegalStateException("Download a GGUF model in Marketplace before chatting.")

            val targetModelId = activeModel.id

            // Add system prompt if first message
            if (_uiState.value.messages.isEmpty()) {
                val systemPrompt = _uiState.value.inferenceParams.systemPrompt
                if (systemPrompt.isNotBlank()) {
                    ggufEngine.addSystemPrompt(systemPrompt)
                }
            }

            // Add user message to engine
            ggufEngine.addUserMessage(text)

            val builder = StringBuilder()
            val promptTokens = ggufEngine.getContextLengthUsed()
            val startedAtNs = System.nanoTime()

            // Generate response using new API
            ggufEngine.getResponseAsFlow(text).collect { piece ->
                if (!_uiState.value.isGenerating) throw CancellationException("Generation stopped")
                builder.append(piece)
                _uiState.update { it.copy(streamingText = builder.toString()) }
            }

            val finalText = builder.toString().trim()
            val completedAtNs = System.nanoTime()
            
            // Add assistant message to engine and database
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

val elapsedMs = ((completedAtNs - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
            val generatedTokens = ggufEngine.getContextLengthUsed() - promptTokens
            val tokensPerSecond = if (elapsedMs > 0) {
                generatedTokens.toDouble() / (elapsedMs / 1000.0)
            } else 0.0
            
            val genSpeed = ggufEngine.getResponseGenerationSpeed()

            _uiState.update {
                it.copy(
                    lastGenerationStats = GenerationStats(
                        modelDisplayName = activeModel.displayName.ifBlank { activeModel.repoId },
                        backend = resolveBackendLabel(),
                        promptTokens = promptTokens,
                        generatedTokens = generatedTokens,
                        generatedChars = finalText.length,
                        durationMs = elapsedMs,
                        tokensPerSecond = tokensPerSecond,
                        threadCount = _uiState.value.inferenceParams.numThreads,
                        gpuLayers = 0,
                        contextSize = _uiState.value.inferenceParams.contextSize ?: 2048,
                    )
                )
            }

                if (convo.modelId != targetModelId) {
                    conversationRepository.updateConversation(
                        convo.copy(modelId = targetModelId, updatedAt = System.currentTimeMillis())
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
                _uiState.update { it.copy(isGenerating = false, streamingText = "") }
            }

            // Update conversation title from first message
            if (convo.title == "New Conversation") {
                val title = text.take(40) + if (text.length > 40) "…" else ""
                conversationRepository.updateConversation(convo.copy(title = title))
            }
        }
    }

    private fun stopGeneration() {
        _uiState.update { it.copy(isGenerating = false) }
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

    private suspend fun ensureEngineReady(conversation: Conversation): LlmModel? = engineMutex.withLock {
        val modelId = _uiState.value.selectedModelId
            ?: conversation.modelId.takeIf { it.isNotBlank() }
            ?: _uiState.value.availableModels.firstOrNull()?.id
            ?: return null

        val model = modelRepository.getModelById(modelId)
            ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            ?: return null

        if (loadedModelId != model.id || !ggufEngine.isModelLoaded()) {
            val path = model.localPath ?: "/data/models/${model.fileName}"
            val params = _uiState.value.inferenceParams
            ggufEngine.load(
                modelPath = path,
                params = com.masterllm.runtime.gguf.InferenceParams(
                    minP = params.minP,
                    temperature = params.temperature,
                    storeChats = params.storeChats,
                    contextSize = params.contextSize?.toLong() ?: 2048L,
                    chatTemplate = params.systemPrompt,
                    numThreads = params.numThreads,
                    useMmap = true,
                    useMlock = false
                )
            )
            loadedModelId = model.id
        }
        return model
    }

    private fun resolveBackendLabel(): String {
        return if (ggufEngine.isModelLoaded()) "CPU native" else "Not loaded"
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
