package com.masterllm.app.solace

/**
 * Streaming token parser for Gemma 4's thinking/channel output.
 *
 * Gemma 4 uses:
 *   <|channel>thought\n ... <channel|>  for thinking blocks
 *
 * NOT <think>...</think> as in some other models.
 *
 * States:
 *   NORMAL       — tokens are part of the visible response
 *   IN_THINKING  — tokens are inside a thinking channel block
 *   BUFFERING    — partial tag detected, waiting for more tokens to disambiguate
 */
class ThinkingTokenParser {
    enum class State { NORMAL, IN_THINKING, BUFFERING }

    var state: State = State.NORMAL
        private set
    val thinkingBuffer: StringBuilder = StringBuilder()
    val responseBuffer: StringBuilder = StringBuilder()

    private val pendingBuffer = StringBuilder()

    private var thinkingStartTimeMs: Long = 0L
    var thinkingDurationMs: Long = 0L
        private set

    companion object {
        private const val THINK_OPEN = "<|channel>thought\n"
        private const val THINK_CLOSE = "<channel|>"

        // Also handle legacy <think>...</think> tags in case llama.cpp
        // maps Gemma 4 output to these (some backends do).
        private const val LEGACY_THINK_OPEN = "<think>"
        private const val LEGACY_THINK_CLOSE = "</think>"
    }

    data class ParseResult(
        val thinkingDelta: String?,
        val responseDelta: String?,
    )

    fun feed(token: String): ParseResult {
        pendingBuffer.append(token)
        val content = pendingBuffer.toString()

        return when (state) {
            State.NORMAL -> handleNormal(content)
            State.IN_THINKING -> handleThinking(content)
            State.BUFFERING -> handleBuffering(content)
        }
    }

    private fun handleNormal(content: String): ParseResult {
        // Check for start of thinking channel
        val channelIdx = content.indexOf("<|channel>thought")
        val legacyIdx = content.indexOf(LEGACY_THINK_OPEN)

        val openIdx: Int
        val openTag: String

        when {
            channelIdx >= 0 && (legacyIdx < 0 || channelIdx <= legacyIdx) -> {
                openIdx = channelIdx
                openTag = THINK_OPEN
            }
            legacyIdx >= 0 -> {
                openIdx = legacyIdx
                openTag = LEGACY_THINK_OPEN
            }
            else -> {
                // Check if content might be a partial match for an opening tag
                if (couldBePartialOpen(content)) {
                    state = State.BUFFERING
                    return ParseResult(thinkingDelta = null, responseDelta = null)
                }
                pendingBuffer.clear()
                responseBuffer.append(content)
                return ParseResult(thinkingDelta = null, responseDelta = content)
            }
        }

        // Check if we have the full opening tag
        val tagEnd = openIdx + openTag.length
        if (tagEnd > content.length) {
            // Partial tag — buffer
            state = State.BUFFERING
            return ParseResult(thinkingDelta = null, responseDelta = null)
        }

        // Emit any text before the tag as response
        val beforeTag = content.substring(0, openIdx)
        val afterTag = content.substring(tagEnd)

        state = State.IN_THINKING
        thinkingStartTimeMs = System.currentTimeMillis()
        pendingBuffer.clear()
        pendingBuffer.append(afterTag)

        responseBuffer.append(beforeTag)

        // Process any content after the tag immediately
        val thinkingResult = if (afterTag.isNotEmpty()) {
            handleThinking(afterTag)
        } else {
            ParseResult(thinkingDelta = null, responseDelta = null)
        }

        return ParseResult(
            thinkingDelta = thinkingResult.thinkingDelta,
            responseDelta = beforeTag.takeIf { it.isNotEmpty() },
        )
    }

