package com.masterllm.app.openclaw

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentUiState(
    val messages: List<AgentMessage> = emptyList(),
    val inputText: String = "",
    val isProcessing: Boolean = false,
    val status: AgentStatus = AgentStatus.Idle,
    val session: AgentSession = AgentSession(),
    val modelReady: Boolean = false,
    val statusMessage: String = "",
    val tools: List<OpenClawTool> = emptyList(),
)

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val engine: OpenClawEngine,
    private val toolRegistry: ToolRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentUiState(tools = toolRegistry.getAllTools()))
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val tag = "AgentViewModel"

    fun checkModelStatus() {
        _uiState.update { it.copy(
            modelReady = engine.isModelLoaded(),
            statusMessage = if (engine.isModelLoaded()) "Qwen3.5 ready" else "Load model in Chat tab first"
        ) }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty() || _uiState.value.isProcessing) return

        _uiState.update { it.copy(inputText = "", isProcessing = true) }

        viewModelScope.launch {
            val session = engine.processQuery(
                userInput = text,
                session = _uiState.value.session,
                maxTurns = 5,
                onStatus = { status ->
                    _uiState.update { state ->
                        when (status) {
                            is AgentStatus.Idle -> state.copy(isProcessing = false, status = status)
                            is AgentStatus.Thinking -> state.copy(
                                status = status,
                                statusMessage = status.partialText,
                            )
                            is AgentStatus.ExecutingTool -> state.copy(
                                status = status,
                                statusMessage = "⚡ ${status.toolName}(${status.params.values.joinToString(", ")})",
                            )
                            is AgentStatus.Error -> state.copy(
                                isProcessing = false,
                                status = status,
                                statusMessage = status.message,
                            )
                        }
                    }
                }
            )

            _uiState.update {
                it.copy(
                    session = session,
                    messages = session.messages,
                    isProcessing = false,
                    status = AgentStatus.Idle,
                )
            }
        }
    }

    fun clearSession() {
        _uiState.update {
            AgentUiState(tools = toolRegistry.getAllTools())
        }
    }

    fun addMessage(role: String, content: String) {
        val msg = AgentMessage(role = role, content = content)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + msg,
                session = state.session.copy(
                    messages = state.session.messages + msg
                ),
            )
        }
    }
}
