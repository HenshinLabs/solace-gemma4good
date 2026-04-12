package com.masterllm.core.network

/**
 * Removes optional "Bearer" prefix and surrounding whitespace from a user-provided HF token.
 */
fun normalizeHfToken(rawToken: String): String {
    val trimmed = rawToken.trim()
    return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        trimmed.substringAfter(' ').trim()
    } else {
        trimmed
    }
}

/**
 * Converts a token string into an Authorization header value.
 */
fun toBearerAuthHeader(rawToken: String): String? {
    val token = normalizeHfToken(rawToken)
    return token.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
}
