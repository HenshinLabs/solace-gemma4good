package com.masterllm.app.solace

/**
 * Gemma 4 chat template constants and formatting utilities.
 *
 * Gemma 4 uses a different token format from Gemma 1/2/3:
 *   Turn delimiters: <|turn>role ... <turn|>
 *   Thinking toggle: <|think|> (placed at start of system prompt when thinking is enabled)
 *   Thinking output: <|channel>thought\n ... <channel|>
 *
 * REVIEW: Verify these tokens against the actual chat_template.jinja shipped
 * with the GGUF file once llama.cpp initialises the model.
 */
object Gemma4ChatTemplate {

    // The Jinja2 chat template for Gemma 4, compatible with llama.cpp's template engine.
    // REVIEW: llama.cpp reads the chat template from the GGUF metadata.
    // This constant is a fallback in case the GGUF metadata is missing or malformed.
    const val CHAT_TEMPLATE = """{% for message in messages %}{% if message['role'] == 'system' %}{{ '<|turn>' + message['role'] + '\n' + message['content'] + '\n<turn|>\n' }}{% elif message['role'] == 'user' %}{{ '<|turn>' + message['role'] + '\n' + message['content'] + '<turn|>\n' }}{% elif message['role'] == 'model' or message['role'] == 'assistant' %}{{ '<|turn>model\n' + message['content'] + '<turn|>\n' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<|turn>model\n' }}{% endif %}"""

    const val BOS_TOKEN = "<bos>"
    const val EOS_TOKEN = "<eos>"
    const val TURN_START = "<|turn>"
    const val TURN_END = "<turn|>"
    const val THINK_TOGGLE = "<|think|>"
    const val CHANNEL_OPEN = "<|channel>"
    const val CHANNEL_CLOSE = "<channel|>"

    /**
     * Builds the initial conversation context with system prompt for Gemma 4.
     *
     * @param systemPrompt The system prompt content (e.g., the Solace mental health companion prompt)
     * @param enableThinking Whether to enable the thinking/reasoning channel
     * @return The formatted initial context string
     */
    fun buildSystemContext(systemPrompt: String, enableThinking: Boolean): String {
        val thinkToggle = if (enableThinking) "$THINK_TOGGLE\n" else ""
        return buildString {
            // System turn
            append("${TURN_START}system\n")
            append(thinkToggle)
            append(systemPrompt)
            append("\n${TURN_END}\n")

            // Model acknowledgement turn (hidden from user)
            append("${TURN_START}model\n")
            if (enableThinking) {
                append("${CHANNEL_OPEN}thought\n")
                append("I understand. I am Solace, a compassionate AI mental health companion. ")
                append("I will follow the guidelines above carefully, prioritising the user's ")
                append("safety and emotional wellbeing in every response.\n")
                append("${CHANNEL_CLOSE}")
            }
            append("I'm here for you. How are you feeling right now?")
            append("${TURN_END}\n")
        }
    }

    /**
     * Formats a user message in Gemma 4 turn format.
     */
    fun formatUserTurn(message: String): String =
        "${TURN_START}user\n${message}${TURN_END}\n"

    /**
     * Opens a model turn (for generation prompt).
     */
    fun openModelTurn(): String = "${TURN_START}model\n"

    /**
     * Formats a complete model turn (for history replay).
     */
    fun formatModelTurn(message: String): String =
        "${TURN_START}model\n${message}${TURN_END}\n"

    /**
     * Recommended inference parameters for mental health companion use case.
     */
    fun getRecommendedInferenceParams(): Map<String, Any> = mapOf(
        "temperature" to 0.7f,
        "topP" to 0.9f,
        "topK" to 40,
        "minP" to 0.05f,
        "repeatPenalty" to 1.1f,
        "repeatPenaltyLastN" to 64f,
        "contextSize" to 16384,
        "maxTokens" to 4096,
        "numThreads" to Runtime.getRuntime().availableProcessors().coerceIn(1, 16),
        "nGpuLayers" to 0, // CPU-only by default for broad compatibility
        "nBatch" to 512,
        "nUbatch" to 256,
        "useMmap" to true,
        "useMlock" to false,
        "chatTemplate" to CHAT_TEMPLATE,
    )
}
