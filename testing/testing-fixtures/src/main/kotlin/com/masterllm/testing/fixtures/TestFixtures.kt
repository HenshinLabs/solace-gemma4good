package com.masterllm.testing.fixtures

import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.domain.model.ModelFormat

/**
 * Pre-built test fixtures for domain models.
 */
object TestFixtures {
    fun sampleGgufModel(
        id: String = "test-model-1",
        displayName: String = "Test Model Q4",
    ) = LlmModel(
        id = id,
        repoId = "test-org/$id",
        fileName = "$id.gguf",
        displayName = displayName,
        format = ModelFormat.GGUF,
        sizeBytes = 4_000_000_000L,
        quantization = "Q4_K_M",
    )
}
