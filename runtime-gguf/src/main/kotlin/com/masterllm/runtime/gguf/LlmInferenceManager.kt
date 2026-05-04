package com.masterllm.runtime.gguf

import android.content.Context
import android.util.Log
import com.masterllm.core.domain.model.InferenceParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.measureTime

private const val LOGTAG = "[LlmInferenceManager]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

sealed interface LoadStatus {
    data object Started : LoadStatus
    data class Progress(val message: String) : LoadStatus
    data class Loaded(
        val loadDurationMs: Long,
        val replayedMessages: Int,
    ) : LoadStatus
    data class Error(val exception: Throwable) : LoadStatus
    data object Cancelled : LoadStatus
}

sealed interface GenerationStatus {
    data object Started : GenerationStatus
    data class Generating(val partialText: String, val stats: InferencePerformanceTracker.LiveStats?) : GenerationStatus
    data class Complete(
        val finalText: String,
        val generationSpeedTps: Float,
        val generationTimeSecs: Int,
        val contextLengthUsed: Int,
        val stats: InferencePerformanceTracker.LiveStats?,
    ) : GenerationStatus
    data class Error(val exception: Throwable) : GenerationStatus
    data object Cancelled : GenerationStatus
}

@Singleton
class LlmInferenceManager @Inject constructor(
    private val ggufEngine: GgufEngine,
    @ApplicationContext private val appContext: Context,
) {
    private val stateMutex = Mutex()
    private val perfTracker = InferencePerformanceTracker(appContext)

    val isModelLoaded = AtomicBoolean(false)

    @Volatile
    var isInferenceRunning = false
        private set

    @Volatile
    private var generationJob: Job? = null

    @Volatile
    private var loadJob: Job? = null

    @Volatile
    private var benchmarkJob: Job? = null

    private var currentModelPath: String? = null
    private var currentParams: InferenceParams? = null

    private val _liveStats = MutableStateFlow(
        InferencePerformanceTracker.LiveStats(
            tokensPerSecond = 0f,
            tokensGenerated = 0,
            contextUsed = 0,
            contextMax = 0,
            memoryUsedMB = 0,
            memoryMaxMB = 0,
            cpuUsagePercent = 0f,
            gpuUsagePercent = null,
            cpuFreqMHz = emptyMap(),
            gpuFreqMHz = null,
            thermalZoneCelsius = emptyMap(),
            firstTokenLatencyMs = 0L,
            elapsedMs = 0L,
        )
    )
    val liveStats: StateFlow<InferencePerformanceTracker.LiveStats> = _liveStats.asStateFlow()

    fun load(
        modelPath: String,
        params: InferenceParams = InferenceParams(),
        gpuAccelerationEnabled: Boolean = false,
        gpuOffloadLayers: Int? = null,
        conversationHistory: List<Pair<String, String>> = emptyList(),
    ): Flow<LoadStatus> = flow {
        stateMutex.withLock {
            loadJob?.cancel()
            isModelLoaded.set(false)

            try {
                emit(LoadStatus.Started)
                emit(LoadStatus.Progress("Loading model from disk..."))

                val startNs = System.nanoTime()

                if (ggufEngine.isModelLoaded()) {
                    emit(LoadStatus.Progress("Closing previous model..."))
                    ggufEngine.close()
                }

                ggufEngine.load(
                    modelPath = modelPath,
                    params = params,
                    gpuAccelerationEnabled = gpuAccelerationEnabled,
                    gpuOffloadLayers = gpuOffloadLayers,
                )

                val loadDurationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)

                emit(LoadStatus.Progress("Replaying conversation history..."))
                var replayedCount = 0
                for ((role, content) in conversationHistory) {
                    if (content.isBlank()) continue
                    when (role.lowercase()) {
                        "system" -> ggufEngine.addSystemPrompt(content)
                        "user" -> ggufEngine.addUserMessage(content)
                        "assistant" -> ggufEngine.addAssistantMessage(content)
                    }
                    replayedCount += 1
                }

                currentModelPath = modelPath
                currentParams = params
                isModelLoaded.set(true)

                emit(
                    LoadStatus.Loaded(
                        loadDurationMs = loadDurationMs,
                        replayedMessages = replayedCount,
                    )
                )
            } catch (e: CancellationException) {
                emit(LoadStatus.Cancelled)
            } catch (e: Exception) {
                LOGD("Error loading model: ${e.message}")
                emit(LoadStatus.Error(e))
            }
        }
    }.flowOn(Dispatchers.Default)

    fun generate(
        prompt: String,
        maxTokens: Int = GgufEngine.DEFAULT_MAX_TOKENS,
        onToken: (String) -> Unit = {},
    ): Flow<GenerationStatus> = flow {
        stateMutex.withLock {
            if (!isModelLoaded.get()) {
                emit(GenerationStatus.Error(IllegalStateException("Model not loaded")))
                return@flow
            }
            generationJob?.cancel()
            isInferenceRunning = true
        }

        try {
            emit(GenerationStatus.Started)

            val response = StringBuilder()
            perfTracker.startTracking()
            perfTracker.updateContextUsage(
                used = ggufEngine.getContextLengthUsed(),
                max = currentParams?.contextSize ?: GgufEngine.DEFAULT_CONTEXT_SIZE.toInt(),
            )

            var finalStats: InferencePerformanceTracker.LiveStats? = null
            val duration = measureTime {
                ggufEngine.getResponseAsFlow(prompt, maxTokens).collect { piece ->
                    response.append(piece)
                    perfTracker.recordToken()
                    perfTracker.updateContextUsage(
                        used = ggufEngine.getContextLengthUsed(),
                        max = currentParams?.contextSize ?: GgufEngine.DEFAULT_CONTEXT_SIZE.toInt(),
                    )

                    val snapshot = perfTracker.getSnapshot()
                    finalStats = snapshot
                    _liveStats.value = snapshot

                    onToken(piece)

                    emit(
                        GenerationStatus.Generating(
                            partialText = response.toString(),
                            stats = snapshot,
                        )
                    )
                }
            }

            finalStats = perfTracker.stopTracking()
            _liveStats.value = finalStats ?: _liveStats.value

            isInferenceRunning = false

            emit(
                GenerationStatus.Complete(
                    finalText = response.toString(),
                    generationSpeedTps = ggufEngine.getFinalTokensPerSecond(),
                    generationTimeSecs = duration.inWholeSeconds.toInt(),
                    contextLengthUsed = ggufEngine.getContextLengthUsed(),
                    stats = finalStats,
                )
            )
        } catch (e: CancellationException) {
            isInferenceRunning = false
            emit(GenerationStatus.Cancelled)
        } catch (e: Exception) {
            isInferenceRunning = false
            LOGD("Error generating: ${e.message}")
            emit(GenerationStatus.Error(e))
        }
    }.flowOn(Dispatchers.Default)

    fun cancelGeneration() {
        generationJob?.cancel()
        isInferenceRunning = false
    }

    fun benchmark(
        promptTokens: Int = 512,
        genTokens: Int = 128,
        parallel: Int = 1,
        repetitions: Int = 1,
        onResult: (String) -> Unit = {},
    ) {
        benchmarkJob?.cancel()
        benchmarkJob = CoroutineScope(Dispatchers.Default).launch {
            stateMutex.withLock {
                try {
                    if (!isModelLoaded.get()) {
                        withContext(Dispatchers.Main) { onResult("Error: Model not loaded") }
                        return@withLock
                    }
                    val result = ggufEngine.benchModel(promptTokens, genTokens, parallel, repetitions).trim()
                    withContext(Dispatchers.Main) { onResult(result) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onResult("Benchmark failed: ${e.message}") }
                }
            }
        }
    }

    suspend fun close() {
        generationJob?.cancel()
        loadJob?.cancel()
        benchmarkJob?.cancel()

        generationJob = null
        loadJob = null
        benchmarkJob = null

        isModelLoaded.set(false)
        isInferenceRunning = false
        currentModelPath = null
        currentParams = null

        try {
            ggufEngine.close()
        } catch (e: Exception) {
            LOGD("Error closing engine: ${e.message}")
        }
    }

    fun getContextLengthUsed(): Int {
        return if (isModelLoaded.get()) {
            ggufEngine.getContextLengthUsed()
        } else {
            0
        }
    }

    fun getTokensPerSecond(): Float {
        return if (isInferenceRunning) {
            ggufEngine.getLiveTokensPerSecond()
        } else {
            ggufEngine.getFinalTokensPerSecond()
        }
    }

    fun getLiveStats(): InferencePerformanceTracker.LiveStats {
        return _liveStats.value
    }

    fun getArchitectureInfo(): String {
        return GgufEngine.detectArchitecture()
    }

    fun getOptimalThreadCount(): Int {
        return GgufEngine.getOptimalThreadCount()
    }

    fun getLoadedLibraryName(): String {
        return GgufEngine.getLoadedNativeLibraryName()
    }
}
