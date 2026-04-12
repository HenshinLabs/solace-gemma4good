package com.masterllm.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HfAuthTest {

    @Test
    fun normalizeHfToken_stripsBearerPrefix() {
        assertThat(normalizeHfToken("Bearer hf_abc123")).isEqualTo("hf_abc123")
        assertThat(normalizeHfToken(" bearer hf_xyz789 ")).isEqualTo("hf_xyz789")
    }

    @Test
    fun normalizeHfToken_keepsRawTokenWhenNoPrefix() {
        assertThat(normalizeHfToken("hf_plain_token")).isEqualTo("hf_plain_token")
    }

    @Test
    fun toBearerAuthHeader_returnsNullForBlankInput() {
        assertThat(toBearerAuthHeader("   ")).isNull()
    }

    @Test
    fun toBearerAuthHeader_normalizesAndBuildsHeader() {
        assertThat(toBearerAuthHeader("Bearer hf_abc")).isEqualTo("Bearer hf_abc")
    }
}
