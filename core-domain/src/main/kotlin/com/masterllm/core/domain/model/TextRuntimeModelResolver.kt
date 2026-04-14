package com.masterllm.core.domain.model

data class TextRuntimeResolution(
    val runtimeModel: LlmModel,
    val resolutionNote: String? = null,
)

object TextRuntimeModelResolver {

    private fun normalizedQuant(quantization: String): String {
        return quantization.lowercase().replace('-', '_').trim()
    }

    private fun speedScoreForQuant(quantization: String): Int {
        val quant = normalizedQuant(quantization)
        return when {
            quant.contains("q4_k_m") -> 100
            quant.contains("q4_k_s") -> 95
            quant.contains("q4_0") -> 90
            quant.contains("q5_k_m") -> 82
            quant.contains("q5_0") -> 78
            quant.contains("q6_k") -> 68
            quant.contains("q8_0") -> 52
            quant.isNotBlank() -> 40
            else -> 20
        }
    }

    fun findSameRepoGgufFallback(
        selectedModel: LlmModel,
        availableModels: List<LlmModel>,
    ): LlmModel? {
        if (selectedModel.format != ModelFormat.SAFETENSORS) return null

        val selectedQuant = normalizedQuant(selectedModel.quantization)

        return availableModels
            .asSequence()
            .filter { model ->
                model.downloadState == DownloadState.DOWNLOADED &&
                    model.format == ModelFormat.GGUF &&
                    model.repoId.isNotBlank() &&
                    model.repoId == selectedModel.repoId
            }
            .sortedWith(
                compareByDescending<LlmModel> { model ->
                    val quantMatchBonus =
                        if (selectedQuant.isNotBlank() && normalizedQuant(model.quantization) == selectedQuant) 200 else 0
                    speedScoreForQuant(model.quantization) + quantMatchBonus
                }
                    .thenBy { model -> model.sizeBytes }
                    .thenBy { model -> model.displayName.lowercase() }
            )
            .firstOrNull()
    }

    fun resolveForTextGeneration(
        selectedModel: LlmModel,
        availableModels: List<LlmModel>,
        contextLabel: String,
    ): TextRuntimeResolution {
        return when (selectedModel.format) {
            ModelFormat.GGUF -> TextRuntimeResolution(runtimeModel = selectedModel)

            ModelFormat.SAFETENSORS -> {
                val fallback = findSameRepoGgufFallback(
                    selectedModel = selectedModel,
                    availableModels = availableModels,
                )

                if (fallback != null) {
                    val selectedName = selectedModel.displayName.ifBlank { selectedModel.repoId }
                    val fallbackName = fallback.displayName.ifBlank { fallback.repoId }
                    TextRuntimeResolution(
                        runtimeModel = fallback,
                        resolutionNote =
                            "Selected $selectedName uses GGUF runtime fallback: $fallbackName",
                    )
                } else {
                    throw IllegalStateException(
                        "SafeTensors weights were validated, but $contextLabel requires GGUF in llama.cpp. " +
                            "Download a GGUF variant from the same repo, or convert the model offline with " +
                            "llama.cpp convert_hf_to_gguf.py.",
                    )
                }
            }

            ModelFormat.DIFFUSERS -> {
                throw IllegalStateException(
                    "Selected model is DIFFUSERS. $contextLabel requires GGUF.",
                )
            }
        }
    }
}