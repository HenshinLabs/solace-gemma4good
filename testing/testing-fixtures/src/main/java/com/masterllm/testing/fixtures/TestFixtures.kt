package com.masterllm.testing.fixtures

import com.masterllm.core.domain.model.DownloadedModel
import com.masterllm.core.domain.model.ModelFormat

object TestFixtures {
    fun ggufModel(
        repoId: String = "TheBloke/Llama-3-8B-GGUF",
        quantization: String = "Q4_K_M",
    ): DownloadedModel {
        return DownloadedModel(
            repoId = repoId,
            modelFormat = ModelFormat.GGUF,
            localPath = "/tmp/$repoId/$quantization.gguf",
            fileSizeBytes = 4_500_000_000,
            downloadedAtEpochMillis = System.currentTimeMillis(),
            quantization = quantization,
            contextLength = 4096,
            parameterCount = "8B",
            supportsRoleplay = true,
        )
    }
}
