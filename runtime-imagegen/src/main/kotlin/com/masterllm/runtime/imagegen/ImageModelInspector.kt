package com.masterllm.runtime.imagegen

import org.json.JSONObject
import java.io.File

enum class ImageBackendType {
    DIFFUSERS,
    SAFETENSORS,
}

internal object ImageModelInspector {
    fun detectBackend(path: File): ImageBackendType {
        if (path.isFile) {
            require(path.extension.equals("safetensors", ignoreCase = true)) {
                "Unsupported file type for image generation: ${path.name}"
            }
            return ImageBackendType.SAFETENSORS
        }

        val hasModelIndex = File(path, "model_index.json").exists()
        val hasSafetensors = path.walkTopDown()
            .maxDepth(6)
            .any { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }

        require(hasModelIndex || hasSafetensors) {
            "Directory is not a valid Diffusers/SafeTensors model: ${path.absolutePath}"
        }
        return if (hasModelIndex) ImageBackendType.DIFFUSERS else ImageBackendType.SAFETENSORS
    }

    fun collectSafetensors(path: File): List<File> {
        return if (path.isFile) {
            listOf(path)
        } else {
            path.walkTopDown()
                .maxDepth(8)
                .filter { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }
                .toList()
        }
    }

    fun normalizeDimension(value: Int): Int {
        val clamped = value.coerceIn(256, 1024)
        return (clamped / 64) * 64
    }

    fun parseConditioningScale(modelIndexFile: File): Float {
        return runCatching {
            val root = JSONObject(modelIndexFile.readText())
            val schedulerClass = root
                .optJSONObject("scheduler")
                ?.optString("_class_name")
                .orEmpty()
                .lowercase()

            when {
                "euler" in schedulerClass -> 1.18f
                "dpm" in schedulerClass -> 1.12f
                "ddim" in schedulerClass -> 1.08f
                "pndm" in schedulerClass -> 1.04f
                else -> 1.0f
            }
        }.getOrDefault(1.0f)
    }
}