    private fun handleThinking(content: String): ParseResult {
        val channelCloseIdx = content.indexOf(THINK_CLOSE)
        val legacyCloseIdx = content.indexOf(LEGACY_THINK_CLOSE)

        val closeIdx: Int
        val closeTag: String

        when {
            channelCloseIdx >= 0 && (legacyCloseIdx < 0 || channelCloseIdx <= legacyCloseIdx) -> {
                closeIdx = channelCloseIdx
                closeTag = THINK_CLOSE
            }
            legacyCloseIdx >= 0 -> {
                closeIdx = legacyCloseIdx
                closeTag = LEGACY_THINK_CLOSE
            }
            else -> {
                // Check for partial close tag
                if (couldBePartialClose(content)) {
                    // Keep buffering — don't emit yet
                    return ParseResult(thinkingDelta = null, responseDelta = null)
                }
                pendingBuffer.clear()
                thinkingBuffer.append(content)
                return ParseResult(thinkingDelta = content, responseDelta = null)
            }
        }

        // Found closing tag
        val thinkingContent = content.substring(0, closeIdx)
        val afterClose = content.substring(closeIdx + closeTag.length)

        state = State.NORMAL
        thinkingDurationMs = System.currentTimeMillis() - thinkingStartTimeMs
        pendingBuffer.clear()

        thinkingBuffer.append(thinkingContent)

        // Any content after the close tag is response
        if (afterClose.isNotEmpty()) {
            responseBuffer.append(afterClose)
        }

        return ParseResult(
            thinkingDelta = thinkingContent.takeIf { it.isNotEmpty() },
            responseDelta = afterClose.takeIf { it.isNotEmpty() },
        )
    }

    private fun handleBuffering(content: String): ParseResult {
        // Try to resolve the ambiguity
        val channelIdx = content.indexOf("<|channel>thought")
        val legacyIdx = content.indexOf(LEGACY_THINK_OPEN)

        val hasFullOpen = (channelIdx >= 0 && channelIdx + THINK_OPEN.length <= content.length) ||
            (legacyIdx >= 0 && legacyIdx + LEGACY_THINK_OPEN.length <= content.length)

        if (hasFullOpen) {
            state = State.NORMAL
            return handleNormal(content)
        }

        if (!couldBePartialOpen(content)) {
            // False alarm — emit as response
            state = State.NORMAL
            pendingBuffer.clear()
            responseBuffer.append(content)
            return ParseResult(thinkingDelta = null, responseDelta = content)
        }

        // Still ambiguous — keep buffering
        return ParseResult(thinkingDelta = null, responseDelta = null)
    }

    private fun couldBePartialOpen(content: String): Boolean {
        if (content.isEmpty()) return false
        val tail = content.takeLast(THINK_OPEN.length.coerceAtMost(content.length))
        return THINK_OPEN.startsWith(tail) || LEGACY_THINK_OPEN.startsWith(tail) ||
            tail.endsWith("<") || tail.endsWith("<|") || tail.endsWith("<|c") ||
            tail.endsWith("<t") || tail.endsWith("<th") || tail.endsWith("<thi")
    }

    private fun couldBePartialClose(content: String): Boolean {
        if (content.isEmpty()) return false
        val tail = content.takeLast(THINK_CLOSE.length.coerceAtMost(content.length))
        return THINK_CLOSE.startsWith(tail) || LEGACY_THINK_CLOSE.startsWith(tail) ||
            tail.endsWith("<") || tail.endsWith("<c") || tail.endsWith("<ch") ||
            tail.endsWith("</") || tail.endsWith("</t")
    }

    fun reset() {
        state = State.NORMAL
        thinkingBuffer.clear()
        responseBuffer.clear()
        pendingBuffer.clear()
        thinkingStartTimeMs = 0L
        thinkingDurationMs = 0L
    }

    fun getThinkingContent(): String = thinkingBuffer.toString()
    fun getResponseContent(): String = responseBuffer.toString()
    fun isCurrentlyThinking(): Boolean = state == State.IN_THINKING
    fun hasThinkingContent(): Boolean = thinkingBuffer.isNotEmpty()
}
