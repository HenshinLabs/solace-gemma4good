package com.masterllm.runtime.gguf

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GgufEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    companion object {
        @Volatile
        private var loadedNativeLibrary: String = "unloaded"

        init {
            val logTag = GgufEngine::class.java.simpleName

            // Check CPU features and load appropriate library
            val cpuFeatures = getCPUFeatures()
            val hasFp16 = cpuFeatures.contains("fp16") || cpuFeatures.contains("fphp")
            val hasDotProd = cpuFeatures.contains("dotprod") || cpuFeatures.contains("asimddp")
            val hasSve = cpuFeatures.contains("sve")
            val hasI8mm = cpuFeatures.contains("i8mm")
            val isAtLeastArmV82 = cpuFeatures.contains("asimd") &&
                cpuFeatures.contains("crc32") &&
                cpuFeatures.contains("aes")
            val isAtLeastArmV84 = cpuFeatures.contains("dcpop") &&
                cpuFeatures.contains("uscat")

            Log.d(logTag, "CPU features: $cpuFeatures")

            // Check if running on emulator
            val isEmulated = Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu")

            Log.d(logTag, "isEmulated: $isEmulated")

            val candidateLibraries = mutableListOf<String>()
            if (!isEmulated) {
                if (supportsArm64V8a()) {
                    when {
                        isAtLeastArmV84 && hasSve && hasI8mm && hasFp16 && hasDotProd ->
                            candidateLibraries += "llama_android_v8_4_fp16_dotprod_i8mm_sve"
                        isAtLeastArmV84 && hasSve && hasFp16 && hasDotProd ->
                            candidateLibraries += "llama_android_v8_4_fp16_dotprod_sve"
                        isAtLeastArmV84 && hasI8mm && hasFp16 && hasDotProd ->
                            candidateLibraries += "llama_android_v8_4_fp16_dotprod_i8mm"
                        isAtLeastArmV84 && hasFp16 && hasDotProd ->
                            candidateLibraries += "llama_android_v8_4_fp16_dotprod"
                        isAtLeastArmV82 && hasFp16 && hasDotProd ->
                            candidateLibraries += "llama_android_v8_2_fp16_dotprod"
                        isAtLeastArmV82 && hasFp16 ->
                            candidateLibraries += "llama_android_v8_2_fp16"
                    }
                }
            }
            candidateLibraries += "llama_android"

            var lastError: Throwable? = null
            for (library in candidateLibraries.distinct()) {
                try {
                    Log.d(logTag, "Trying to load lib$library.so")
                    System.loadLibrary(library)
                    loadedNativeLibrary = library
                    Log.i(logTag, "Loaded native library: $library")
                    lastError = null
                    break
                } catch (error: UnsatisfiedLinkError) {
                    lastError = error
                    Log.w(logTag, "Native library unavailable: $library")
                }
            }

            if (lastError != null) {
                throw UnsatisfiedLinkError(
                    "Unable to load GGUF native runtime. Tried: ${candidateLibraries.distinct().joinToString()}"
                )
            }
        }

        private fun getCPUFeatures(): String {
            return try {
                File("/proc/cpuinfo").readText()
                    .substringAfter("Features")
                    .substringAfter(":")
                    .substringBefore("\n")
                    .trim()
            } catch (e: FileNotFoundException) {
                ""
            }
        }
        
        private fun supportsArm64V8a(): Boolean = 
            Build.SUPPORTED_ABIS[0]?.equals("arm64-v8a") == true

        const val DEFAULT_CONTEXT_SIZE: Long = 2048L
        const val DEFAULT_CHAT_TEMPLATE = "{% for message in messages %}{% if loop.first and messages[0]['role'] != 'system' %}{{ '<|im_start|>system\\nYou are a helpful AI assistant.\\n<|im_end|>\\n' }}{% endif %}{{'<|im_start|>' + message['role'] + '\\n' + message['content'] + '<|im_end|>\\n'}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n' }}{% endif %}"

        fun getLoadedNativeLibraryName(): String = loadedNativeLibrary
    }
    
    private var nativePtr = 0L
    private var isLoaded = false
    
    /**
     * Loads a GGUF model from the given path.
     * 
     * @param modelPath The path to the GGUF model file
     * @param params Inference parameters from core-domain
     * @return Result indicating success or failure
     */
    suspend fun load(
        modelPath: String, 
        params: com.masterllm.core.domain.model.InferenceParams = com.masterllm.core.domain.model.InferenceParams()
    ) = withContext(Dispatchers.IO) {
        val actualContextSize = params.contextSize?.toLong()?.coerceAtLeast(512L) ?: DEFAULT_CONTEXT_SIZE
        val actualChatTemplate = params.chatTemplate?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_TEMPLATE
        
        nativePtr = loadModel(
            modelPath,
            params.minP,
            params.temperature,
            params.storeChats,
            actualContextSize,
            actualChatTemplate,
            params.numThreads.coerceAtLeast(1),
            params.useMmap,
            params.useMlock,
        )
        
        isLoaded = nativePtr != 0L
        
        if (isLoaded) {
            Log.i("GgufEngine", "Model loaded successfully: $modelPath")
        } else {
            throw IllegalStateException("Failed to load model")
        }
    }
    
    /**
     * Adds a user message to the conversation history.
     */
    fun addUserMessage(message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, "user")
    }
    
    /**
     * Adds a system prompt to the conversation.
     */
    fun addSystemPrompt(prompt: String) {
        verifyHandle()
        addChatMessage(nativePtr, prompt, "system")
    }
    
    /**
     * Adds an assistant message to the conversation.
     */
    fun addAssistantMessage(message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, "assistant")
    }
    
    /**
     * Returns the response generation speed in tokens per second.
     */
    fun getResponseGenerationSpeed(): Float {
        verifyHandle()
        return getResponseGenerationSpeed(nativePtr)
    }
    
    /**
     * Returns the number of tokens used in the current context.
     */
    fun getContextLengthUsed(): Int {
        verifyHandle()
        return getContextSizeUsed(nativePtr)
    }
    
    /**
     * Generates a response to the given query as a Flow of strings.
     * The flow emits each piece of the response as it's generated.
     * The special token "[EOG]" indicates end of generation.
     * 
     * @param query The user's query/prompt
     * @return Flow of response pieces
     */
    fun getResponseAsFlow(query: String): Flow<String> = flow {
        verifyHandle()
        startCompletion(nativePtr, query)

        try {
            while (currentCoroutineContext().isActive) {
                val piece = completionLoop(nativePtr)
                when (piece) {
                    "[EOG]", "[STOP]", "[ERROR]" -> break
                    else -> if (piece.isNotEmpty()) emit(piece)
                }
            }
        } finally {
            stopCompletion(nativePtr)
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Generates a response to the given query as a complete string.
     * This is a blocking call that returns the full response.
     * 
     * @param query The user's query/prompt
     * @return The complete response from the model
     */
    fun getResponse(query: String): String {
        verifyHandle()
        startCompletion(nativePtr, query)

        val response = StringBuilder()

        try {
            var piece = completionLoop(nativePtr)

            while (piece != "[EOG]" && piece != "[STOP]" && piece != "[ERROR]") {
                response.append(piece)
                piece = completionLoop(nativePtr)
            }
        } finally {
            stopCompletion(nativePtr)
        }

        return response.toString()
    }
    
    /**
     * Runs a benchmark on the model.
     * 
     * @param pp Number of prompt tokens
     * @param tg Number of tokens to generate
     * @param pl Number of tokens to preload
     * @param nr Number of repetitions
     * @return Benchmark results as a string
     */
    fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String {
        verifyHandle()
        return benchModel(nativePtr, pp, tg, pl, nr)
    }
    
    /**
     * Unloads the model and releases resources.
     */
    fun close() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0L
            isLoaded = false
        }
    }
    
    /**
     * Returns true if a model is currently loaded.
     */
    fun isModelLoaded(): Boolean = isLoaded && nativePtr != 0L
    
    private fun verifyHandle() {
        check(nativePtr != 0L) { "Model is not loaded. Call load() first." }
    }
    
    // Native methods
    private external fun loadModel(
        modelPath: String,
        minP: Float,
        temperature: Float,
        storeChats: Boolean,
        contextSize: Long,
        chatTemplate: String,
        nThreads: Int,
        useMmap: Boolean,
        useMlock: Boolean,
    ): Long
    
    private external fun addChatMessage(
        modelPtr: Long,
        message: String,
        role: String,
    )
    
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun completionLoop(modelPtr: Long): String
    private external fun stopCompletion(modelPtr: Long)
    private external fun benchModel(
        modelPtr: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int,
    ): String
}
