package com.masterllm.core.data

import android.content.Context
import android.util.Log
import java.io.File

object BundledModelManager {
    private const val TAG = "Solace.BundledModelMgr"

    // Gemma 4 E2B — downloaded at runtime, not bundled in APK assets.
    const val MODEL_DISPLAY_NAME = "Gemma 4 E2B (Q4_K_M)"
    const val MODEL_ID = "gemma4_e2b_q4km"
    // REVIEW: Verify the GGUF metadata reports "gemma4" as architecture
    const val ARCHITECTURE = "gemma4"
    const val CONTEXT_LENGTH = 131072 // 128K verified from Gemma 4 model card
    const val EOS_TOKEN = "<eos>"
    const val MODEL_FILENAME = "gemma-4-E2B-it-Q4_K_M.gguf"

    // Gemma 4 chat template — uses <|turn>/<turn|> delimiters
    // REVIEW: llama.cpp reads the template from GGUF metadata; this is a fallback.
    const val GEMMA4_CHAT_TEMPLATE = "{% for message in messages %}{% if message['role'] == 'system' %}{{ '<|turn>' + message['role'] + '\\n' + message['content'] + '\\n<turn|>\\n' }}{% elif message['role'] == 'user' %}{{ '<|turn>' + message['role'] + '\\n' + message['content'] + '<turn|>\\n' }}{% elif message['role'] == 'model' or message['role'] == 'assistant' %}{{ '<|turn>model\\n' + message['content'] + '<turn|>\\n' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '<|turn>model\\n' }}{% endif %}"

    data class BundledModelInfo(
        val id: String,
        val displayName: String,
        val localPath: String,
        val architecture: String,
        val contextLength: Int,
        val eosToken: String,
    )

    fun initialize(context: Context): BundledModelInfo? {
        val modelFile = getModelFile(context)
        if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
            Log.i(TAG, "Model not yet downloaded — will be fetched on first launch via ModelDownloadManager")
            return null
        }
        Log.i(TAG, "Model available at: ${modelFile.absolutePath}")
        return BundledModelInfo(
            id = MODEL_ID,
            displayName = MODEL_DISPLAY_NAME,
            localPath = modelFile.absolutePath,
            architecture = ARCHITECTURE,
            contextLength = CONTEXT_LENGTH,
            eosToken = EOS_TOKEN,
        )
    }

    private fun getModelFile(context: Context): File? {
        // Check external files dir first (download location)
        val externalDir = File(context.getExternalFilesDir(null), "models")
        val externalFile = File(externalDir, MODEL_FILENAME)
        if (externalFile.exists() && externalFile.length() > 0L) return externalFile

        // Fallback to internal files dir
        val internalFile = File(context.filesDir, "models/$MODEL_FILENAME")
        if (internalFile.exists() && internalFile.length() > 0L) return internalFile

        return null
    }

    fun getRecommendedInferenceParams(): Map<String, Any> = mapOf(
        "temperature" to 0.7f,
        "topP" to 0.9f,
        "topK" to 40,
        "minP" to 0.05f,
        "repeatPenalty" to 1.1f,
        "repeatPenaltyLastN" to 64f,
        "contextSize" to 16384, // Use 16K default for RAM safety; user can increase
        "maxTokens" to 4096,
        "numThreads" to Runtime.getRuntime().availableProcessors().coerceIn(1, 16),
        "nGpuLayers" to 0, // CPU-only default for broad device compatibility
        "nBatch" to 512,
        "nUbatch" to 256,
        "useMmap" to true,
        "useMlock" to false,
        "chatTemplate" to GEMMA4_CHAT_TEMPLATE,
    )

    fun isModelAvailable(context: Context): Boolean {
        return getModelFile(context)?.let { it.exists() && it.length() > 0L } ?: false
    }
}
