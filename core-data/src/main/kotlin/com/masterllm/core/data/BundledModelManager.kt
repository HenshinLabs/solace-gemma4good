package com.masterllm.core.data

import android.content.Context
import android.util.Log
import java.io.File

object BundledModelManager {
    private const val TAG = "BundledModelManager"
    private const val MODEL_ASSET_PATH = "models/Qwen3.5-0.8B-Q4_K_M.gguf"
    const val MODEL_DISPLAY_NAME = "Qwen3.5-0.8B (Bundled)"
    const val MODEL_ID = "bundled_qwen3.5_0.8b"
    const val ARCHITECTURE = "qwen35"
    const val CONTEXT_LENGTH = 262144
    const val EOS_TOKEN = "<|im_end|>"
    const val QWEN35_CHAT_TEMPLATE = "{% if not messages %}{{ raise_exception('No messages provided.') }}{% endif %}{% if messages[0]['role'] == 'system' %}{{ '<|im_start|>system\\n' + messages[0]['content'] + '<|im_end|>\\n' }}{% endif %}{% for message in messages %}{% if message['role'] == 'system' %}{% if not loop.first %}{{ raise_exception('System message must be at the beginning.') }}{% endif %}{% elif message['role'] == 'user' %}{{ '<|im_start|>user\\n' + message['content'] + '<|im_end|>\\n' }}{% elif message['role'] == 'assistant' %}{{ '<|im_start|>assistant\\n' + message['content'] + '<|im_end|>\\n' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n<think>\\n\\n</think>\\n\\n' }}{% endif %}"

    data class BundledModelInfo(
        val id: String,
        val displayName: String,
        val localPath: String,
        val architecture: String,
        val contextLength: Int,
        val eosToken: String,
    )

    fun initialize(context: Context): BundledModelInfo? {
        return try {
            val modelFile = copyModelFromAssets(context)
            if (modelFile == null) {
                Log.w(TAG, "Bundled model file not found in assets")
                return null
            }
            Log.i(TAG, "Bundled model ready at: ${modelFile.absolutePath}")
            BundledModelInfo(
                id = MODEL_ID,
                displayName = MODEL_DISPLAY_NAME,
                localPath = modelFile.absolutePath,
                architecture = ARCHITECTURE,
                contextLength = CONTEXT_LENGTH,
                eosToken = EOS_TOKEN,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize bundled model: ${e.message}")
            null
        }
    }

    private fun copyModelFromAssets(context: Context): File? {
        return try {
            val targetDir = File(context.filesDir, "models")
            targetDir.mkdirs()
            val targetFile = File(targetDir, "Qwen3.5-0.8B-Q4_K_M.gguf")
            if (targetFile.exists() && targetFile.length() > 0L) {
                Log.i(TAG, "Model already extracted: ${targetFile.absolutePath} (${targetFile.length()})")
                return targetFile
            }
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "Extracted model: ${targetFile.absolutePath} (${targetFile.length()})")
            targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Model asset not found at $MODEL_ASSET_PATH: ${e.message}")
            null
        }
    }

    fun getRecommendedInferenceParams(): Map<String, Any> = mapOf(
        "temperature" to 0.7f,
        "topP" to 0.8f,
        "topK" to 20,
        "minP" to 0.0f,
        "presencePenalty" to 1.5f,
        "repeatPenalty" to 1.0f,
        "contextSize" to 8192,
        "maxTokens" to 4096,
        "numThreads" to Runtime.getRuntime().availableProcessors(),
        "nGpuLayers" to 99,
        "nBatch" to 256,
        "nUbatch" to 128,
        "useMmap" to true,
        "useMlock" to false,
        "chatTemplate" to QWEN35_CHAT_TEMPLATE,
        "agentMode" to true,
    )

    fun isModelAvailable(context: Context): Boolean {
        val targetFile = File(context.filesDir, "models/Qwen3.5-0.8B-Q4_K_M.gguf")
        return targetFile.exists() && targetFile.length() > 0L
    }
}
