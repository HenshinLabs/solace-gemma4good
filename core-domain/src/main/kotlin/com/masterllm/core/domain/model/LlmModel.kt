package com.masterllm.core.domain.model

/**
 * Format of a downloaded or remote ML model.
 */
enum class ModelFormat {
    GGUF,
    SAFETENSORS,
    DIFFUSERS
}

/**
 * Download / local state.
 */
enum class DownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

/**
 * Conversation mode — regular chat or immersive roleplay.
 */
enum class ConversationMode {
    CHAT,
    ROLEPLAY
}

/**
 * Message author role.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    IMAGE_GEN,
    OOC
}

/**
 * Visual style presets for image generation.
 */
enum class VisualStyle(val displayName: String) {
    PHOTOREALISTIC("Photorealistic"),
    FANTASY_ART("Fantasy Art"),
    ANIME("Anime / Manga"),
    OIL_PAINTING("Oil Painting"),
    WATERCOLOR("Watercolor"),
    PIXEL_ART("Pixel Art"),
    COMIC("Comic Book"),
    SKETCH("Pencil Sketch"),
    CINEMATIC("Cinematic"),
    CUSTOM("Custom (raw prompt pass-through)")
}

/**
 * Frequency of auto-image generation during roleplay.
 */
enum class ImageFrequency(val displayName: String) {
    EVERY_RESPONSE("Every response"),
    EVERY_2("Every 2 responses"),
    EVERY_5("Every 5 responses"),
    KEY_MOMENTS("Key moments only (AI decides)"),
    MANUAL("Manual only")
}

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
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val contextLength: Int = 4096,
    val parameterCount: String = "",
    val downloadedAt: Long = 0L,
    val description: String = "",
)

/**
 * Represents a conversation (chat or roleplay).
 */
data class Conversation(
    val id: String = "",
    val title: String = "New Conversation",
    val mode: ConversationMode = ConversationMode.CHAT,
    val modelId: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
)

/**
 * A single message in a conversation.
 */
data class Message(
    val id: String = "",
    val conversationId: String = "",
    val role: MessageRole = MessageRole.USER,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val attachedImagePath: String? = null,
    val isStreaming: Boolean = false,
)

/**
 * Roleplay session configuration.
 */
data class RoleplaySession(
    val id: String = "",
    val conversationId: String = "",
    val title: String = "",
    val genre: String = "",
    val premise: String = "",
    val aiCharacterName: String = "",
    val aiCharacterDescription: String = "",
    val aiCharacterAppearance: String = "",
    val userCharacterName: String = "",
    val userCharacterDescription: String = "",
    val userCharacterAppearance: String = "",
    val worldDetails: String = "",
    val writingStyle: String = "",
    val imageModelId: String? = null,
    val visualStyle: VisualStyle = VisualStyle.FANTASY_ART,
    val imageFrequency: ImageFrequency = ImageFrequency.EVERY_RESPONSE,
    val narrativeResponseCount: Int = 0,
    val lastImagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Character visual cache entry for consistency.
 */
data class CharacterVisualEntry(
    val characterName: String,
    val sessionId: String,
    val anchorPrompt: String,
    val lastImagePath: String?,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Result of the image prompt bridge.
 */
data class ImagePromptResult(
    val prompt: String,
    val negativePrompt: String = "",
    val suggestedSteps: Int = 20,
    val cfgScale: Float = 7.5f,
    val width: Int = 512,
    val height: Int = 512,
)

/**
 * Inference parameters for LLM text generation.
 */
data class InferenceParams(
    val minP: Float = 0.1f,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 2048,
    val systemPrompt: String = "",
    val storeChats: Boolean = true,
    val contextSize: Int? = null,
    val chatTemplate: String? = null,
    val numThreads: Int = 4,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)

/**
 * Hugging Face model search result from API.
 */
data class HfModelInfo(
    val modelId: String = "",
    val author: String = "",
    val sha: String = "",
    val downloads: Int = 0,
    val likes: Int = 0,
    val tags: List<String> = emptyList(),
    val pipelineTag: String = "",
    val siblings: List<HfModelFile> = emptyList(),
    val lastModified: String = "",
    val isPrivate: Boolean = false,
    val description: String = "",
    val cardData: Map<String, String> = emptyMap(),
    val modelCardUrl: String = "",
)

/**
 * A single file (sibling) within a HF repo.
 */
data class HfModelFile(
    val rfilename: String = "",
    val size: Long? = null,
)

/**
 * User profile from HF whoami endpoint.
 */
data class HfUserProfile(
    val username: String = "",
    val avatarUrl: String = "",
    val fullname: String = "",
)
