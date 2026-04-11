package com.masterllm.core.domain.model

/**
 * Represents a downloaded or available LLM model.
 */
data class LlmModel(
    val id: String = "",
    val repoId: String = "",
    val fileName: String = "",
    val displayName: String = "",
    val format: ModelFormat = ModelFormat.GGUF,
    val sizeBytes: Long = 0L,
    val quantization: String = "",
    val localPath: String? = null,
    val isDownloaded: Boolean = false,
)

enum class ModelFormat {
    GGUF,
    SAFETENSORS
}
