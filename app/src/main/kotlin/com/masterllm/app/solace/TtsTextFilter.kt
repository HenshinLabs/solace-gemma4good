package com.masterllm.app.solace

object TtsTextFilter {
    // Gemma 4 channel-based thinking blocks
    private val CHANNEL_THINK_REGEX = Regex("<\\|channel>thought\\n[\\s\\S]*?<channel\\|>", RegexOption.IGNORE_CASE)
    // Legacy <think>...</think> blocks
    private val LEGACY_THINK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    // Any residual HTML-like tags
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    // Markdown formatting
    private val MARKDOWN_BOLD = Regex("\\*\\*(.*?)\\*\\*")
    private val MARKDOWN_ITALIC = Regex("\\*(.*?)\\*")
    private val MARKDOWN_HEADER = Regex("#+\\s")
    private val MARKDOWN_CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val MARKDOWN_INLINE_CODE = Regex("`([^`]+)`")
    // Multiple newlines/whitespace
    private val EXCESS_WHITESPACE = Regex("\\n{3,}")

    fun filter(rawModelOutput: String): String {
        return rawModelOutput
            .replace(CHANNEL_THINK_REGEX, "")
            .replace(LEGACY_THINK_REGEX, "")
            .replace(HTML_TAG_REGEX, "")
            .replace(MARKDOWN_CODE_BLOCK, "")
            .replace(MARKDOWN_BOLD, "$1")
            .replace(MARKDOWN_ITALIC, "$1")
            .replace(MARKDOWN_INLINE_CODE, "$1")
            .replace(MARKDOWN_HEADER, "")
            .replace(EXCESS_WHITESPACE, "\n\n")
            .trim()
    }
}
