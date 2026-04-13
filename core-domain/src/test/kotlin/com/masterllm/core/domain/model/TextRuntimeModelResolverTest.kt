package com.masterllm.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextRuntimeModelResolverTest {

    @Test
    fun resolveForTextGeneration_gguf_keepsSelectedModel() {
        val selected = model(
            id = "gguf-selected",
            repoId = "org/gguf-model",
            format = ModelFormat.GGUF,
        )

        val resolution = TextRuntimeModelResolver.resolveForTextGeneration(
            selectedModel = selected,
            availableModels = listOf(selected),
            contextLabel = "chat text inference",
        )

        assertThat(resolution.runtimeModel.id).isEqualTo(selected.id)
        assertThat(resolution.resolutionNote).isNull()
    }

    @Test
    fun resolveForTextGeneration_safetensors_usesSameRepoGgufFallback() {
        val selected = model(
            id = "safe-selected",
            repoId = "org/model-a",
            format = ModelFormat.SAFETENSORS,
            displayName = "Model A Safe",
        )
        val fallback = model(
            id = "gguf-fallback",
            repoId = "org/model-a",
            format = ModelFormat.GGUF,
            displayName = "Model A GGUF",
        )
        val unrelated = model(
            id = "gguf-unrelated",
            repoId = "org/other",
            format = ModelFormat.GGUF,
            displayName = "Other GGUF",
        )

        val resolution = TextRuntimeModelResolver.resolveForTextGeneration(
            selectedModel = selected,
            availableModels = listOf(selected, unrelated, fallback),
            contextLabel = "chat text inference",
        )

        assertThat(resolution.runtimeModel.id).isEqualTo(fallback.id)
        assertThat(resolution.resolutionNote).contains("uses GGUF runtime fallback")
        assertThat(resolution.resolutionNote).contains("Model A GGUF")
    }

    @Test
    fun resolveForTextGeneration_safetensors_withoutSameRepoGguf_throws() {
        val selected = model(
            id = "safe-selected",
            repoId = "org/model-a",
            format = ModelFormat.SAFETENSORS,
        )
        val unrelatedGguf = model(
            id = "gguf-unrelated",
            repoId = "org/model-b",
            format = ModelFormat.GGUF,
        )

        val error = runCatching {
            TextRuntimeModelResolver.resolveForTextGeneration(
                selectedModel = selected,
                availableModels = listOf(selected, unrelatedGguf),
                contextLabel = "chat text inference",
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error?.message).contains("same repo")
    }

    @Test
    fun resolveForTextGeneration_diffusers_throws() {
        val selected = model(
            id = "diffusers-selected",
            repoId = "org/diff",
            format = ModelFormat.DIFFUSERS,
        )

        val error = runCatching {
            TextRuntimeModelResolver.resolveForTextGeneration(
                selectedModel = selected,
                availableModels = listOf(selected),
                contextLabel = "roleplay text inference",
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error?.message).contains("requires GGUF")
    }

    private fun model(
        id: String,
        repoId: String,
        format: ModelFormat,
        displayName: String = "",
    ): LlmModel {
        return LlmModel(
            id = id,
            repoId = repoId,
            fileName = "$id.bin",
            displayName = displayName,
            format = format,
            downloadState = DownloadState.DOWNLOADED,
        )
    }
}