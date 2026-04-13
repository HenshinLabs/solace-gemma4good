package com.masterllm.feature.roleplay

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
import com.masterllm.runtime.safetensors.SafetensorsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val threadCount: Int,
    val gpuLayers: Int,
    val contextSize: Int,
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
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
    val setupTitle: String = "",
    val setupGenre: String = "Fantasy",
    val setupPremise: String = "",
    val setupAiName: String = "",
    val setupAiDescription: String = "",
    val setupUserName: String = "",
    val setupUserDescription: String = "",
    val setupVisualStyle: VisualStyle = VisualStyle.FANTASY_ART,
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
    private val safetensorsEngine: SafetensorsEngine,
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
    private var gpuAccelerationEnabled: Boolean = false
    private val engineMutex = Mutex()

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
                        (model.format == ModelFormat.GGUF || model.format == ModelFormat.SAFETENSORS)
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
                    gpuAccelerationEnabled = gpuEnabled
                    _uiState.update { state ->
                        state.copy(
                            inferenceParams = state.inferenceParams.copy(
                                numThreads = threadCount.coerceAtLeast(1),
                            ),
                        )
                    }
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
        _uiState.update { state ->
            state.copy(inferenceParams = update(state.inferenceParams))
        }
    }

    private fun selectModel(modelId: String) {
        viewModelScope.launch {
            if (_uiState.value.isGenerating) {
                stopGeneration()
            }

            _uiState.update { it.copy(selectedModelId = modelId) }

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
                ggufEngine.close()
                loadedModelId = null
            }
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
                ?: _uiState.value.availableModels.firstOrNull()?.id
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
            _uiState.update {
                it.copy(
                    currentSession = session,
                    showSessionList = false,
                    selectedModelId = conversation?.modelId?.takeIf { modelId -> modelId.isNotBlank() }
                        ?: it.selectedModelId,
                )
            }
            observeMessages(session.conversationId)
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
                    _uiState.update { it.copy(streamingText = builder.toString()) }
                }

                val finalText = builder.toString().trim()
                val completedAtNs = System.nanoTime()
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
                val generatedTokens = (ggufEngine.getContextLengthUsed() - promptTokens).coerceAtLeast(0)
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
                val nativePromptSpeed = ggufEngine.getPromptProcessingSpeed()
                val nativeDecodeSpeed = ggufEngine.getResponseGenerationSpeed()
                val activeThreads = ggufEngine.getConfiguredThreadCount().coerceAtLeast(1)
                val activeGpuLayers = ggufEngine.getConfiguredGpuLayers().coerceAtLeast(0)

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
                            threadCount = activeThreads,
                            gpuLayers = activeGpuLayers,
                            contextSize = state.inferenceParams.contextSize ?: 2048,
                        ),
                    )
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
            }
        }
    }

    private fun stopGeneration() {
        _uiState.update { it.copy(isGenerating = false, generationStatus = null) }
        generationJob?.cancel()
        generationJob = null
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
        if (loadedModelId != textRuntimeModel.id || !ggufEngine.isModelLoaded()) {
            val path = textRuntimeModel.localPath ?: "/data/models/${textRuntimeModel.fileName}"

            val loadStartedAtNs = System.nanoTime()
            ggufEngine.load(
                modelPath = path,
                params = _uiState.value.inferenceParams,
                gpuAccelerationEnabled = gpuAccelerationEnabled,
                gpuOffloadLayers = resolveDesiredGpuLayers(),
            )
            loadDurationMs = ((System.nanoTime() - loadStartedAtNs) / 1_000_000L).coerceAtLeast(0L)

            replayedMessages = replayConversationContext(
                conversationId = session.conversationId,
                configuredSystemPrompt = _uiState.value.inferenceParams.systemPrompt,
            )

            loadedModelId = textRuntimeModel.id
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

    private fun resolveDesiredGpuLayers(): Int = if (gpuAccelerationEnabled) 99 else 0

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
