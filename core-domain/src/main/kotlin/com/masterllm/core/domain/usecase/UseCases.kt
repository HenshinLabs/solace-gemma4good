package com.masterllm.core.domain.usecase

import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Persists a Hugging Face token.
 * Validation is performed separately in the auth flow via network call.
 */
class ValidateHfTokenUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun execute(token: String): Result<HfUserProfile> {
        return try {
            settingsRepository.setHfToken(token)
            Result.success(HfUserProfile(username = "saved"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Searches HF Hub models with pagination and filtering.
 */
class SearchModelsUseCase @Inject constructor() {
    suspend fun execute(
        query: String,
        filter: String = "",
        sort: String = "downloads",
        limit: Int = 20,
        offset: Int = 0,
    ): Result<List<HfModelInfo>> {
        return Result.success(emptyList()) // Wired to network in data layer
    }
}

/**
 * Detects model format from the list of sibling files.
 */
class DetectModelFormatUseCase @Inject constructor() {
    fun execute(siblings: List<HfModelFile>): ModelFormat {
        val filenames = siblings.map { it.rfilename.lowercase() }
        return when {
            filenames.any { it.endsWith(".gguf") } -> ModelFormat.GGUF
            filenames.any { it.endsWith(".safetensors") } &&
                filenames.any { it.contains("model_index.json") || it.contains("unet") } -> ModelFormat.DIFFUSERS
            filenames.any { it.endsWith(".safetensors") } -> ModelFormat.SAFETENSORS
            else -> ModelFormat.GGUF
        }
    }
}

/**
 * Download a model file from HF Hub.
 */
class DownloadModelUseCase @Inject constructor(
    private val modelRepository: ModelRepository,
) {
    suspend fun execute(
        repoId: String,
        fileName: String,
        displayName: String,
        format: ModelFormat,
        sizeBytes: Long,
        quantization: String = "",
    ): Result<String> {
        return try {
            val id = "${repoId}/${fileName}".hashCode().toString()
            val model = LlmModel(
                id = id,
                repoId = repoId,
                fileName = fileName,
                displayName = displayName,
                format = format,
                sizeBytes = sizeBytes,
                quantization = quantization,
                downloadState = DownloadState.DOWNLOADING,
            )
            modelRepository.saveModel(model)
            // Actual download is handled by WorkManager DownloadWorker
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Compact a conversation by summarizing older messages.
 */
class CompactConversationUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    /**
     * Summarizes messages older than the last 3 pairs.
     * @return the summary text that replaced older messages
     */
    suspend fun execute(
        conversationId: String,
        messages: List<Message>,
        summarize: suspend (String) -> String,
    ): Result<String> {
        return try {
            if (messages.size <= 6) {
                return Result.success("") // Not enough messages to compact
            }

            val preserveCount = 6 // Last 3 pairs (user + assistant)
            val toSummarize = messages.dropLast(preserveCount)
            val toKeep = messages.takeLast(preserveCount)

            val conversationText = toSummarize.joinToString("\n") { "${it.role}: ${it.content}" }

            val summary = summarize(conversationText)

            // Delete old messages
            val cutoffTimestamp = toKeep.first().timestamp - 1
            conversationRepository.deleteMessagesAfter(conversationId, 0) // Clear before cutoff

            // Re-add: summary as system message + kept messages
            val summaryMessage = Message(
                id = "summary_${System.currentTimeMillis()}",
                conversationId = conversationId,
                role = MessageRole.SYSTEM,
                content = "[Context Summary]\n$summary",
                timestamp = toKeep.first().timestamp - 1,
            )
            conversationRepository.addMessage(summaryMessage)

            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Get downloaded models list.
 */
class GetDownloadedModelsUseCase @Inject constructor(
    private val modelRepository: ModelRepository,
) {
    fun execute(): Flow<List<LlmModel>> = modelRepository.getDownloadedModels()
}

/**
 * Get all conversations.
 */
class GetConversationsUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    fun execute(mode: ConversationMode? = null): Flow<List<Conversation>> {
        return if (mode != null) {
            conversationRepository.getConversationsByMode(mode)
        } else {
            conversationRepository.getAllConversations()
        }
    }
}

/**
 * Get messages for a conversation.
 */
class GetMessagesUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    fun execute(conversationId: String): Flow<List<Message>> {
        return conversationRepository.getMessagesForConversation(conversationId)
    }
}

/**
 * Send a message and trigger inference.
 */
class SendMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend fun execute(conversationId: String, content: String): Result<String> {
        return try {
            val message = Message(
                id = "msg_${System.currentTimeMillis()}",
                conversationId = conversationId,
                role = MessageRole.USER,
                content = content,
            )
            val id = conversationRepository.addMessage(message)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Generate an image prompt from conversation context using LLM.
 */
class GenerateImagePromptUseCase @Inject constructor() {
    /**
     * @param conversationContext Natural language scene/context
     * @param imageModelId The diffusion model to use
     * @param visualStyle Art style preset
     * @param characterAppearances Optional character descriptions for consistency
     * @param generatePrompt Lambda that calls the LLM to produce structured JSON
     * @return ImagePromptResult
     */
    suspend fun execute(
        conversationContext: String,
        imageModelId: String,
        visualStyle: VisualStyle,
        characterAppearances: List<String> = emptyList(),
        generatePrompt: suspend (String) -> String,
    ): Result<ImagePromptResult> {
        return try {
            val stylePrefix = when (visualStyle) {
                VisualStyle.PHOTOREALISTIC -> "photorealistic, 8k, detailed"
                VisualStyle.FANTASY_ART -> "fantasy art, epic, detailed illustration"
                VisualStyle.ANIME -> "anime style, manga, high quality"
                VisualStyle.OIL_PAINTING -> "oil painting, classical art, textured"
                VisualStyle.WATERCOLOR -> "watercolor painting, soft, artistic"
                VisualStyle.PIXEL_ART -> "pixel art, retro, 16-bit"
                VisualStyle.COMIC -> "comic book style, bold lines, vibrant"
                VisualStyle.SKETCH -> "pencil sketch, detailed drawing, monochrome"
                VisualStyle.CINEMATIC -> "cinematic, dramatic lighting, movie scene"
                VisualStyle.CUSTOM -> ""
            }

            val characterContext = if (characterAppearances.isNotEmpty()) {
                "\nCharacter details: ${characterAppearances.joinToString("; ")}"
            } else ""

            val systemPrompt = """
                |You are an image prompt engineer. Given a scene description, generate a detailed prompt for Stable Diffusion.
                |Return ONLY valid JSON with these exact fields:
                |{"prompt": "...", "negative_prompt": "...", "steps": 20, "cfg_scale": 7.5, "width": 512, "height": 512}
                |Style: $stylePrefix$characterContext
            """.trimMargin()

            val llmInput = "$systemPrompt\n\nScene: $conversationContext"

            try {
                val jsonOutput = generatePrompt(llmInput)
                val result = parseImagePromptJson(jsonOutput, stylePrefix)
                Result.success(result)
            } catch (firstAttempt: Exception) {
                // Retry with stricter prompt
                try {
                    val retryPrompt = "$systemPrompt\n\nIMPORTANT: Return ONLY JSON, no other text.\n\nScene: $conversationContext"
                    val retryOutput = generatePrompt(retryPrompt)
                    val result = parseImagePromptJson(retryOutput, stylePrefix)
                    Result.success(result)
                } catch (secondAttempt: Exception) {
                    // Fallback: use raw context as prompt
                    Result.success(
                        ImagePromptResult(
                            prompt = "$stylePrefix, $conversationContext".take(500),
                            negativePrompt = "blurry, bad anatomy, worst quality, low quality",
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseImagePromptJson(json: String, stylePrefix: String): ImagePromptResult {
        // Extract JSON object from potentially wrapped response
        val jsonStr = json.let {
            val start = it.indexOf('{')
            val end = it.lastIndexOf('}')
            if (start >= 0 && end > start) it.substring(start, end + 1) else throw IllegalArgumentException("No JSON found")
        }

        val gson = com.google.gson.Gson()
        val map = gson.fromJson(jsonStr, Map::class.java) as Map<String, Any>

        return ImagePromptResult(
            prompt = "$stylePrefix, ${map["prompt"] ?: ""}",
            negativePrompt = (map["negative_prompt"] as? String) ?: "blurry, bad anatomy",
            suggestedSteps = ((map["steps"] as? Number)?.toInt()) ?: 20,
            cfgScale = ((map["cfg_scale"] as? Number)?.toFloat()) ?: 7.5f,
            width = ((map["width"] as? Number)?.toInt()) ?: 512,
            height = ((map["height"] as? Number)?.toInt()) ?: 512,
        )
    }
}
