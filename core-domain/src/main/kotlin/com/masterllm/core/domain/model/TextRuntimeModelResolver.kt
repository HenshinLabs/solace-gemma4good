package com.masterllm.core.domain.model

data class TextRuntimeResolution(
    val runtimeModel: LlmModel,
    val resolutionNote: String? = null,
)

object TextRuntimeModelResolver {

    fun findSameRepoGgufFallback(
        selectedModel: LlmModel,
        availableModels: List<LlmModel>,
    ): LlmModel? {
        if (selectedModel.format != ModelFormat.SAFETENSORS) return null

        return availableModels.firstOrNull { model ->
            model.downloadState == DownloadState.DOWNLOADED &&
                model.format == ModelFormat.GGUF &&
                model.repoId.isNotBlank() &&
                model.repoId == selectedModel.repoId
        }
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