# Solace Android App — API Reference

> Comprehensive API documentation for all key classes, their constructors, methods, properties, and usage across the Solace Android application.

---

## Table of Contents

1. [GgufEngine](#ggufengine)
2. [GgufRuntimeCoordinator](#ggufruntimecoordinator)
3. [InferencePerformanceTracker](#inferenceperformancetracker)
4. [PerformanceUsageSampler](#performanceusagesampler)
5. [ChatViewModel](#chatviewmodel)
6. [VoskSpeechManager](#voskspeechmanager)
7. [KittenTtsEngine](#kittenttsengine)
8. [TtsTextFilter](#ttstextfilter)
9. [ToolRegistry](#toolregistry)
10. [OpenClawEngine](#openclawengine)
11. [ModelDownloadManager](#modeldownloadmanager)
12. [BundledModelManager](#bundledmodelmanager)
13. [RoleplayViewModel](#roleplayviewmodel)
14. [Domain Models](#domain-models)

---

## GgufEngine

**Module:** `runtime-gguf`
**Package:** `com.masterllm.runtime.gguf`
**File:** `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/GgufEngine.kt`

Singleton Hilt-injected engine that wraps the llama.cpp native runtime for GGUF model inference on Android. Handles model loading, chat message management, token generation, multimodal vision, benchmarking, and performance telemetry.

### Constructor

```kotlin
@Singleton
class GgufEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
)
```

Injected via Hilt. The native library is loaded automatically in the `companion object` `init` block based on detected CPU features (ARM v8.2, v8.4, FP16, dotprod, i8mm, SVE).

### Companion Object

| Member | Type | Description |
|---|---|---|
| `isAvailable()` | `fun(): Boolean` | Returns `true` if the native library loaded successfully and no init error occurred. |
| `getInitError()` | `fun(): String?` | Returns the error message if native library loading failed, or `null` on success. |
| `getLoadedNativeLibraryName()` | `fun(): String` | Returns the name of the loaded native library (e.g., `"llama_android_v8_2_fp16_dotprod"`). Returns `"unloaded"` if none loaded. |
| `getOptimalThreadCount()` | `fun(): Int` | Returns the optimal number of threads for inference, clamped to `[1, 16]` based on `Runtime.getRuntime().availableProcessors()`. |
| `getPerformanceBatchSize()` | `fun(): Int` | Returns the recommended `nBatch` size based on thread count: 1024 (>=12 threads), 512 (>=8), 256 (>=4), else 128. |
| `getPerformanceUbatchSize()` | `fun(): Int` | Returns the recommended `nUbatch` size based on thread count: 512 (>=12 threads), 256 (>=8), 128 (>=4), else 64. |
| `detectArchitecture()` | `fun(): String` | Reads `/proc/cpuinfo` and returns a human-readable string describing the CPU architecture, performance/efficiency core counts, and chipset vendor (Qualcomm Kryo, MediaTek Dimensity, or generic ARM). |
| `DEFAULT_CONTEXT_SIZE` | `const val Long = 16384L` | Default context window size (16K tokens). |
| `DEFAULT_MAX_TOKENS` | `const val Int = 4096` | Default maximum tokens to generate per response. |
| `DEFAULT_CHAT_TEMPLATE` | `const val String` | Generic ChatML fallback template for non-Gemma models. |
| `GEMMA4_CHAT_TEMPLATE` | `const val String` | Gemma 4 chat template using turn delimiters. Used as fallback when GGUF metadata template is missing. |
| `CoreType` | `enum` | `PERFORMANCE`, `EFFICIENCY`, `UNKNOWN` — used internally for CPU core classification. |

### Instance Methods — Model Lifecycle

#### `load`

```kotlin
suspend fun load(
    modelPath: String,
    params: InferenceParams = InferenceParams(),
    gpuAccelerationEnabled: Boolean = false,
    gpuOffloadLayers: Int? = null,
)
```

Loads a GGUF model from disk into native memory. This is a `suspend` function that runs on `Dispatchers.IO` and acquires the native operations mutex.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `modelPath` | `String` | — | Absolute path to the `.gguf` file. |
| `params` | `InferenceParams` | `InferenceParams()` | Inference configuration (temperature, topP, topK, context size, threads, etc.). |
| `gpuAccelerationEnabled` | `Boolean` | `false` | Whether to offload layers to GPU. |
| `gpuOffloadLayers` | `Int?` | `null` | Number of layers to offload. `null` or `99` means offload all possible layers. |

**Behavior:**
- Reads GGUF metadata via `GGUFReader` to extract context size and chat template.
- Resolves actual values from `params`, falling back to GGUF metadata, then to companion defaults.
- Auto-selects thread count if `params.numThreads <= 0`.
- Closes any previously loaded model before loading the new one.
- Throws `IllegalStateException` if the native load returns a null pointer.

**Usage:**
```kotlin
ggufEngine.load(
    modelPath = "/storage/emulated/0/Android/data/.../models/model.gguf",
    params = InferenceParams(temperature = 0.7f, contextSize = 8192),
    gpuAccelerationEnabled = true,
    gpuOffloadLayers = 28,
)
```

#### `close`

```kotlin
suspend fun close()
```

Unloads the model and releases native resources. If generation is active, sets `closeRequested` flag and returns immediately; the model is freed after generation completes.

#### `isModelLoaded`

```kotlin
fun isModelLoaded(): Boolean
```

Returns `true` if a model is currently loaded in native memory (`nativePtr != 0L`).

### Instance Methods — Chat Messages

#### `addSystemPrompt`

```kotlin
fun addSystemPrompt(prompt: String)
```

Adds a system prompt message to the native conversation context. Must be called after `load()`.

#### `addUserMessage`

```kotlin
fun addUserMessage(message: String)
```

Adds a user message to the native conversation context. Must be called after `load()`.

#### `addAssistantMessage`

```kotlin
fun addAssistantMessage(message: String)
```

Adds an assistant message to the native conversation context. Used for replaying conversation history. Must be called after `load()`.

### Instance Methods — Text Generation

#### `getResponseAsFlow`

```kotlin
fun getResponseAsFlow(
    query: String,
    maxTokens: Int = DEFAULT_MAX_TOKENS,
): Flow<String>
```

Generates a response as a cold `Flow` that emits individual token pieces. Runs on `Dispatchers.Default`.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `query` | `String` | — | The user query/prompt to generate a response for. |
| `maxTokens` | `Int` | `4096` | Maximum number of tokens to generate. |

**Returns:** `Flow<String>` — Each emission is a token piece (word fragment). The flow completes when generation ends naturally (`[EOG]`, `[STOP]`, `[ERROR]`), `maxTokens` is reached, or the coroutine is cancelled.

**Special tokens emitted as flow completion signals:**
- `"[EOG]"` — End of generation (model finished naturally).
- `"[STOP]"` — Generation was stopped.
- `"[ERROR]"` — An error occurred during generation.

**Usage:**
```kotlin
ggufEngine.getResponseAsFlow("Explain quantum computing", maxTokens = 2048)
    .collect { piece ->
        appendToUi(piece)
    }
```

#### `getResponse`

```kotlin
suspend fun getResponse(
    query: String,
    maxTokens: Int = DEFAULT_MAX_TOKENS,
): String
```

Generates a complete response as a single string. This is a blocking suspend function that collects all tokens internally.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `query` | `String` | — | The user query/prompt. |
| `maxTokens` | `Int` | `4096` | Maximum tokens to generate. |

**Returns:** `String` — The complete generated response.

**Usage:**
```kotlin
val response = ggufEngine.getResponse("What is the capital of France?")
```

### Instance Methods — Multimodal Vision

#### `loadMmproj`

```kotlin
fun loadMmproj(mmprojPath: String): Boolean
```

Loads a multimodal projector (mmproj) GGUF file for vision support. Must be called after `load()`.

| Parameter | Type | Description |
|---|---|---|
| `mmprojPath` | `String` | Absolute path to the mmproj `.gguf` file. |

**Returns:** `Boolean` — `true` if the mmproj was loaded successfully.

#### `supportsVision`

```kotlin
fun supportsVision(): Boolean
```

Returns `true` if the currently loaded model has vision/multimodal support (mmproj loaded).

#### `startCompletionWithImage`

```kotlin
fun startCompletionWithImage(
    prompt: String,
    imageData: ByteArray,
    width: Int,
    height: Int,
)
```

Starts a multimodal completion with an image input. The image must be raw RGB bytes (`width * height * 3`).

| Parameter | Type | Description |
|---|---|---|
| `prompt` | `String` | The text prompt accompanying the image. |
| `imageData` | `ByteArray` | Raw RGB pixel data (no header, `width * height * 3` bytes). |
| `width` | `Int` | Image width in pixels. |
| `height` | `Int` | Image height in pixels. |

**Usage:**
```kotlin
val bitmap = BitmapFactory.decodeFile(imagePath)
val rgbBytes = bitmapToRgbBytes(bitmap)
ggufEngine.startCompletionWithImage("Describe this image", rgbBytes, bitmap.width, bitmap.height)
```

#### `completionLoop` (instance)

```kotlin
fun completionLoop(): String
```

Runs one iteration of the token generation loop (multimodal mode). Returns the generated token piece, or `"[EOG]"` when generation is complete.

#### `stopCompletion` (instance)

```kotlin
fun stopCompletion()
```

Stops the current completion and saves the assistant message to the native conversation context.

### Instance Methods — Benchmarking

#### `benchModel`

```kotlin
fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
```

Runs a benchmark on the loaded model.

| Parameter | Type | Description |
|---|---|---|
| `pp` | `Int` | Number of prompt processing tokens. |
| `tg` | `Int` | Number of tokens to generate (text generation). |
| `pl` | `Int` | Number of tokens to preload. |
| `nr` | `Int` | Number of repetitions. |

**Returns:** `String` — Benchmark results including tokens/second metrics.

**Usage:**
```kotlin
val result = ggufEngine.benchModel(pp = 512, tg = 128, pl = 1, nr = 1)
```

### Instance Methods — Performance Telemetry

#### `getLiveTokensPerSecond`

```kotlin
fun getLiveTokensPerSecond(): Float
```

Returns the real-time generation speed in tokens/second based on wall-clock time since generation started.

#### `getFinalTokensPerSecond`

```kotlin
fun getFinalTokensPerSecond(): Float
```

Returns the final generation speed in tokens/second. If generation is still active, uses current time; otherwise uses the recorded end time.

#### `getTokensGenerated`

```kotlin
fun getTokensGenerated(): Int
```

Returns the number of tokens generated so far in the current/last generation.

#### `getGenerationElapsedMs`

```kotlin
fun getGenerationElapsedMs(): Long
```

Returns the elapsed time in milliseconds since generation started.

#### `getResponseGenerationSpeed`

```kotlin
fun getResponseGenerationSpeed(): Float
```

Returns the native response generation speed in tokens/second (from llama.cpp internals).

#### `getPromptProcessingSpeed`

```kotlin
fun getPromptProcessingSpeed(): Float
```

Returns the native prompt processing speed in tokens/second (from llama.cpp internals).

#### `getConfiguredThreadCount`

```kotlin
fun getConfiguredThreadCount(): Int
```

Returns the thread count configured in the native llama.cpp context.

#### `getConfiguredGpuLayers`

```kotlin
fun getConfiguredGpuLayers(): Int
```

Returns the number of GPU offload layers configured in the native context.

#### `getContextLengthUsed`

```kotlin
fun getContextLengthUsed(): Int
```

Returns the number of tokens currently used in the context window.

---

## GgufRuntimeCoordinator

**Module:** `runtime-gguf`
**Package:** `com.masterllm.runtime.gguf`
**File:** `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/GgufRuntimeCoordinator.kt`

Singleton coordinator that serializes access to the shared `GgufEngine` across features (Chat, Roleplay). Prevents concurrent close/load/generation races.

```kotlin
@Singleton
class GgufRuntimeCoordinator @Inject constructor()
```

### Methods

#### `withEngineLock`

```kotlin
suspend fun <T> withEngineLock(action: suspend () -> T): T
```

Executes the given `action` while holding the engine mutex. All access to `GgufEngine` load/close/generation operations must go through this method.

**Usage:**
```kotlin
runtimeCoordinator.withEngineLock {
    ggufEngine.load(modelPath, params)
    ggufEngine.getResponseAsFlow(query).collect { ... }
}
```

---

## InferencePerformanceTracker

**Module:** `runtime-gguf`
**Package:** `com.masterllm.runtime.gguf`
**File:** `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/InferencePerformanceTracker.kt`

Tracks real-time inference performance metrics including tokens/second, memory usage, CPU/GPU utilization, CPU frequencies, GPU frequency, and thermal zones.

```kotlin
class InferencePerformanceTracker(private val context: Context)
```

### Data Classes

#### `LiveStats`

```kotlin
data class LiveStats(
    val tokensPerSecond: Float,
    val tokensGenerated: Int,
    val contextUsed: Int,
    val contextMax: Int,
    val memoryUsedMB: Int,
    val memoryMaxMB: Int,
    val cpuUsagePercent: Float,
    val gpuUsagePercent: Float?,
    val cpuFreqMHz: Map<Int, Long>,
    val gpuFreqMHz: Long?,
    val thermalZoneCelsius: Map<String, Float>,
    val firstTokenLatencyMs: Long,
    val elapsedMs: Long,
)
```

### Methods

#### `startTracking`

```kotlin
fun startTracking()
```

Resets all counters and begins a new tracking session. Call before starting generation.

#### `recordToken`

```kotlin
fun recordToken()
```

Increments the token counter. Call once per generated token. Records first-token timestamp automatically.

#### `updateContextUsage`

```kotlin
fun updateContextUsage(used: Int, max: Int)
```

Updates the context window usage metrics.

#### `getSnapshot`

```kotlin
fun getSnapshot(): LiveStats
```

Returns a point-in-time snapshot of all performance metrics. Reads CPU/GPU frequencies, thermal zones, and memory usage from sysfs.

#### `stopTracking`

```kotlin
fun stopTracking(): LiveStats
```

Stops tracking and returns the final snapshot.

---

## PerformanceUsageSampler

**Module:** `runtime-gguf`
**Package:** `com.masterllm.runtime.gguf`
**File:** `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/PerformanceUsageSampler.kt`

Lightweight singleton utility for sampling process-level CPU and GPU usage between two points in time.

```kotlin
object PerformanceUsageSampler
```

### Data Classes

#### `Snapshot`

```kotlin
data class Snapshot(
    val processCpuTicks: Long,
    val totalCpuTicks: Long,
    val gpuBusyTicks: Long?,
    val gpuTotalTicks: Long?,
    val gpuInstantPercent: Double?,
)
```

#### `UsagePercent`

```kotlin
data class UsagePercent(
    val cpuPercent: Double,
    val gpuPercent: Double?,
)
```

### Methods

#### `captureSnapshot`

```kotlin
fun captureSnapshot(): Snapshot
```

Captures the current CPU/GPU tick counters. CPU ticks are read from `/proc/self/stat` and `/proc/stat`. GPU ticks are read from Qualcomm KGSL sysfs when available.

#### `computeUsage`

```kotlin
fun computeUsage(start: Snapshot, end: Snapshot): UsagePercent
```

Computes the CPU and GPU usage percentages between two snapshots. Uses delta-based calculation for CPU; supports both KGSL counter-based and instantaneous GPU utilization.

**Usage:**
```kotlin
val start = PerformanceUsageSampler.captureSnapshot()
// ... run inference ...
val end = PerformanceUsageSampler.captureSnapshot()
val usage = PerformanceUsageSampler.computeUsage(start, end)
println("CPU: ${usage.cpuPercent}%, GPU: ${usage.gpuPercent}%")
```

---

## ChatViewModel

**Module:** `feature-chat`
**Package:** `com.masterllm.feature.chat`
**File:** `feature-chat/src/main/kotlin/com/masterllm/feature/chat/ChatViewModel.kt`

HiltViewModel that manages the chat screen state, conversation lifecycle, model loading, text/multimodal inference, ASR, TTS, and web search.

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val ggufEngine: GgufEngine,
    private val runtimeCoordinator: GgufRuntimeCoordinator,
    private val imageGenEngine: ImageGenEngine,
    private val safetensorsEngine: SafetensorsEngine,
    private val ollamaApiService: OllamaApiService,
    @ApplicationContext private val appContext: Context,
) : ViewModel()
```

### State

#### `uiState`

```kotlin
val uiState: StateFlow<ChatUiState>
```

Observable UI state for the chat screen.

#### `ChatUiState`

```kotlin
data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val currentConversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val availableModels: List<LlmModel> = emptyList(),
    val selectedModelId: String? = null,
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val generationStatus: String? = null,
    val showConversationList: Boolean = true,
    val error: String? = null,
    val lastGenerationStats: GenerationStats? = null,
    val inferenceParams: InferenceParams = InferenceParams(),
    val showModelConfig: Boolean = false,
    val selectedModelInfo: LlmModel? = null,
    val modelRuntime: ModelRuntimeInfo = ModelRuntimeInfo(),
    val benchmarkRunning: Boolean = false,
    val benchmarkResult: String? = null,
    val chatBackend: ChatBackend = ChatBackend.LOCAL,
    val ollamaConnected: Boolean = false,
    val ollamaModels: List<OllamaModelInfo> = emptyList(),
    val ollamaSelectedModel: String = "",
    val pendingImageAttachment: String? = null,
    val asrState: VoskSpeechManager.AsrState = VoskSpeechManager.AsrState(),
    val ttsEnabled: Boolean = true,
    val isSpeaking: Boolean = false,
    val voskModelReady: Boolean = false,
)
```

| Property | Type | Description |
|---|---|---|
| `conversations` | `List<Conversation>` | All saved chat conversations. |
| `currentConversation` | `Conversation?` | The currently active/open conversation. |
| `messages` | `List<Message>` | Messages in the current conversation. |
| `availableModels` | `List<LlmModel>` | Downloaded models available for selection. |
| `selectedModelId` | `String?` | ID of the currently selected model. |
| `inputText` | `String` | Current text in the input field. |
| `isGenerating` | `Boolean` | Whether the model is currently generating a response. |
| `streamingText` | `String` | Accumulated text from the current streaming generation. |
| `generationStatus` | `String?` | Human-readable status message during generation. |
| `error` | `String?` | Current error message to display. |
| `lastGenerationStats` | `GenerationStats?` | Performance stats from the last completed generation. |
| `inferenceParams` | `InferenceParams` | Current inference parameter configuration. |
| `chatBackend` | `ChatBackend` | Active backend: `LOCAL` or `OLLAMA`. |
| `pendingImageAttachment` | `String?` | File path of an image attached to the next message. |
| `asrState` | `VoskSpeechManager.AsrState` | Current speech recognition state. |
| `ttsEnabled` | `Boolean` | Whether text-to-speech playback is enabled. |
| `isSpeaking` | `Boolean` | Whether TTS is currently playing audio. |
| `voskModelReady` | `Boolean` | Whether the Vosk ASR model is loaded. |

#### `ChatBackend`

```kotlin
enum class ChatBackend { LOCAL, OLLAMA }
```

#### `ModelLoadStatus`

```kotlin
enum class ModelLoadStatus { IDLE, LOADING, LOADED, ERROR }
```

#### `ModelRuntimeInfo`

```kotlin
data class ModelRuntimeInfo(
    val modelId: String? = null,
    val modelDisplayName: String = "No model selected",
    val status: ModelLoadStatus = ModelLoadStatus.IDLE,
    val backend: String = "Not loaded",
    val modelPath: String? = null,
    val loadDurationMs: Long? = null,
    val threadCount: Int = 4,
    val contextSize: Int = 2048,
    val gpuAccelerationEnabled: Boolean = false,
    val gpuOffloadLayers: Int = 0,
    val cpuExecutionEnabled: Boolean = true,
    val offloadSummary: String = "CPU execution",
    val note: String = "Select a model to preload.",
    val lastError: String? = null,
)
```

#### `GenerationStats`

```kotlin
data class GenerationStats(
    val modelDisplayName: String,
    val backend: String,
    val modelLoadDurationMs: Long,
    val replayedMessages: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val generatedChars: Int,
    val firstTokenLatencyMs: Long,
    val durationMs: Long,
    val promptTokensPerSecond: Double,
    val nativePromptTokensPerSecond: Float,
    val decodeTokensPerSecond: Double,
    val nativeTokensPerSecond: Float,
    val promptCpuUsagePercent: Double,
    val decodeCpuUsagePercent: Double,
    val promptGpuUsagePercent: Double?,
    val decodeGpuUsagePercent: Double?,
    val threadCount: Int,
    val gpuLayers: Int,
    val contextSize: Int,
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
)
```

### Actions

#### `ChatAction`

Sealed interface representing all user-initiated actions on the chat screen.

| Action | Type | Description |
|---|---|---|
| `InputChanged(text: String)` | `data class` | Updates the input text field. |
| `SendMessage` | `data object` | Sends the current input text as a message. |
| `StopGeneration` | `data object` | Stops the currently running generation. |
| `SelectConversation(id: String)` | `data class` | Opens an existing conversation by ID. |
| `NewConversation` | `data object` | Creates a new conversation. |
| `DeleteConversation(id: String)` | `data class` | Deletes a conversation by ID. |
| `SelectModel(modelId: String)` | `data class` | Selects a model for inference. |
| `BackToList` | `data object` | Navigates back to the conversation list. |
| `DismissError` | `data object` | Clears the current error message. |
| `ShowModelConfig` | `data object` | Opens the model configuration panel. |
| `HideModelConfig` | `data object` | Closes the model configuration panel. |
| `RefreshModelRuntime` | `data object` | Re-probes the selected model runtime state. |
| `UpdateTemperature(value: Float)` | `data class` | Updates inference temperature. |
| `UpdateTopP(value: Float)` | `data class` | Updates inference top-P. |
| `UpdateTopK(value: Int)` | `data class` | Updates inference top-K. |
| `UpdateRepeatPenalty(value: Float)` | `data class` | Updates repeat penalty. |
| `UpdateMaxTokens(value: Int)` | `data class` | Updates max tokens to generate. |
| `UpdateSystemPrompt(value: String)` | `data class` | Updates the system prompt. |
| `ResetInferenceParams` | `data object` | Resets all inference params to defaults. |
| `ApplyTaskTemplate(systemPrompt, starterPrompt)` | `data class` | Applies a task template with custom system prompt and starter. |
| `RunBenchmark` | `data object` | Runs a pp/tg benchmark on the loaded model. |
| `ClearBenchmarkResult` | `data object` | Clears the benchmark result display. |
| `ToggleBackend` | `data object` | Switches between LOCAL and OLLAMA backends. |
| `CheckOllamaConnection` | `data object` | Tests connection to Ollama server. |
| `SelectOllamaModel(modelName: String)` | `data class` | Selects an Ollama model. |
| `AttachImage(imagePath: String)` | `data class` | Attaches an image to the next message. |
| `ClearImageAttachment` | `data object` | Removes the pending image attachment. |
| `StartListening` | `data object` | Starts Vosk speech recognition. |
| `StopListening` | `data object` | Stops speech recognition. |
| `ToggleTts` | `data object` | Toggles TTS playback on/off. |
| `StopSpeaking` | `data object` | Stops current TTS playback. |
| `DownloadVoskModel` | `data object` | Downloads the Vosk ASR model. |
| `WebSearch(query: String)` | `data class` | Performs a web search via ToolRegistry. |

### Method

#### `onAction`

```kotlin
fun onAction(action: ChatAction)
```

Main entry point for all UI actions. Dispatches to internal handler methods based on the action type.

**Usage:**
```kotlin
// In Composable
val state by viewModel.uiState.collectAsState()

viewModel.onAction(ChatAction.InputChanged("Hello"))
viewModel.onAction(ChatAction.SendMessage)
viewModel.onAction(ChatAction.StopGeneration)
viewModel.onAction(ChatAction.SelectModel("gemma4_e2b_q4km"))
viewModel.onAction(ChatAction.StartListening)
```

### Key Behaviors

1. **Model Loading:** On init, observes downloaded models and auto-selects the first available GGUF model. Model loading is deferred until the first message is sent (`ensureEngineReady`).
2. **Text Inference:** Uses `ggufEngine.getResponseAsFlow()` for streaming token generation. Collects tokens into `streamingText` and persists the final message to the conversation repository.
3. **Multimodal Image Inference:** If `supportsVision()` is true and an image is attached, uses `startCompletionWithImage()` + manual `completionLoop()`. Otherwise falls back to text-only.
4. **ASR Integration:** Observes `VoskSpeechManager.asrState` and copies `finalTranscript` to `inputText` when recognition completes.
5. **TTS Playback:** After generation completes, if TTS is enabled, filters the response through `TtsTextFilter` and plays via `KittenTtsEngine`.
6. **Performance Stats:** Captures `PerformanceUsageSampler` snapshots at generation start, first token, and end. Computes tokens/second, CPU/GPU usage, and first-token latency.

---

## VoskSpeechManager

**Module:** `core-data`
**Package:** `com.masterllm.core.data`
**File:** `core-data/src/main/kotlin/com/masterllm/core/data/VoskSpeechManager.kt`

Offline speech recognition manager using the Vosk library. Requires a Vosk model downloaded to `context.filesDir/vosk-model/`.

```kotlin
class VoskSpeechManager(private val context: Context)
```

### Types

#### `ListeningState`

```kotlin
enum class ListeningState {
    IDLE,           // Not listening
    LOADING_MODEL,  // Model is being loaded
    LISTENING,      // Actively listening for speech
    PROCESSING,     // Processing final result
    ERROR           // An error occurred
}
```

#### `AsrState`

```kotlin
data class AsrState(
    val state: ListeningState = ListeningState.IDLE,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val error: String? = null,
)
```

| Property | Type | Description |
|---|---|---|
| `state` | `ListeningState` | Current recognition state. |
| `partialTranscript` | `String` | Interim (unstable) recognition result. |
| `finalTranscript` | `String` | Final recognized text after speech ends. |
| `error` | `String?` | Error message if state is `ERROR`. |

### Properties

#### `asrState`

```kotlin
val asrState: StateFlow<AsrState>
```

Observable state flow of the current ASR state.

### Methods

#### `hasMicPermission`

```kotlin
fun hasMicPermission(): Boolean
```

Returns `true` if `RECORD_AUDIO` permission is granted.

#### `isAvailable`

```kotlin
fun isAvailable(): Boolean
```

Returns `true` if the Vosk model is loaded and ready.

#### `initModel`

```kotlin
fun initModel(onReady: (Boolean) -> Unit)
```

Loads the Vosk model from `context.filesDir/vosk-model/`. Runs asynchronously on `Dispatchers.IO`. Calls `onReady(true)` on success, `onReady(false)` on failure.

| Parameter | Type | Description |
|---|---|---|
| `onReady` | `(Boolean) -> Unit` | Callback invoked on the main thread when loading completes. |

**Usage:**
```kotlin
voskSpeechManager.initModel { ready ->
    if (ready) {
        // Model loaded, can start listening
    } else {
        // Model not found, prompt download
    }
}
```

#### `startListening`

```kotlin
fun startListening(onResult: (String) -> Unit)
```

Starts speech recognition. Creates a Vosk `Recognizer` and `SpeechService`, then begins listening on the microphone.

| Parameter | Type | Description |
|---|---|---|
| `onResult` | `(String) -> Unit` | Callback invoked with the final recognized text. |

**Behavior:**
- Checks microphone permission; sets `AsrState.ERROR` if not granted.
- Checks model availability; sets `AsrState.ERROR` if not loaded.
- Updates `asrState` with partial results in real-time.
- On final result, updates `asrState.state` to `IDLE` and invokes `onResult`.

**Usage:**
```kotlin
voskSpeechManager.startListening { recognizedText ->
    viewModel.onAction(ChatAction.InputChanged(recognizedText))
}
```

#### `stopListening`

```kotlin
fun stopListening()
```

Stops the current speech recognition session and releases the `SpeechService`.

#### `destroy`

```kotlin
fun destroy()
```

Releases all resources: stops listening, closes the Vosk model, and cancels the coroutine scope.

---

## KittenTtsEngine

**Module:** `core-data`
**Package:** `com.masterllm.core.data`
**File:** `core-data/src/main/kotlin/com/masterllm/core/data/KittenTtsEngine.kt`

On-device text-to-speech engine using KittenTTS (ONNX-based). Copies model assets from APK to internal storage, generates audio via ONNX Runtime, and plays via `AudioTrack`.

```kotlin
class KittenTtsEngine()
```

### Methods

#### `initialize`

```kotlin
suspend fun initialize(context: Context): Boolean
```

Initializes the TTS engine. Copies the ONNX model (`kitten_tts_nano_v0_8.onnx`) and voice embeddings (`voices.npz`) from APK assets to `context.filesDir/tts_models/kittentts/`. Creates the internal `OnnxTtsEngine` instance.

| Parameter | Type | Description |
|---|---|---|
| `context` | `Context` | Application context for accessing assets. |

**Returns:** `Boolean` — `true` if initialization succeeded.

**Usage:**
```kotlin
val success = kittenTtsEngine.initialize(context)
```

#### `isAvailable`

```kotlin
fun isAvailable(): Boolean
```

Returns `true` if the engine is initialized and ready.

#### `speak`

```kotlin
suspend fun speak(
    text: String,
    voiceIndex: Int = 0,
    speed: Float = 1.0f,
): Boolean
```

Generates audio from text and plays it synchronously (blocks until playback completes).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `text` | `String` | — | Text to speak. |
| `voiceIndex` | `Int` | `0` | Index of the voice embedding to use. |
| `speed` | `Float` | `1.0f` | Playback speed multiplier. |

**Returns:** `Boolean` — `true` if playback succeeded.

**Behavior:** Stops any current playback before starting new audio. Audio is generated as PCM 16-bit mono at 24kHz sample rate.

#### `stop`

```kotlin
fun stop()
```

Stops current audio playback, pauses and releases the `AudioTrack`.

#### `destroy`

```kotlin
fun destroy()
```

Stops playback and closes the ONNX session, releasing all resources.

---

## TtsTextFilter

**Module:** `core-data`
**Package:** `com.masterllm.core.data`
**File:** `core-data/src/main/kotlin/com/masterllm/core/data/TtsTextFilter.kt`

Singleton text filter that strips model-internal markup from LLM output before TTS playback.

```kotlin
object TtsTextFilter
```

### Methods

#### `filter`

```kotlin
fun filter(rawModelOutput: String): String
```

Cleans raw model output for TTS consumption by removing:

| Pattern | Description |
|---|---|
| Channel-based thinking blocks | Gemma 4 internal thinking (`<\|channel>thought...`) |
| Legacy thinking blocks | Standard `<think>...</think>` blocks |
| HTML tags | Any HTML-like tags |
| Markdown code blocks | Fenced code blocks |
| Markdown bold | `**text**` (keeps content) |
| Markdown italic | `*text*` (keeps content) |
| Markdown inline code | Backtick-wrapped code (keeps content) |
| Markdown headers | Header markers |
| Excess whitespace | Triple+ newlines collapsed to double |

**Returns:** `String` — Cleaned text suitable for TTS.

**Usage:**
```kotlin
val cleanText = TtsTextFilter.filter(rawModelResponse)
kittenTtsEngine.speak(cleanText)
```

---

## ToolRegistry

**Module:** `runtime-gguf`
**Package:** `com.masterllm.runtime.gguf`
**File:** `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/ToolRegistry.kt`

Registry for agent tools used by `OpenClawEngine`. Manages registration, lookup, and system prompt generation for tool-calling workflows.

```kotlin
class ToolRegistry(private val context: Context)
```

### Data Classes

#### `OpenClawTool`

```kotlin
data class OpenClawTool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>,
    val executor: suspend (Map<String, String>) -> String,
)
```

| Property | Type | Description |
|---|---|---|
| `name` | `String` | Unique tool name (e.g., `"web_search"`). |
| `description` | `String` | Human-readable description for the model. |
| `parameters` | `Map<String, String>` | Parameter names and type descriptions. |
| `executor` | `suspend (Map<String, String>) -> String` | Async function that executes the tool. |

#### `ToolCall`

```kotlin
data class ToolCall(
    val name: String,
    val params: Map<String, String>,
)
```

#### `ToolResult`

```kotlin
data class ToolResult(
    val toolCall: ToolCall,
    val output: String,
    val isError: Boolean = false,
)
```

### Methods

#### `register`

```kotlin
fun register(tool: OpenClawTool)
```

Registers a tool. If a tool with the same name exists, it is replaced.

#### `getTool`

```kotlin
fun getTool(name: String): OpenClawTool?
```

Returns the tool with the given name, or `null` if not found.

#### `getAllTools`

```kotlin
fun getAllTools(): List<OpenClawTool>
```

Returns a copy of all registered tools.

#### `buildToolsJson`

```kotlin
fun buildToolsJson(): String
```

Returns a JSON-formatted string listing all tools (name, description, parameters). Used in system prompt construction.

#### `buildSystemPrompt`

```kotlin
fun buildSystemPrompt(): String
```

Builds the full system prompt for the agent, including tool definitions and tool-call format instructions.

### Built-in Tools

Registered automatically in `init`:

| Tool Name | Description | Parameters |
|---|---|---|
| `web_search` | Searches the web via DuckDuckGo HTML. Returns top 5 results with titles, snippets, and URLs. | `query: string (required)` |
| `fetch_url` | Fetches text content from a URL. Returns up to 8000 characters. | `url: string (required)` |
| `current_time` | Returns the current date and time in `yyyy-MM-dd HH:mm:ss` format. | *(none)* |

**Usage:**
```kotlin
val registry = ToolRegistry(context)

// Register custom tool
registry.register(OpenClawTool(
    name = "get_weather",
    description = "Get current weather for a city",
    parameters = mapOf("city" to "string (required)"),
    executor = { params ->
        val city = params["city"] ?: "Unknown"
        "Sunny, 25C in $city"
    }
))

// Use with OpenClawEngine
val engine = OpenClawEngine(ggufEngine, registry)
```

---

## OpenClawEngine

**Module:** `runtime-gguf`
**Package:** `com.masterllm.runtime.gguf`
**File:** `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/OpenClawEngine.kt`

Agent execution engine that orchestrates multi-turn tool-calling conversations. Parses tool calls from model output, executes them via `ToolRegistry`, and feeds results back to the model.

```kotlin
class OpenClawEngine(
    private val ggufEngine: GgufEngine,
    private val toolRegistry: ToolRegistry,
)
```

### Data Classes

#### `AgentMessage`

```kotlin
data class AgentMessage(
    val role: String,         // "user", "assistant", "tool"
    val content: String,
    val isToolCall: Boolean = false,
    val isToolResult: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)
```

#### `AgentSession`

```kotlin
data class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val messages: List<AgentMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val title: String = "OpenClaw Session",
)
```

#### `AgentStatus`

```kotlin
sealed class AgentStatus {
    data object Idle : AgentStatus()
    data class Thinking(val partialText: String) : AgentStatus()
    data class ExecutingTool(val toolName: String, val params: Map<String, String>) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
}
```

### Methods

#### `isModelLoaded`

```kotlin
fun isModelLoaded(): Boolean
```

Delegates to `ggufEngine.isModelLoaded()`.

#### `processQuery`

```kotlin
suspend fun processQuery(
    userInput: String,
    session: AgentSession,
    maxTurns: Int = 5,
    onStatus: (AgentStatus) -> Unit = {},
): AgentSession
```

Processes a user query through the agent loop: generates a response, parses tool calls, executes them, feeds results back, and repeats until the model produces a final response or `maxTurns` is reached.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `userInput` | `String` | — | The user's query. |
| `session` | `AgentSession` | — | Current agent session with message history. |
| `maxTurns` | `Int` | `5` | Maximum number of tool-calling turns. |
| `onStatus` | `(AgentStatus) -> Unit` | `{}` | Callback for status updates (thinking, executing tool, error). |

**Returns:** `AgentSession` — Updated session with new messages appended.

**Behavior:**
1. Adds user message to session.
2. Sets system prompt from `toolRegistry.buildSystemPrompt()`.
3. Builds conversation context using Gemma 4 turn delimiters.
4. Generates response via `ggufEngine.getResponseAsFlow()`.
5. Parses tool call XML from the response.
6. If no tool calls found, returns the response as final.
7. Executes each tool call via `toolRegistry.getTool(name)?.executor(params)`.
8. Feeds tool results back to the model and loops.
9. Returns after `maxTurns` or when the model produces a final response.

**Usage:**
```kotlin
val engine = OpenClawEngine(ggufEngine, ToolRegistry(context))
var session = AgentSession()

session = engine.processQuery(
    userInput = "What's the weather in Tokyo?",
    session = session,
    maxTurns = 5,
    onStatus = { status ->
        when (status) {
            is AgentStatus.Thinking -> println("Thinking: ${status.partialText}")
            is AgentStatus.ExecutingTool -> println("Running: ${status.toolName}")
            is AgentStatus.Error -> println("Error: ${status.message}")
            AgentStatus.Idle -> println("Done")
        }
    }
)

// Final response is the last assistant message
val finalResponse = session.messages.lastOrNull { it.role == "assistant" }?.content
```

---

## ModelDownloadManager

**Module:** `app`
**Package:** `com.masterllm.app.solace`
**File:** `app/src/main/kotlin/com/masterllm/app/solace/ModelDownloadManager.kt`

Singleton Hilt-injected manager for downloading, verifying, and managing the bundled Gemma 4 E2B model and its mmproj vision projector.

```kotlin
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
)
```

### Constants

| Constant | Type | Value | Description |
|---|---|---|---|
| `MODEL_FILENAME` | `String` | `"gemma-4-E2B-it-Q4_K_M.gguf"` | Main model filename. |
| `MODEL_SHA256` | `String` | `"9378bc47..."` | SHA-256 hash for integrity verification. |
| `MODEL_SIZE_BYTES` | `Long` | `3_340_000_000` | Approximate model size (~3.11 GB). |
| `MODEL_DISPLAY_NAME` | `String` | `"Gemma 4 E2B (Q4_K_M)"` | Display name for UI. |
| `MODEL_DOWNLOAD_URL` | `String` | HuggingFace URL | Direct download URL for the GGUF model. |
| `MMPROJ_FILENAME` | `String` | `"gemma-4-E2B-it.BF16-mmproj.gguf"` | Vision projector filename. |
| `MMPROJ_DOWNLOAD_URL` | `String` | HuggingFace URL | Direct download URL for the mmproj. |
| `MMPROJ_SIZE_BYTES` | `Long` | `986_833_408` | Approximate mmproj size (~941 MB). |
| `CONTEXT_LENGTH` | `Int` | `131072` | Model context length (128K). |
| `ARCHITECTURE` | `String` | `"gemma4"` | Model architecture identifier. |
| `EOS_TOKEN` | `String` | `"<eos>"` | End-of-sequence token. |

### Data Classes

#### `DownloadStatus`

```kotlin
sealed class DownloadStatus {
    data object CheckingLocal : DownloadStatus()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : DownloadStatus() {
        val progressPercent: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) * 100f else 0f
    }
    data object Verifying : DownloadStatus()
    data object Ready : DownloadStatus()
    data class Error(val message: String, val retryable: Boolean = true) : DownloadStatus()
}
```

### Methods

#### `isModelReady`

```kotlin
fun isModelReady(): Boolean
```

Returns `true` if the main model file exists on disk, has non-zero size, and was previously verified (SharedPreferences flag).

#### `isMmprojReady`

```kotlin
fun isMmprojReady(): Boolean
```

Returns `true` if the mmproj file exists on disk, has non-zero size, and was previously verified.

#### `ensureModelReady`

```kotlin
fun ensureModelReady(): Flow<DownloadStatus>
```

Returns a `Flow` that emits download status updates. Downloads the main model if not present, verifies SHA-256 integrity, and supports resume for interrupted downloads. Emits `Ready` when the model is available.

**Behavior:**
1. Emits `CheckingLocal`.
2. If model exists and is verified, emits `Ready` immediately.
3. If model exists but is not verified, verifies SHA-256 and emits `Verifying`.
4. Otherwise downloads from `MODEL_DOWNLOAD_URL` with resume support.
5. Verifies SHA-256 after download.
6. Emits `Ready` on success or `Error` on failure.

**Usage:**
```kotlin
modelDownloadManager.ensureModelReady().collect { status ->
    when (status) {
        is DownloadStatus.Downloading -> {
            val pct = status.progressPercent
            showProgress(pct)
        }
        is DownloadStatus.Ready -> {
            // Model is ready for inference
        }
        is DownloadStatus.Error -> {
            showError(status.message)
        }
        else -> { /* CheckingLocal, Verifying */ }
    }
}
```

#### `ensureMmprojReady`

```kotlin
fun ensureMmprojReady(): Flow<DownloadStatus>
```

Returns a `Flow` that downloads the mmproj vision projector if not present. Same pattern as `ensureModelReady` but without SHA-256 verification.

#### `deleteModel`

```kotlin
fun deleteModel()
```

Deletes the main model file and any partial download, and clears the SharedPreferences ready flag.

#### `getModelPath`

```kotlin
fun getModelPath(): String
```

Returns the absolute path to the main model file.

#### `getMmprojPath`

```kotlin
fun getMmprojPath(): String
```

Returns the absolute path to the mmproj file.

#### `getAvailableStorageBytes`

```kotlin
fun getAvailableStorageBytes(): Long
```

Returns the available storage space in the model directory. Returns `-1` on error.

---

## BundledModelManager

**Module:** `core-data`
**Package:** `com.masterllm.core.data`
**File:** `core-data/src/main/kotlin/com/masterllm/core/data/BundledModelManager.kt`

Singleton object that manages the bundled Gemma 4 E2B model. The model is downloaded at runtime (not bundled in APK), but this manager provides a consistent entry point for model discovery.

```kotlin
object BundledModelManager
```

### Constants

| Constant | Type | Value | Description |
|---|---|---|---|
| `MODEL_DISPLAY_NAME` | `String` | `"Gemma 4 E2B (Q4_K_M)"` | Display name. |
| `MODEL_ID` | `String` | `"gemma4_e2b_q4km"` | Unique model identifier. |
| `ARCHITECTURE` | `String` | `"gemma4"` | Architecture identifier. |
| `CONTEXT_LENGTH` | `Int` | `131072` | Context length (128K). |
| `EOS_TOKEN` | `String` | `"<eos>"` | End-of-sequence token. |
| `MODEL_FILENAME` | `String` | `"gemma-4-E2B-it-Q4_K_M.gguf"` | Model filename. |
| `GEMMA4_CHAT_TEMPLATE` | `String` | *(Gemma 4 template)* | Chat template with turn delimiters. |

### Data Classes

#### `BundledModelInfo`

```kotlin
data class BundledModelInfo(
    val id: String,
    val displayName: String,
    val localPath: String,
    val architecture: String,
    val contextLength: Int,
    val eosToken: String,
)
```

### Methods

#### `initialize`

```kotlin
fun initialize(context: Context): BundledModelInfo?
```

Checks if the bundled model exists on disk (external or internal files dir). Returns `BundledModelInfo` if found, or `null` if the model has not been downloaded yet.

| Parameter | Type | Description |
|---|---|---|
| `context` | `Context` | Application context for file path resolution. |

**Returns:** `BundledModelInfo?` — Model metadata if the file exists, `null` otherwise.

**Usage:**
```kotlin
val modelInfo = BundledModelManager.initialize(context)
if (modelInfo != null) {
    ggufEngine.load(modelInfo.localPath)
} else {
    // Trigger download via ModelDownloadManager
}
```

#### `getRecommendedInferenceParams`

```kotlin
fun getRecommendedInferenceParams(): Map<String, Any>
```

Returns a map of recommended inference parameters for the bundled model. Includes temperature, topP, topK, minP, repeatPenalty, contextSize (16K), maxTokens (4096), thread count, batch sizes, mmap/mlock settings, and the Gemma 4 chat template.

#### `isModelAvailable`

```kotlin
fun isModelAvailable(context: Context): Boolean
```

Returns `true` if the model file exists and has non-zero size.

---

## RoleplayViewModel

**Module:** `feature-roleplay`
**Package:** `com.masterllm.feature.roleplay`
**File:** `feature-roleplay/src/main/kotlin/com/masterllm/feature/roleplay/RoleplayViewModel.kt`

HiltViewModel that manages the roleplay screen state, session lifecycle, character setup, text inference with conversation replay, and scene image generation.

```kotlin
@HiltViewModel
class RoleplayViewModel @Inject constructor(
    private val roleplayRepository: RoleplayRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val ggufEngine: GgufEngine,
    private val runtimeCoordinator: GgufRuntimeCoordinator,
    private val imageGenEngine: ImageGenEngine,
    private val safetensorsEngine: SafetensorsEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel()
```

### State

#### `uiState`

```kotlin
val uiState: StateFlow<RoleplayUiState>
```

Observable UI state for the roleplay screen.

#### `RoleplayUiState`

```kotlin
data class RoleplayUiState(
    val sessions: List<RoleplaySession> = emptyList(),
    val currentSession: RoleplaySession? = null,
    val messages: List<Message> = emptyList(),
    val availableModels: List<LlmModel> = emptyList(),
    val selectedModelId: String? = null,
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val generationStatus: String? = null,
    val showSessionList: Boolean = true,
    val showSetupDialog: Boolean = false,
    val showModelConfig: Boolean = false,
    val setupTitle: String = "",
    val setupGenre: String = "Fantasy",
    val setupPremise: String = "",
    val setupAiName: String = "",
    val setupAiDescription: String = "",
    val setupUserName: String = "",
    val setupUserDescription: String = "",
    val setupVisualStyle: VisualStyle = VisualStyle.FANTASY_ART,
    val selectedModelInfo: LlmModel? = null,
    val modelRuntime: RoleplayModelRuntimeInfo = RoleplayModelRuntimeInfo(),
    val error: String? = null,
    val inferenceParams: InferenceParams = InferenceParams(),
    val lastGenerationStats: RoleplayGenerationStats? = null,
)
```

| Property | Type | Description |
|---|---|---|
| `sessions` | `List<RoleplaySession>` | All saved roleplay sessions. |
| `currentSession` | `RoleplaySession?` | The currently active roleplay session. |
| `messages` | `List<Message>` | Messages in the current session's conversation. |
| `isGenerating` | `Boolean` | Whether the model is generating a response. |
| `streamingText` | `String` | Accumulated streaming text. |
| `showSetupDialog` | `Boolean` | Whether the session setup dialog is visible. |
| `setupTitle` | `String` | Title for the new session being created. |
| `setupGenre` | `String` | Genre selection for the new session. |
| `setupPremise` | `String` | Premise/story setup for the new session. |
| `setupAiName` | `String` | AI character name. |
| `setupAiDescription` | `String` | AI character description. |
| `setupUserName` | `String` | User character name. |
| `setupUserDescription` | `String` | User character description. |
| `setupVisualStyle` | `VisualStyle` | Visual style for scene image generation. |
| `modelRuntime` | `RoleplayModelRuntimeInfo` | Current model runtime status. |
| `lastGenerationStats` | `RoleplayGenerationStats?` | Performance stats from last generation. |

#### `RoleplayModelRuntimeInfo`

```kotlin
data class RoleplayModelRuntimeInfo(
    val modelId: String? = null,
    val modelDisplayName: String = "No model selected",
    val backend: String = "Not loaded",
    val statusLabel: String = "Idle",
    val threadCount: Int = 4,
    val gpuLayers: Int = 0,
    val contextSize: Int = 2048,
    val gpuAccelerationEnabled: Boolean = false,
    val loadDurationMs: Long? = null,
    val note: String = "Select a model to start roleplay inference.",
    val lastError: String? = null,
)
```

#### `RoleplayGenerationStats`

```kotlin
data class RoleplayGenerationStats(
    val modelDisplayName: String,
    val backend: String,
    val modelLoadDurationMs: Long,
    val replayedMessages: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val generatedChars: Int,
    val firstTokenLatencyMs: Long,
    val durationMs: Long,
    val promptTokensPerSecond: Double,
    val nativePromptTokensPerSecond: Float,
    val decodeTokensPerSecond: Double,
    val nativeTokensPerSecond: Float,
    val promptCpuUsagePercent: Double,
    val decodeCpuUsagePercent: Double,
    val promptGpuUsagePercent: Double?,
    val decodeGpuUsagePercent: Double?,
    val threadCount: Int,
    val gpuLayers: Int,
    val contextSize: Int,
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
)
```

### Actions

#### `RoleplayAction`

Sealed interface representing all user-initiated actions on the roleplay screen.

| Action | Type | Description |
|---|---|---|
| `InputChanged(text: String)` | `data class` | Updates the input text field. |
| `SendMessage` | `data object` | Sends the current input as a roleplay message. |
| `StopGeneration` | `data object` | Stops the currently running generation. |
| `SelectSession(id: String)` | `data class` | Opens an existing session by ID. |
| `NewSession` | `data object` | Opens the session setup dialog. |
| `DeleteSession(id: String)` | `data class` | Deletes a session by ID. |
| `SelectModel(modelId: String)` | `data class` | Selects a model for inference. |
| `BackToList` | `data object` | Navigates back to the session list. |
| `ShowSetup` | `data object` | Opens the session setup dialog. |
| `DismissSetup` | `data object` | Closes the session setup dialog. |
| `ShowModelConfig` | `data object` | Opens the model configuration panel. |
| `HideModelConfig` | `data object` | Closes the model configuration panel. |
| `RefreshModelRuntime` | `data object` | Re-probes the selected model runtime state. |
| `GenerateSceneImage` | `data object` | Generates a scene image for the current roleplay context. |
| `SetupTitleChanged(v: String)` | `data class` | Updates setup title. |
| `SetupGenreChanged(v: String)` | `data class` | Updates setup genre. |
| `SetupPremiseChanged(v: String)` | `data class` | Updates setup premise. |
| `SetupAiNameChanged(v: String)` | `data class` | Updates AI character name. |
| `SetupAiDescChanged(v: String)` | `data class` | Updates AI character description. |
| `SetupUserNameChanged(v: String)` | `data class` | Updates user character name. |
| `SetupUserDescChanged(v: String)` | `data class` | Updates user character description. |
| `SetupStyleChanged(v: VisualStyle)` | `data class` | Updates visual style. |
| `CreateSession` | `data object` | Creates a new roleplay session from setup values. |
| `DismissError` | `data object` | Clears the current error message. |
| `UpdateTemperature(value: Float)` | `data class` | Updates inference temperature. |
| `UpdateTopP(value: Float)` | `data class` | Updates inference top-P. |
| `UpdateTopK(value: Int)` | `data class` | Updates inference top-K. |
| `UpdateRepeatPenalty(value: Float)` | `data class` | Updates repeat penalty. |
| `UpdateMaxTokens(value: Int)` | `data class` | Updates max tokens to generate. |
| `UpdateSystemPrompt(value: String)` | `data class` | Updates the system prompt. |
| `ResetInferenceParams` | `data object` | Resets all inference params to defaults. |

### Method

#### `onAction`

```kotlin
fun onAction(action: RoleplayAction)
```

Main entry point for all UI actions. Dispatches to internal handler methods based on the action type.

**Usage:**
```kotlin
// In Composable
val state by viewModel.uiState.collectAsState()

viewModel.onAction(RoleplayAction.InputChanged("I draw my sword."))
viewModel.onAction(RoleplayAction.SendMessage)
viewModel.onAction(RoleplayAction.GenerateSceneImage)
viewModel.onAction(RoleplayAction.CreateSession)
```

### Key Behaviors

1. **Session Management:** Creates sessions with character names, genre, premise, and visual style. Sessions are linked to underlying `Conversation` objects.
2. **Conversation Replay:** Before generating, replays all existing messages from the conversation into the native context via `addSystemPrompt`/`addUserMessage`/`addAssistantMessage`.
3. **System Prompt Construction:** Builds a roleplay-specific system prompt from session metadata (character names, descriptions, genre, premise).
4. **Text Inference:** Same streaming pattern as `ChatViewModel` — uses `ggufEngine.getResponseAsFlow()` with `PerformanceUsageSampler` tracking.
5. **Scene Image Generation:** Uses `ImageGenEngine` with visual style prefix appended to the prompt. Persists generated images to `context.filesDir/generated/roleplay/{sessionId}/`.
6. **Model Resolution:** Uses `TextRuntimeModelResolver` to find a GGUF fallback when the selected model is SafeTensors-only.

---

## Domain Models

**Module:** `core-domain`
**Package:** `com.masterllm.core.domain.model`
**File:** `core-domain/src/main/kotlin/com/masterllm/core/domain/model/LlmModel.kt`

### `InferenceParams`

```kotlin
data class InferenceParams(
    val minP: Float = 0.1f,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val repeatPenaltyLastN: Float = 64f,
    val seed: Long = -1L,
    val maxTokens: Int = 2048,
    val systemPrompt: String = "",
    val storeChats: Boolean = true,
    val contextSize: Int? = null,
    val chatTemplate: String? = null,
    val numThreads: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val nBatch: Int = 0,
    val nUbatch: Int = 0,
)
```

| Property | Type | Default | Description |
|---|---|---|---|
| `minP` | `Float` | `0.1f` | Minimum probability threshold for sampling. |
| `temperature` | `Float` | `0.7f` | Sampling temperature. Higher = more random. |
| `topP` | `Float` | `0.9f` | Nucleus sampling threshold. |
| `topK` | `Int` | `40` | Top-K sampling limit. |
| `repeatPenalty` | `Float` | `1.1f` | Penalty for repeated tokens. |
| `repeatPenaltyLastN` | `Float` | `64f` | Number of last tokens to apply repeat penalty to. |
| `seed` | `Long` | `-1L` | Random seed. `-1` for random. |
| `maxTokens` | `Int` | `2048` | Maximum tokens to generate per response. |
| `systemPrompt` | `String` | `""` | System prompt for the conversation. |
| `storeChats` | `Boolean` | `true` | Whether to store chat history in native context. |
| `contextSize` | `Int?` | `null` | Context window size. `null` uses GGUF metadata default. |
| `chatTemplate` | `String?` | `null` | Chat template string. `null` uses GGUF metadata default. |
| `numThreads` | `Int` | `0` | Number of CPU threads. `0` for auto-detect. |
| `useMmap` | `Boolean` | `true` | Whether to use memory-mapped file I/O. |
| `useMlock` | `Boolean` | `false` | Whether to lock model in RAM. |
| `nBatch` | `Int` | `0` | Batch size for prompt processing. `0` for auto. |
| `nUbatch` | `Int` | `0` | Micro-batch size. `0` for auto. |

### `LlmModel`

```kotlin
data class LlmModel(
    val id: String = "",
    val repoId: String = "",
    val fileName: String = "",
    val displayName: String = "",
    val format: ModelFormat = ModelFormat.GGUF,
    val sizeBytes: Long = 0L,
    val quantization: String = "",
    val localPath: String? = null,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val contextLength: Int = 4096,
    val parameterCount: String = "",
    val downloadedAt: Long = 0L,
    val description: String = "",
)
```

### `ModelFormat`

```kotlin
enum class ModelFormat { GGUF, SAFETENSORS, DIFFUSERS }
```

### `DownloadState`

```kotlin
enum class DownloadState { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED }
```

### `Conversation`

```kotlin
data class Conversation(
    val id: String = "",
    val title: String = "New Conversation",
    val mode: ConversationMode = ConversationMode.CHAT,
    val modelId: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
)
```

### `ConversationMode`

```kotlin
enum class ConversationMode { CHAT, ROLEPLAY }
```

### `Message`

```kotlin
data class Message(
    val id: String = "",
    val conversationId: String = "",
    val role: MessageRole = MessageRole.USER,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val attachedImagePath: String? = null,
    val isStreaming: Boolean = false,
)
```

### `MessageRole`

```kotlin
enum class MessageRole { USER, ASSISTANT, SYSTEM, IMAGE_GEN, OOC }
```

### `RoleplaySession`

```kotlin
data class RoleplaySession(
    val id: String = "",
    val conversationId: String = "",
    val title: String = "",
    val genre: String = "",
    val premise: String = "",
    val aiCharacterName: String = "",
    val aiCharacterDescription: String = "",
    val aiCharacterAppearance: String = "",
    val userCharacterName: String = "",
    val userCharacterDescription: String = "",
    val userCharacterAppearance: String = "",
    val worldDetails: String = "",
    val writingStyle: String = "",
    val imageModelId: String? = null,
    val visualStyle: VisualStyle = VisualStyle.FANTASY_ART,
    val imageFrequency: ImageFrequency = ImageFrequency.EVERY_RESPONSE,
    val narrativeResponseCount: Int = 0,
    val lastImagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

### `VisualStyle`

```kotlin
enum class VisualStyle(val displayName: String) {
    PHOTOREALISTIC("Photorealistic"),
    FANTASY_ART("Fantasy Art"),
    ANIME("Anime / Manga"),
    OIL_PAINTING("Oil Painting"),
    WATERCOLOR("Watercolor"),
    PIXEL_ART("Pixel Art"),
    COMIC("Comic Book"),
    SKETCH("Pencil Sketch"),
    CINEMATIC("Cinematic"),
    CUSTOM("Custom (raw prompt pass-through)")
}
```

### `ImageFrequency`

```kotlin
enum class ImageFrequency(val displayName: String) {
    EVERY_RESPONSE("Every response"),
    EVERY_2("Every 2 responses"),
    EVERY_5("Every 5 responses"),
    KEY_MOMENTS("Key moments only (AI decides)"),
    MANUAL("Manual only")
}
```
