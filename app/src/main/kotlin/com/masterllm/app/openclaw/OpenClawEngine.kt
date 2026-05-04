package com.masterllm.app.openclaw

import android.util.Log
import com.masterllm.runtime.gguf.GgufEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AgentMessage(
    val role: String,
    val content: String,
    val isToolCall: Boolean = false,
    val isToolResult: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AgentSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val messages: List<AgentMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val title: String = "OpenClaw Session",
)

sealed class AgentStatus {
    data object Idle : AgentStatus()
    data class Thinking(val partialText: String) : AgentStatus()
    data class ExecutingTool(val toolName: String, val params: Map<String, String>) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
}

@Singleton
class OpenClawEngine @Inject constructor(
    private val ggufEngine: GgufEngine,
    private val toolRegistry: ToolRegistry,
) {
    private val tag = "OpenClawEngine"

    fun isModelLoaded(): Boolean = ggufEngine.isModelLoaded()
    private val toolCallRegex = Regex(
        "<tool_call>\\s*<function=([^>]+)>([\\s\\S]*?)</function>\\s*</tool_call>"
    )
    private val paramRegex = Regex(
        "<parameter=([^>]+)>\\n([\\s\\S]*?)\\n</parameter>"
    )

    suspend fun processQuery(
        userInput: String,
        session: AgentSession,
        maxTurns: Int = 5,
        onStatus: (AgentStatus) -> Unit = {},
    ): AgentSession {
        var currentSession = session
        val updatedMessages = session.messages.toMutableList()

        // Add user message
        updatedMessages.add(AgentMessage(role = "user", content = userInput))

        // Ensure model loaded
        if (!ggufEngine.isModelLoaded()) {
            onStatus(AgentStatus.Error("Model not loaded. Load a model in Chat tab first."))
            return session
        }

        try {
            var turnCount = 0

            // Set system prompt
            ggufEngine.addSystemPrompt(toolRegistry.buildSystemPrompt())

            // Main agent loop
            while (turnCount < maxTurns) {
                turnCount++
                onStatus(AgentStatus.Thinking("Processing (turn $turnCount)..."))

                val context = buildConversationContext(updatedMessages)

                // Get response from model
                val response = generateResponse(context)

                // Parse tool calls
                val toolCalls = parseToolCalls(response)

                if (toolCalls.isEmpty()) {
                    // No tool calls — this is the final response
                    updatedMessages.add(AgentMessage(
                        role = "assistant", content = response.trim()
                    ))
                    onStatus(AgentStatus.Idle)
                    return currentSession.copy(messages = updatedMessages)
                }

                // Add the assistant message with tool calls
                updatedMessages.add(AgentMessage(
                    role = "assistant", content = response.trim(), isToolCall = true
                ))

                val toolResponseContext = StringBuilder(response)

                // Execute each tool call
                for (toolCall in toolCalls) {
                    onStatus(AgentStatus.ExecutingTool(toolCall.name, toolCall.params))

                    val tool = toolRegistry.getTool(toolCall.name)
                    if (tool == null) {
                        val error = "Unknown tool: ${toolCall.name}"
                        updatedMessages.add(AgentMessage(
                            role = "tool", content = error, isToolResult = true
                        ))
                        toolResponseContext.append("\n<tool_response>\n$error\n</tool_response>\n")
                        continue
                    }

                    val result = withContext(Dispatchers.IO) {
                        try {
                            tool.executor(toolCall.params)
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                    }

                    updatedMessages.add(AgentMessage(
                        role = "tool", content = result.take(3000), isToolResult = true
                    ))
                    toolResponseContext.append("\n<tool_response>\n${result.take(3000)}\n</tool_response>\n")
                }

                // Feed tool results back to model for continuation
                ggufEngine.addUserMessage(toolResponseContext.toString())
            }

            // Max turns reached — return last response
            onStatus(AgentStatus.Idle)
            return currentSession.copy(messages = updatedMessages)

        } catch (e: Exception) {
            Log.e(tag, "Agent error: ${e.message}")
            updatedMessages.add(AgentMessage(
                role = "assistant",
                content = "Error: ${e.message}",
            ))
            onStatus(AgentStatus.Error(e.message ?: "Unknown error"))
            return currentSession.copy(messages = updatedMessages)
        }
    }

    private fun buildConversationContext(messages: List<AgentMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                "user" -> sb.append("<|im_start|>user\n${msg.content}\n<|im_end|>\n")
                "assistant" -> sb.append("<|im_start|>assistant\n${msg.content}\n<|im_end|>\n")
                "tool" -> sb.append("<|im_start|>user\n<tool_response>\n${msg.content}\n</tool_response>\n<|im_end|>\n")
            }
        }
        return sb.toString()
    }

    private suspend fun generateResponse(context: CharSequence): String {
        val response = StringBuilder()
        ggufEngine.getResponseAsFlow(context.toString(), maxTokens = 4096).collect { piece ->
            response.append(piece)
        }
        return response.toString()
    }

    private fun parseToolCalls(response: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        for (match in toolCallRegex.findAll(response)) {
            val funcName = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            val params = mutableMapOf<String, String>()
            for (pm in paramRegex.findAll(body)) {
                params[pm.groupValues[1].trim()] = pm.groupValues[2].trim()
            }
            calls.add(ToolCall(funcName, params))
        }
        return calls
    }
}
