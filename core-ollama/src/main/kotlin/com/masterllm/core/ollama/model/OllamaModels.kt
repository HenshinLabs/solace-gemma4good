package com.masterllm.core.ollama.model

data class OllamaModelInfo(
    val name: String,
    val modified_at: String? = null,
    val size: Long? = null,
    val details: OllamaModelDetails? = null
)

data class OllamaModelDetails(
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    val parameter_size: String? = null
)

data class ListModelsResponse(val models: List<OllamaModelInfo>)

data class ShowModelRequest(val name: String)
data class ShowModelResponse(
    val details: Map<String, Any>? = null,
    val model_info: Map<String, Any>? = null
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val keep_alive: Any = 300,
    val options: Map<String, Any>? = null
)

data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<String>? = null
)

data class ChatResponse(
    val message: ChatResponseMessage? = null,
    val done: Boolean = false,
    val total_duration: Long? = null,
    val eval_count: Int? = null
)

data class ChatResponseMessage(
    val role: String? = null,
    val content: String? = null
)

data class ChatCompletionChunk(
    val message: ChatResponseMessage? = null,
    val done: Boolean = false
)

data class PullModelRequest(
    val name: String,
    val insecure: Boolean = false,
    val stream: Boolean = true
)

data class PullModelResponse(
    val status: String? = null,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null
)

data class GenerateRequest(
    val model: String,
    val keep_alive: Any = 300
)

data class GenerateResponse(val response: String? = null)

data class OllamaValidationResult(
    val isAvailable: Boolean,
    val version: String? = null,
    val error: String? = null
)

data class OllamaLibraryModel(
    val name: String,
    val description: String = "",
    val pulls: String = "",
    val tags: List<String> = emptyList(),
    val lastUpdated: String = "",
)

data class OllamaLibraryModelDetail(
    val name: String,
    val description: String,
    val tags: List<String>,
    val sizes: Map<String, Long>,
    val pulls: Long,
    val lastUpdated: String,
)
