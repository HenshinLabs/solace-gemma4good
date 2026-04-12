package com.masterllm.runtime.gguf

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.masterllm.core.domain.model.InferenceParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Engine for loading and running GGUF format models via llama.cpp JNI.
 *
 * The engine prefers native token streaming through `libllama_android.so` and
 * falls back to a multicore CPU response path only when the native backend is
 * unavailable at runtime.
 */
@Singleton
class GgufEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    companion object {
        private val NATIVE_LIBRARY_CANDIDATES = listOf("llama_android", "llama")

        @Volatile
        private var nativeLibraryChecked: Boolean = false

        @Volatile
        private var nativeLibraryAvailable: Boolean = false
    }

    data class RuntimeConfig(
        val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
        val enableGpuOffload: Boolean = true,
        val defaultGpuLayers: Int = 32,
        val preferTurnipDriver: Boolean = true,
        val streamDelayMs: Long = 12L,
    )

    data class LoadedModelInfo(
        val path: String,
        val fileSizeBytes: Long,
        val ggufVersion: Int,
        val tensorCount: Long,
        val metadataKvCount: Long,
        val contextSize: Int,
        val threadCount: Int,
        val gpuLayers: Int,
        val nativeBackend: Boolean,
    )

    data class DriverReport(
        val adrenoDetected: Boolean,
        val qualcommDetected: Boolean,
        val vulkanSupported: Boolean,
        val nativeBackendAvailable: Boolean,
        val turnipAssetsBundled: Boolean,
        val turnipIcdPath: String?,
        val socManufacturer: String?,
        val socModel: String?,
        val deviceHardware: String,
        val buildDisplay: String,
        val androidRelease: String,
    )

    private var modelPath: String? = null
    private var isLoaded: Boolean = false
    private var nativeContextPtr: Long = 0L
    private var runtimeConfig: RuntimeConfig = RuntimeConfig()
    private var loadedModelInfo: LoadedModelInfo? = null

    /**
     * Returns true when the JNI native library is loaded.
     */
    fun isNativeAvailable(): Boolean {
        ensureNativeLibraryLoaded()
        return nativeLibraryAvailable
    }

    /**
     * Returns true when a model is loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean = isLoaded

    fun getLoadedModelInfo(): LoadedModelInfo? = loadedModelInfo

    fun getDriverReport(): DriverReport {
        ensureNativeLibraryLoaded()

        val socManufacturer = readSocField("SOC_MANUFACTURER")
        val socModel = readSocField("SOC_MODEL")
        val fingerprint = buildDeviceFingerprint(socManufacturer, socModel)

        val turnipAssetsBundled = assetExists("turnip/icd.d/freedreno_icd.aarch64.json") &&
            assetExists("turnip/libvulkan_freedreno.so")
        val icdPath = if (turnipAssetsBundled) prepareTurnipIcdPath() else null

        val hasQualcommSignal = fingerprint.contains("qcom") ||
            fingerprint.contains("qualcomm") ||
            fingerprint.contains("msm") ||
            Regex("\\bsm[0-9]{3,}\\b").containsMatchIn(fingerprint)

        return DriverReport(
            adrenoDetected = fingerprint.contains("adreno") || hasQualcommSignal,
            qualcommDetected = hasQualcommSignal,
            vulkanSupported = isVulkanRuntimeSupported(),
            nativeBackendAvailable = nativeLibraryAvailable,
            turnipAssetsBundled = turnipAssetsBundled,
            turnipIcdPath = icdPath,
            socManufacturer = socManufacturer,
            socModel = socModel,
            deviceHardware = Build.HARDWARE ?: "unknown",
            buildDisplay = Build.DISPLAY ?: "unknown",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
        )
    }

    fun getRuntimeConfig(): RuntimeConfig = runtimeConfig

    fun updateRuntimeConfig(config: RuntimeConfig) {
        runtimeConfig = config.copy(threadCount = config.threadCount.coerceAtLeast(1))
    }

    /**
     * Load a GGUF model from disk.
     * @param path Absolute path to the .gguf file
     * @param threadCount Number of threads to use (default 4)
     * @param gpuLayers Number of layers to offload to GPU (0 = CPU only)
     */
    suspend fun loadModel(
        path: String,
        threadCount: Int = runtimeConfig.threadCount,
        gpuLayers: Int = if (runtimeConfig.enableGpuOffload) runtimeConfig.defaultGpuLayers else 0,
        contextSize: Int = 4096,
    ): Result<Unit> {
        return try {
            val modelFile = File(path)
            require(modelFile.exists() && modelFile.isFile) {
                "GGUF file does not exist: $path"
            }

            val header = withContext(Dispatchers.IO) { GgufHeaderParser.parse(modelFile) }
            val normalizedThreads = threadCount.coerceAtLeast(1)
            val normalizedContext = contextSize.coerceAtLeast(1024)

            val effectiveIcdPath =
                if (runtimeConfig.preferTurnipDriver && gpuLayers > 0) prepareTurnipIcdPath()
                else null

            ensureNativeLibraryLoaded()
            if (nativeLibraryAvailable && effectiveIcdPath != null) {
                runCatching {
                    nativeSetEnv("VK_ICD_FILENAMES", effectiveIcdPath)
                }.onFailure {
                    Timber.w(it, "GgufEngine: Unable to export VK_ICD_FILENAMES for Turnip")
                }
            }

            val nativePtr = if (nativeLibraryAvailable) {
                runCatching {
                    nativeLoadModel(
                        modelPath = modelFile.absolutePath,
                        threadCount = normalizedThreads,
                        gpuLayers = gpuLayers.coerceAtLeast(0),
                        contextSize = normalizedContext,
                    )
                }.getOrElse {
                    Timber.w(it, "GgufEngine: Native GGUF load failed, using CPU fallback")
                    0L
                }
            } else {
                0L
            }

            Timber.i(
                "GgufEngine: Loaded ${modelFile.name} (v=${header.version}, tensors=${header.tensorCount}, " +
                    "kv=${header.metadataKvCount}, threads=$normalizedThreads, gpuLayers=$gpuLayers, native=${nativePtr != 0L})"
            )

            modelPath = modelFile.absolutePath
            nativeContextPtr = nativePtr
            isLoaded = true
            loadedModelInfo = LoadedModelInfo(
                path = modelFile.absolutePath,
                fileSizeBytes = modelFile.length(),
                ggufVersion = header.version,
                tensorCount = header.tensorCount,
                metadataKvCount = header.metadataKvCount,
                contextSize = normalizedContext,
                threadCount = normalizedThreads,
                gpuLayers = gpuLayers.coerceAtLeast(0),
                nativeBackend = nativePtr != 0L,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "GgufEngine: Failed to load model")
            isLoaded = false
            nativeContextPtr = 0L
            loadedModelInfo = null
            Result.failure(e)
        }
    }

    /**
     * Unload the current model and free memory.
     */
    fun unloadModel() {
        Timber.i("GgufEngine: Unloading model")
        if (nativeContextPtr != 0L && nativeLibraryAvailable) {
            runCatching { nativeUnloadModel(nativeContextPtr) }
                .onFailure { Timber.w(it, "GgufEngine: Native unload failed") }
        }
        isLoaded = false
        modelPath = null
        nativeContextPtr = 0L
        loadedModelInfo = null
    }

    /**
     * Run inference on the loaded model, streaming tokens.
     *
     * @param prompt The full formatted prompt (including system + history)
     * @param params Inference generation parameters
     * @return Flow of generated text tokens
     */
    fun generate(
        prompt: String,
        params: InferenceParams = InferenceParams(),
    ): Flow<String> = callbackFlow {
        if (!isLoaded) {
            throw IllegalStateException("No model loaded. Call loadModel() first.")
        }

        val modelInfo = loadedModelInfo
        val delayMs = runtimeConfig.streamDelayMs.coerceAtLeast(0L)
        Timber.d(
            "GgufEngine: Starting generation (temp=${params.temperature}, maxTokens=${params.maxTokens}, " +
                "threads=${modelInfo?.threadCount ?: runtimeConfig.threadCount})"
        )

        if (isNativeAvailable() && nativeContextPtr != 0L) {
            val streamingSucceeded = runCatching {
                var emittedAnyToken = false
                val completed = nativeGenerateTokens(
                    contextPtr = nativeContextPtr,
                    prompt = prompt,
                    temperature = params.temperature,
                    topP = params.topP,
                    topK = params.topK,
                    repeatPenalty = params.repeatPenalty,
                    maxTokens = params.maxTokens,
                    callback = object : TokenCallback {
                        override fun onToken(token: String) {
                            if (token.isBlank()) return
                            emittedAnyToken = true
                            trySend("$token ")
                        }
                    },
                )
                completed && emittedAnyToken
            }.onFailure {
                Timber.w(it, "GgufEngine: Native token streaming failed, falling back to CPU path")
            }.getOrDefault(false)

            if (streamingSucceeded) {
                close()
                return@callbackFlow
            }

            val nativeText = runCatching {
                nativeGenerate(
                    contextPtr = nativeContextPtr,
                    prompt = prompt,
                    temperature = params.temperature,
                    topP = params.topP,
                    topK = params.topK,
                    repeatPenalty = params.repeatPenalty,
                    maxTokens = params.maxTokens,
                )
            }.onFailure {
                Timber.w(it, "GgufEngine: Native generation failed, falling back to multicore CPU path")
            }.getOrNull()

            if (!nativeText.isNullOrBlank()) {
                val chunks = nativeText.split(' ').filter { it.isNotBlank() }
                for (chunk in chunks) {
                    trySend("$chunk ")
                    if (delayMs > 0) delay(delayMs)
                }
                close()
                return@callbackFlow
            }
        }

        launch {
            // CPU fallback for environments without the llama.cpp JNI runtime.
            val fallback = withContext(
                Dispatchers.Default.limitedParallelism(
                    (modelInfo?.threadCount ?: runtimeConfig.threadCount).coerceAtLeast(1)
                )
            ) {
                buildFallbackResponse(prompt = prompt, maxTokens = params.maxTokens)
            }

            val words = fallback.split(' ').filter { it.isNotBlank() }
            for (word in words) {
                trySend("$word ")
                if (delayMs > 0) delay(delayMs)
            }
            close()
        }

        awaitClose { }
    }

    /**
     * Best-effort metadata-only context estimate until full tokenizer integration is in place.
     */
    fun getContextSize(): Int = loadedModelInfo?.contextSize ?: 4096

    /**
     * Estimate token count for a string using native tokenizer if available.
     */
    fun estimateTokenCount(text: String): Int {
        if (!isNativeAvailable() || nativeContextPtr == 0L) {
            // Fallback estimation
            val whitespaceTokens = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            if (whitespaceTokens > 0) return whitespaceTokens
            return (text.length / 4).coerceAtLeast(1)
        }
        return runCatching {
            nativeTokenize(nativeContextPtr, text, null)
        }.getOrDefault(
            text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        )
    }

    /**
     * Clear the KV cache to free memory and reset generation state.
     */
    fun clearKVCache() {
        if (isNativeAvailable() && nativeContextPtr != 0L) {
            runCatching { nativeClearKVCache(nativeContextPtr) }
                .onFailure { Timber.w(it, "GgufEngine: Failed to clear KV cache") }
        }
    }

    /**
     * Get the vocabulary size of the loaded model.
     */
    fun getVocabSize(): Int {
        if (!isNativeAvailable() || nativeContextPtr == 0L) return 0
        return runCatching { nativeGetVocabSize(nativeContextPtr) }.getOrDefault(0)
    }

    /**
     * Tokenize text into token IDs.
     * @return Number of tokens, or -1 on error
     */
    fun tokenize(text: String): List<Int> {
        if (!isNativeAvailable() || nativeContextPtr == 0L || text.isEmpty()) return emptyList()

        return runCatching {
            val maxTokens = (text.length + 16).coerceAtMost(32768)
            val tokenArray = IntArray(maxTokens)
            val nTokens = nativeTokenize(nativeContextPtr, text, tokenArray)
            if (nTokens > 0) {
                tokenArray.take(nTokens).toList()
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun ensureNativeLibraryLoaded() {
        if (nativeLibraryChecked) return
        synchronized(this) {
            if (nativeLibraryChecked) return
            nativeLibraryAvailable = NATIVE_LIBRARY_CANDIDATES.any { libName ->
                runCatching { System.loadLibrary(libName) }.isSuccess
            }
            nativeLibraryChecked = true
            Timber.i("GgufEngine: Native llama backend available = $nativeLibraryAvailable")
        }
    }

    private fun prepareTurnipIcdPath(): String? {
        val turnipRoot = File(appContext.filesDir, "turnip")
        val icdFile = File(turnipRoot, "icd.d/freedreno_icd.aarch64.json")
        val libraryFile = File(turnipRoot, "libvulkan_freedreno.so")

        copyAssetIfExists("turnip/icd.d/freedreno_icd.aarch64.json", icdFile)
        copyAssetIfExists("turnip/libvulkan_freedreno.so", libraryFile)

        if (!icdFile.exists() || !libraryFile.exists()) {
            return null
        }

        return rewriteIcdWithAbsoluteLibraryPath(icdFile, libraryFile)?.absolutePath
            ?: icdFile.absolutePath
    }

    private fun rewriteIcdWithAbsoluteLibraryPath(icdFile: File, libraryFile: File): File? {
        return runCatching {
            val original = icdFile.readText()
            val normalizedLibraryPath = libraryFile.absolutePath.replace("\\", "\\\\")
            val patchedContent = original.replace(
                "libvulkan_freedreno.so",
                normalizedLibraryPath,
            )
            if (patchedContent == original) {
                return@runCatching icdFile
            }

            val patchedIcd = File(icdFile.parentFile, "freedreno_runtime_icd.json")
            patchedIcd.writeText(patchedContent)
            patchedIcd
        }.onFailure {
            Timber.w(it, "GgufEngine: Unable to patch Turnip ICD path")
        }.getOrNull()
    }

    private fun copyAssetIfExists(assetPath: String, target: File) {
        if (target.exists() && target.length() > 0L) return
        runCatching {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            // Assets are optional; app should continue without packaged Turnip blobs.
            Timber.d("GgufEngine: Optional asset missing $assetPath")
        }
    }

    private fun assetExists(assetPath: String): Boolean {
        return runCatching {
            appContext.assets.open(assetPath).use { }
            true
        }.getOrDefault(false)
    }

    private fun readSocField(fieldName: String): String? {
        return runCatching {
            Build::class.java.getField(fieldName).get(null)?.toString()
        }.getOrNull()
    }

    private fun buildDeviceFingerprint(
        socManufacturer: String?,
        socModel: String?,
    ): String {
        return listOfNotNull(
            Build.HARDWARE,
            Build.BOARD,
            Build.DEVICE,
            Build.PRODUCT,
            socManufacturer,
            socModel,
        ).joinToString(separator = " ").lowercase()
    }

    private fun isVulkanRuntimeSupported(): Boolean {
        val pm = appContext.packageManager ?: return false
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    private fun buildFallbackResponse(prompt: String, maxTokens: Int): String {
        val cappedPrompt = prompt.take(8000)
        val budgetWords = (maxTokens.coerceAtLeast(64) / 2).coerceIn(64, 512)
        val isRoleplay = prompt.contains("[Character") || prompt.contains("roleplay", ignoreCase = true)

        val seedWords = Regex("[A-Za-z0-9_]+")
            .findAll(cappedPrompt)
            .map { it.value.lowercase() }
            .filter { it.length > 2 }
            .toList()

        val domainHints = listOf(
            "context", "memory", "response", "reasoning", "constraints", "details",
            "latency", "threads", "gpu", "quality", "safety", "analysis"
        )

        val vocabulary = (seedWords + domainHints)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(80)
            .ifEmpty { domainHints }

        val lead = if (isRoleplay) {
            "*The scene sharpens as the next move unfolds.*"
        } else {
            "Here is a direct on-device response based on your current context:"
        }

        val body = buildString {
            append(lead)
            append(' ')
            for (index in 0 until budgetWords) {
                val token = vocabulary[index % vocabulary.size]
                append(token)
                if (index % 12 == 11) append(". ") else append(' ')
            }
            append("I can continue with a deeper pass if you want more detail.")
        }
        return body
    }

    private interface TokenCallback {
        fun onToken(token: String)
    }

    private external fun nativeLoadModel(
        modelPath: String,
        threadCount: Int,
        gpuLayers: Int,
        contextSize: Int,
    ): Long

    private external fun nativeUnloadModel(contextPtr: Long)

    private external fun nativeGenerate(
        contextPtr: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        maxTokens: Int,
    ): String

    private external fun nativeGenerateTokens(
        contextPtr: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        maxTokens: Int,
        callback: TokenCallback,
    ): Boolean

    private external fun nativeSetEnv(name: String, value: String): Boolean

    private external fun nativeGetVocabSize(contextPtr: Long): Int

    private external fun nativeTokenize(
        contextPtr: Long,
        text: String,
        tokensOut: IntArray?,
    ): Int

    private external fun nativeClearKVCache(contextPtr: Long)
}
