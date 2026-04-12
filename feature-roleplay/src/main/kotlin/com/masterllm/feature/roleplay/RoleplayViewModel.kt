package com.masterllm.feature.roleplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ConversationRepository
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.RoleplayRepository
import com.masterllm.runtime.gguf.GgufEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

data class RoleplayUiState(
    val sessions: List<RoleplaySession> = emptyList(),
    val currentSession: RoleplaySession? = null,
    val messages: List<Message> = emptyList(),
    val availableModels: List<LlmModel> = emptyList(),
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val showSessionList: Boolean = true,
    val showSetupDialog: Boolean = false,
    // Setup form
    val setupTitle: String = "",
    val setupGenre: String = "Fantasy",
    val setupPremise: String = "",
    val setupAiName: String = "",
    val setupAiDescription: String = "",
    val setupUserName: String = "",
    val setupUserDescription: String = "",
    val setupVisualStyle: VisualStyle = VisualStyle.FANTASY_ART,
    val error: String? = null,
)

sealed interface RoleplayAction {
    data class InputChanged(val text: String) : RoleplayAction
    data object SendMessage : RoleplayAction
    data object StopGeneration : RoleplayAction
    data class SelectSession(val id: String) : RoleplayAction
    data object NewSession : RoleplayAction
    data class DeleteSession(val id: String) : RoleplayAction
    data object BackToList : RoleplayAction
    // Setup
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
}

@HiltViewModel
class RoleplayViewModel @Inject constructor(
    private val roleplayRepository: RoleplayRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val ggufEngine: GgufEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoleplayUiState())
    val uiState: StateFlow<RoleplayUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var generationJob: Job? = null
    private var loadedModelId: String? = null
    private val engineMutex = Mutex()

    init {
        viewModelScope.launch {
            roleplayRepository.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            modelRepository.getDownloadedModels().collect { models ->
                _uiState.update {
                    it.copy(availableModels = models.filter { m ->
                        m.downloadState == DownloadState.DOWNLOADED && m.format == ModelFormat.GGUF
                    })
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
        }
    }

    private fun createSession() {
        val s = _uiState.value
        if (s.setupTitle.isBlank() || s.setupAiName.isBlank()) {
            _uiState.update { it.copy(error = "Title and AI character name required") }
            return
        }

        viewModelScope.launch {
            val selectedModelId = _uiState.value.availableModels.firstOrNull()?.id.orEmpty()
            val convoId = UUID.randomUUID().toString()
            val convo = Conversation(
                id = convoId,
                title = s.setupTitle,
                mode = ConversationMode.ROLEPLAY,
                modelId = selectedModelId,
            )
            conversationRepository.createConversation(convo)

            val session = RoleplaySession(
                id = UUID.randomUUID().toString(),
                conversationId = convoId,
                title = s.setupTitle,
                genre = s.setupGenre,
                premise = s.setupPremise,
                aiCharacterName = s.setupAiName,
                aiCharacterDescription = s.setupAiDescription,
                userCharacterName = s.setupUserName,
                userCharacterDescription = s.setupUserDescription,
                visualStyle = s.setupVisualStyle,
            )
            roleplayRepository.createSession(session)

            // Add system message with RP context
            val systemMsg = Message(
                id = UUID.randomUUID().toString(),
                conversationId = convoId,
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

            // Watch messages
            observeMessages(convoId)
        }
    }

    private fun selectSession(id: String) {
        viewModelScope.launch {
            val session = roleplayRepository.getSessionById(id) ?: return@launch
            _uiState.update {
                it.copy(currentSession = session, showSessionList = false)
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

            val userMsg = Message(
                id = UUID.randomUUID().toString(),
                conversationId = session.conversationId,
                role = MessageRole.USER,
                content = text,
            )
            conversationRepository.addMessage(userMsg)

            _uiState.update { it.copy(isGenerating = true, streamingText = "") }

            try {
                ensureEngineReady(session)
                    ?: throw IllegalStateException("Download a GGUF model in Marketplace before roleplaying.")

                val prompt = buildRoleplayPrompt(session, text)
                val builder = StringBuilder()

                ggufEngine.generate(prompt).collect { token ->
                    if (!_uiState.value.isGenerating) throw CancellationException("Generation stopped")
                    builder.append(token)
                    _uiState.update { it.copy(streamingText = builder.toString()) }
                }

                val finalText = builder.toString().trim()
                if (finalText.isNotEmpty()) {
                    val aiMsg = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.conversationId,
                        role = MessageRole.ASSISTANT,
                        content = finalText,
                    )
                    conversationRepository.addMessage(aiMsg)
                }
            } catch (_: CancellationException) {
                // Explicit stop from UI.
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Roleplay generation failed: ${e.message ?: "unknown error"}")
                }
            } finally {
                _uiState.update { it.copy(isGenerating = false, streamingText = "") }
            }
        }
    }

    private fun stopGeneration() {
        _uiState.update { it.copy(isGenerating = false) }
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

    private suspend fun ensureEngineReady(session: RoleplaySession): String? = engineMutex.withLock {
        val conversation = conversationRepository.getConversationById(session.conversationId)
        val modelId = conversation?.modelId?.takeIf { it.isNotBlank() }
            ?: _uiState.value.availableModels.firstOrNull()?.id
            ?: return null

        val model = modelRepository.getModelById(modelId)
            ?: _uiState.value.availableModels.firstOrNull { it.id == modelId }
            ?: return null

        if (conversation != null && conversation.modelId != model.id) {
            conversationRepository.updateConversation(
                conversation.copy(modelId = model.id, updatedAt = System.currentTimeMillis())
            )
        }

        if (loadedModelId != model.id || !ggufEngine.isModelLoaded()) {
            val path = model.localPath ?: "/data/models/${model.fileName}"
            ggufEngine.loadModel(path).getOrElse { throw it }
            loadedModelId = model.id
        }
        return model.id
    }

    private fun buildRoleplayPrompt(session: RoleplaySession, userText: String): String {
        val history = _uiState.value.messages
            .takeLast(10)
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .joinToString("\n") { "${it.role.name.lowercase()}: ${it.content}" }

        return buildString {
            appendLine(buildSystemPrompt(session))
            if (history.isNotBlank()) {
                appendLine("Conversation history:")
                appendLine(history)
            }
            appendLine("user: $userText")
            append("assistant:")
        }
    }

    private fun buildSystemPrompt(session: RoleplaySession): String {
        return buildString {
            appendLine("You are roleplaying as ${session.aiCharacterName}.")
            if (session.aiCharacterDescription.isNotEmpty())
                appendLine("Character: ${session.aiCharacterDescription}")
            if (session.genre.isNotEmpty())
                appendLine("Genre: ${session.genre}")
            if (session.premise.isNotEmpty())
                appendLine("Premise: ${session.premise}")
            if (session.userCharacterName.isNotEmpty())
                appendLine("The user plays as ${session.userCharacterName}.")
            if (session.userCharacterDescription.isNotEmpty())
                appendLine("User character: ${session.userCharacterDescription}")
            appendLine("Write in third person, use *actions* for physical actions.")
            appendLine("Keep responses immersive and in-character.")
        }
    }
}
