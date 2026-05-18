# Solace Android — Module Reference

> **Project**: MasterLLM (Solace) | **Package**: `com.masterllm.app` | **Version**: 2.0.5  
> **Architecture**: Gradle multi-module with clean architecture (domain → data → feature → app)  
> **Build system**: Gradle 8.x with Kotlin DSL, KSP, Hilt DI

---

## Table of Contents

- [Module Dependency Graph](#module-dependency-graph)
- [app/ — Application Module](#app--application-module)
- [core-domain/ — Domain Layer](#core-domain--domain-layer)
- [core-data/ — Data Layer](#core-data--data-layer)
- [core-network/ — Network Layer](#core-network--network-layer)
- [core-ollama/ — Ollama Integration](#core-ollama--ollama-integration)
- [core-ui/ — UI Components](#core-ui--ui-components)
- [runtime-gguf/ — Native GGUF Inference](#runtime-gguf--native-gguf-inference)
- [runtime-safetensors/ — Safetensors Runtime](#runtime-safetensors--safetensors-runtime)
- [runtime-imagegen/ — Image Generation Runtime](#runtime-imagegen--image-generation-runtime)
- [feature-chat/ — Chat Feature](#feature-chat--chat-feature)
- [feature-roleplay/ — Guided Sessions](#feature-roleplay--guided-sessions)
- [feature-settings/ — Settings](#feature-settings--settings)
- [feature-auth/ — Authentication](#feature-auth--authentication)
- [feature-marketplace/ — Model Marketplace](#feature-marketplace--model-marketplace)
- [feature-model-manager/ — Model Manager](#feature-model-manager--model-manager)
- [feature-performance/ — Performance Monitor](#feature-performance--performance-monitor)
- [feature-image-gen/ — Image Generation UI](#feature-image-gen--image-generation-ui)

---

## Module Dependency Graph

```
                                    ┌──────────┐
                                    │   app/   │
                                    └────┬─────┘
           ┌─────────────┬──────────────┼──────────────┬─────────────┐
           │             │              │              │             │
     ┌─────▼──────┐ ┌────▼─────┐ ┌─────▼──────┐ ┌────▼─────┐ ┌────▼──────┐
     │feature-chat│ │feature-  │ │feature-    │ │feature-  │ │feature-   │
     │            │ │roleplay  │ │settings    │ │auth      │ │marketplace│
     └─────┬──────┘ └────┬─────┘ └─────┬──────┘ └────┬─────┘ └────┬──────┘
           │             │              │              │             │
           ├─────────────┼──────────────┼──────────────┼─────────────┤
           │             │              │              │             │
     ┌─────▼─────────────▼──────────────▼──────────────▼─────────────▼──────┐
     │                         core-ui/                                      │
     └──────────────────────────────────────────────────────────────────────┘
           │
     ┌─────▼──────┐    ┌──────────────┐    ┌──────────────────┐
     │ core-data/ │    │ runtime-gguf │    │ runtime-imagegen │
     └─────┬──────┘    └──────┬───────┘    └────────┬─────────┘
           │                  │                      │
     ┌─────▼──────┐    ┌─────▼──────────────────────▼──────┐
     │core-domain │    │        llama.cpp (native)          │
     └────────────┘    └───────────────────────────────────┘
```

**Dependency rules**:
- `core-domain` has zero upstream module dependencies (pure Kotlin models + interfaces)
- `core-data` depends on `core-domain`
- `runtime-gguf` depends on `core-domain`
- All `feature-*` modules depend on `core-domain`, `core-data`, `core-ui`, and relevant runtimes
- `app` depends on everything — it wires navigation and provides the Application class

---

## app/ — Application Module

**Namespace**: `com.masterllm.app`  
**Type**: Android Application  
**Lines of code**: ~4,200 (21 Kotlin files)

### Purpose

The top-level application module. Provides the `Application` class, `MainActivity`, navigation graph, and Solace-specific utilities (model download, chat template, thinking parser, TTS filtering, speech recognition, agent engine).

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `MainActivity.kt` | 43 | Entry point. Sets up edge-to-edge display, theme switching (light/dark/system), and renders `MasterLLMApp` composable. |
| `MasterLLMApplication.kt` | 36 | `@HiltAndroidApp` Application class. Plants Timber debug tree, initializes `BundledModelManager`, creates notification channels. |
| `MasterLLMNavHost.kt` | 328 | Navigation graph with 5 routes: `MODEL_DOWNLOAD` → `HOME` → `CHAT`, `ROLEPLAY`, `SETTINGS`. Defines `TopLevelDestination` enum and bottom navigation bar. Home screen shows mood chips, action cards, and crisis helpline buttons. |
| `AppThemeViewModel.kt` | 35 | Reads theme preference from `SettingsRepository` and exposes `StateFlow<ThemeState>`. |
| `GgufEngineViewModel.kt` | 11 | Thin ViewModel wrapper exposing `GgufEngine` state. |

### Solace Package (`app/solace/`)

| File | Lines | Description |
|------|-------|-------------|
| `ModelDownloadManager.kt` | 335 | Downloads Gemma 4 E2B GGUF (~3.1 GB) from HuggingFace. Supports HTTP range-based resume, SHA-256 verification, and progress tracking via `StateFlow<DownloadProgress>`. |
| `ModelDownloadScreen.kt` | 362 | Consent dialog (safety disclaimer) + download progress UI. Navigates to HOME on completion. |
| `Gemma4ChatTemplate.kt` | 96 | Gemma 4 chat template with `<\|turn>`/`<turn\|>` delimiters. Provides `buildSystemContext()`, `formatUserTurn()`, `openModelTurn()`, `formatModelTurn()`, and recommended inference params. |
| `ThinkingTokenParser.kt` | 220 | Streaming parser for Gemma 4's `<\|channel>thought`/`<channel\|>` blocks. State machine with `NORMAL`, `IN_THINKING`, `BUFFERING` states. Also handles legacy `<think>`/`</think>` tags. |
| `TtsTextFilter.kt` | 32 | Strips thinking blocks, markdown, and HTML before text-to-speech output. |
| `SystemPromptLoader.kt` | 26 | Loads the Solace mental health system prompt from assets. |
| `SpeechRecognitionManager.kt` | 144 | Legacy Android `SpeechRecognizer` wrapper. |
| `VoskSpeechManager.kt` | 186 | Vosk offline ASR wrapper (duplicate of core-data version). |
| `VoskModelDownloadManager.kt` | 131 | Downloads Vosk model (~40 MB) from alphacephei.com. |

### OpenClaw Package (`app/openclaw/`)

| File | Lines | Description |
|------|-------|-------------|
| `OpenClawEngine.kt` | 182 | Agent engine for tool-calling. Parses model output for tool invocations, executes them, and feeds results back. |
| `ToolRegistry.kt` | 209 | Registers available tools: `web_search`, `fetch_url`, `current_time`. Defines tool schemas and execution logic. |
| `AgentViewModel.kt` | 108 | ViewModel for the agent screen. |

### Navigation Package (`app/navigation/`)

| File | Lines | Description |
|------|-------|-------------|
| `AgentScreen.kt` | 196 | UI for the OpenClaw agent interface. |
| `OllamaServeScreen.kt` | 730 | Ollama server management UI. |
| `VoiceScreen.kt` | 522 | Voice interaction screen (TTS/ASR testing). |
| `KittenTtsEngine.kt` | 271 | ONNX-based TTS using KittenTTS nano model (app-level copy). |

### Dependencies

```kotlin
// All core modules
implementation(project(":core-data"))
implementation(project(":core-domain"))
implementation(project(":core-network"))
implementation(project(":core-ollama"))
implementation(project(":core-ui"))

// All feature modules
implementation(project(":feature-auth"))
implementation(project(":feature-marketplace"))
implementation(project(":feature-model-manager"))
implementation(project(":feature-chat"))
implementation(project(":feature-image-gen"))
implementation(project(":feature-roleplay"))
implementation(project(":feature-performance"))
implementation(project(":feature-settings"))

// All runtime modules
implementation(project(":runtime-gguf"))
implementation(project(":runtime-safetensors"))
implementation(project(":runtime-imagegen"))

// Key external deps
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
implementation("com.alphacephei:vosk-android:0.3.47")
```

### Build Config Fields

```kotlin
MODEL_DOWNLOAD_URL = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"
MODEL_SHA256 = "9378bc471710229ef165709b62e34bfb62231420ddaf6d729e727305b5b8672d"
MODEL_FILENAME = "gemma-4-E2B-it-Q4_K_M.gguf"
```

### Public APIs

```kotlin
// Navigation routes
object Routes {
    const val MODEL_DOWNLOAD = "model_download"
    const val HOME = "home"
    const val CHAT = "chat"
    const val ROLEPLAY = "roleplay"
    const val SETTINGS = "settings"
}

// Gemma 4 template
Gemma4ChatTemplate.buildSystemContext(systemPrompt: String, enableThinking: Boolean): String
Gemma4ChatTemplate.formatUserTurn(message: String): String
Gemma4ChatTemplate.openModelTurn(): String
Gemma4ChatTemplate.getRecommendedInferenceParams(): Map<String, Any>

// Thinking parser
ThinkingTokenParser.feed(token: String): ParseResult
ThinkingTokenParser.getThinkingContent(): String
ThinkingTokenParser.getResponseContent(): String
ThinkingTokenParser.isCurrentlyThinking(): Boolean

// Model download
ModelDownloadManager.downloadProgress: StateFlow<DownloadProgress>
ModelDownloadManager.startDownload(context: Context)
ModelDownloadManager.verifyAndComplete(context: Context): Boolean
```

---

## core-domain/ — Domain Layer

**Namespace**: `com.masterllm.core.domain`  
**Type**: Android Library  
**Lines of code**: ~721 (4 Kotlin files)

### Purpose

Pure domain layer with zero Android framework dependencies (except Hilt). Defines all data models, repository interfaces, and use cases. Every other module depends on this.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `model/LlmModel.kt` | 220 | All domain models: `LlmModel`, `Conversation`, `Message`, `RoleplaySession`, `CharacterVisualEntry`, `ImagePromptResult`, `InferenceParams`, `HfModelInfo`, `HfModelFile`, `HfUserProfile`. Enums: `ModelFormat`, `DownloadState`, `ConversationMode`, `MessageRole`, `VisualStyle`, `ImageFrequency`. |
| `model/TextRuntimeModelResolver.kt` | 94 | Resolves which runtime (GGUF, Safetensors, Ollama) to use for a given model. |
| `repository/ModelRepository.kt` | 118 | Repository interfaces: `ModelRepository`, `ConversationRepository`, `RoleplayRepository`, `CharacterVisualCacheRepository`, `SettingsRepository`. |
| `usecase/UseCases.kt` | 289 | Use cases: `ValidateHfTokenUseCase`, `SearchModelsUseCase`, `DetectModelFormatUseCase`, `DownloadModelUseCase`, `CompactConversationUseCase`, `GetDownloadedModelsUseCase`, `GetConversationsUseCase`, `GetMessagesUseCase`, `SendMessageUseCase`, `GenerateImagePromptUseCase`. |

### Public APIs — Models

```kotlin
data class LlmModel(
    val id: String, val repoId: String, val fileName: String,
    val displayName: String, val format: ModelFormat, val sizeBytes: Long,
    val quantization: String, val localPath: String?, val downloadState: DownloadState,
    val contextLength: Int, val parameterCount: String, val description: String
)

data class Conversation(
    val id: String, val title: String, val mode: ConversationMode,
    val modelId: String, val systemPrompt: String, val messageCount: Int
)

data class Message(
    val id: String, val conversationId: String, val role: MessageRole,
    val content: String, val attachedImagePath: String?, val isStreaming: Boolean
)

data class RoleplaySession(
    val id: String, val conversationId: String, val title: String,
    val genre: String, val premise: String, val aiCharacterName: String,
    val aiCharacterDescription: String, val userCharacterName: String,
    val visualStyle: VisualStyle, val imageFrequency: ImageFrequency
)

data class InferenceParams(
    val minP: Float, val temperature: Float, val topP: Float, val topK: Int,
    val repeatPenalty: Float, val maxTokens: Int, val contextSize: Int?,
    val chatTemplate: String?, val numThreads: Int
)
```

### Public APIs — Repository Interfaces

```kotlin
interface ModelRepository {
    fun getDownloadedModels(): Flow<List<LlmModel>>
    suspend fun getModelById(id: String): LlmModel?
    suspend fun deleteModel(id: String)
    suspend fun saveModel(model: LlmModel)
    suspend fun updateDownloadState(id: String, state: DownloadState, localPath: String?)
}

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    fun getConversationsByMode(mode: ConversationMode): Flow<List<Conversation>>
    suspend fun createConversation(conversation: Conversation): String
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun addMessage(message: Message): String
}

interface SettingsRepository {
    fun getTheme(): Flow<String>
    fun getShowThinking(): Flow<Boolean>
    fun getVoiceOutputEnabled(): Flow<Boolean>
    fun getVoiceInputEnabled(): Flow<Boolean>
    fun getContextLength(): Flow<Int>
    // ... 20+ preference getters/setters
}
```

### Dependencies

```kotlin
implementation(libs.hilt.android)
implementation(libs.coroutines.core)
implementation(libs.gson)
```

---

## core-data/ — Data Layer

**Namespace**: `com.masterllm.core.data`  
**Type**: Android Library  
**Lines of code**: ~1,610 (15 Kotlin files)

### Purpose

Implements repository interfaces from `core-domain`. Contains Room database, DataStore preferences, bundled model management, offline speech recognition (Vosk), and text-to-speech (KittenTTS via ONNX).

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `db/AppDatabase.kt` | 26 | Room database with entities for conversations, messages, models, roleplay sessions, character visuals. |
| `db/Daos.kt` | 109 | Data access objects: `ConversationDao`, `MessageDao`, `ModelDao`, `RoleplaySessionDao`, `CharacterVisualDao`. |
| `db/ModelEntity.kt` | 114 | Room entities: `ConversationEntity`, `MessageEntity`, `ModelEntity`, `RoleplaySessionEntity`, `CharacterVisualEntity`. |
| `di/DataModule.kt` | 66 | Hilt module providing database, DAOs, and repository bindings. |
| `mapper/EntityMappers.kt` | 148 | Mappers between Room entities and domain models. |
| `repository/ConversationRepositoryImpl.kt` | 57 | Implements `ConversationRepository`. |
| `repository/ModelRepositoryImpl.kt` | 40 | Implements `ModelRepository`. |
| `repository/RoleplayRepositoryImpl.kt` | 36 | Implements `RoleplayRepository`. |
| `repository/CharacterVisualCacheRepositoryImpl.kt` | 26 | Implements `CharacterVisualCacheRepository`. |
| `repository/SettingsRepositoryImpl.kt` | 237 | Implements `SettingsRepository` using DataStore Preferences. |
| `BundledModelManager.kt` | 83 | Manages bundled GGUF model. `initialize(context)` copies model from assets to internal storage if needed. |
| `VoskSpeechManager.kt` | 186 | Offline ASR using Vosk. Manages `Recognizer` lifecycle, provides `partialResults` and `finalResults` flows. |
| `VoskModelDownloadManager.kt` | 180 | Downloads Vosk speech model (~40 MB) from `alphacephei.com`. |
| `KittenTtsEngine.kt` | 270 | ONNX-based TTS using KittenTTS nano model. Loads ONNX model, generates audio from text. |
| `TtsTextFilter.kt` | 32 | Strips thinking blocks, markdown formatting, and HTML tags before TTS. |

### Public APIs

```kotlin
// Bundled model
BundledModelManager.initialize(context: Context)
BundledModelManager.getModelFile(context: Context): File?

// Vosk speech
VoskSpeechManager.partialResults: StateFlow<String>
VoskSpeechManager.finalResults: StateFlow<String>
VoskSpeechManager.startListening()
VoskSpeechManager.stopListening()

// KittenTTS
KittenTtsEngine.initialize(context: Context)
KittenTtsEngine.speak(text: String)

// TTS text filter
TtsTextFilter.filterForSpeech(text: String): String
```

### Dependencies

```kotlin
implementation(project(":core-domain"))
implementation(libs.room.runtime)
implementation(libs.room.ktx)
implementation(libs.datastore.preferences)
implementation(libs.security.crypto)
implementation("com.alphacephei:vosk-android:0.3.47")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
```

---

## core-network/ — Network Layer

**Namespace**: `com.masterllm.core.network`  
**Type**: Android Library  
**Lines of code**: ~170 (4 Kotlin files)

### Purpose

HuggingFace API client for model search, metadata retrieval, and authentication.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `HuggingFaceApi.kt` | 60 | Retrofit interface for HuggingFace Hub API: model search, model info, file listing. |
| `HfAuth.kt` | 21 | HF token authentication interceptor. |
| `model/NetworkModels.kt` | 40 | Network DTOs for HF API responses. |
| `di/NetworkModule.kt` | 49 | Hilt module providing Retrofit, OkHttp, and API service. |

### Dependencies

```kotlin
implementation(libs.retrofit)
implementation(libs.okhttp)
implementation(libs.gson)
```

---

## core-ollama/ — Ollama Integration

**Namespace**: `com.masterllm.core.ollama`  
**Type**: Android Library  
**Lines of code**: ~491 (3 Kotlin files)

### Purpose

Ollama API client for running models via Ollama server. Supports chat completions, model listing, and server health checks.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `api/OllamaApiService.kt` | 378 | Retrofit interface for Ollama API: `/api/chat`, `/api/tags`, `/api/show`, health check. Handles streaming responses. |
| `model/OllamaModels.kt` | 97 | DTOs: `OllamaChatRequest`, `OllamaChatResponse`, `OllamaModelInfo`, `OllamaModelDetails`. |
| `di/OllamaModule.kt` | 16 | Hilt module providing `OllamaApiService`. |

### Dependencies

```kotlin
implementation(libs.retrofit)
implementation(libs.okhttp)
implementation(libs.gson)
```

---

## core-ui/ — UI Components

**Namespace**: `com.masterllm.core.ui`  
**Type**: Android Library (Compose)  
**Lines of code**: ~559 (6 Kotlin files)

### Purpose

Shared Compose UI components and the Solace therapeutic color theme. Used by all feature modules.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `theme/Theme.kt` | 81 | `MasterLLMTheme` composable with light/dark color schemes. Therapeutic palette: calm blue (`#5B8DB8`), sage green (`#A8D5BA`), warm peach (`#F4A97F`). |
| `theme/Type.kt` | 52 | Typography scale (`MasterLLMTypography`). |
| `components/CrisisResourceBanner.kt` | 119 | Tap-to-call crisis helpline banner. Shows 988 Lifeline (US), iCall India, Vandrevala Foundation, 112/911 emergency. Dismissible. |
| `components/MarkdownMessageText.kt` | 123 | Markdown rendering in chat bubbles using Markwon library. |
| `components/SharedComponents.kt` | 170 | `GradientCard`, `EmptyState`, `LoadingScreen`, `TypingIndicator` (animated dots), `SizeBadge`. |
| `util/ConnectivityUtil.kt` | 14 | Network connectivity check utility. |

### Public APIs

```kotlin
// Theme
@Composable fun MasterLLMTheme(darkTheme: Boolean, content: @Composable () -> Unit)

// Components
@Composable fun CrisisResourceBanner(modifier: Modifier, onDismiss: () -> Unit)
@Composable fun MarkdownMessageText(markdown: String, modifier: Modifier)
@Composable fun GradientCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit)
@Composable fun TypingIndicator(modifier: Modifier)
@Composable fun EmptyState(icon: ImageVector, title: String, subtitle: String)
@Composable fun LoadingScreen(message: String)
@Composable fun SizeBadge(sizeBytes: Long)
```

### Dependencies

```kotlin
implementation(libs.compose.material3)
implementation(libs.compose.material.icons.extended)
implementation(libs.coil.compose)
implementation(libs.markwon.core)
implementation(libs.markwon.linkify)
```

---

## runtime-gguf/ — Native GGUF Inference

**Namespace**: `com.masterllm.runtime.gguf`  
**Type**: Android Library with NDK  
**Lines of code**: ~3,158 (9 Kotlin + 5 C++/CMake files)

### Purpose

Core inference engine wrapping llama.cpp via JNI. Handles model loading, text generation, multimodal (vision) support, performance tracking, and agent tool-calling. Builds multiple ARM64-optimized native libraries for different CPU feature levels.

### Key Files — Kotlin

| File | Lines | Description |
|------|-------|-------------|
| `GgufEngine.kt` | 672 | JNI wrapper for llama.cpp. Auto-detects CPU features (FP16, dotprod, SVE, i8mm) and loads the optimal native library. Provides `loadModel()`, `generate()`, `stopGeneration()`, `loadMmproj()`, `benchModel()`. |
| `LlmInferenceManager.kt` | 315 | High-level inference orchestrator. Manages model lifecycle, streaming generation with `LoadStatus` and `GenerationStatus` sealed interfaces, live performance stats. |
| `OpenClawEngine.kt` | 181 | Agent engine for tool-calling. Parses `<\|tool_call\|>` blocks from model output, executes registered tools, feeds results back. |
| `ToolRegistry.kt` | 208 | Tool definitions: `web_search`, `fetch_url`, `current_time`, `calculate`. JSON schema for each tool. |
| `InferencePerformanceTracker.kt` | 284 | Tracks tokens/sec, CPU/GPU usage, memory, battery during inference. Provides `LiveStats` data class. |
| `PerformanceUsageSampler.kt` | 130 | Samples `/proc/stat` and GPU usage during inference for performance metrics. |
| `GgufRuntimeCoordinator.kt` | 19 | Mutex-based engine lock to prevent concurrent model operations. |
| `GGUFReader.kt` | 49 | Kotlin-side GGUF file header parser (delegates to native). |
| `GgufHeaderParser.kt` | 38 | Parses GGUF metadata (architecture, context length, quantization) from file headers. |

### Key Files — C++ / Native

| File | Lines | Description |
|------|-------|-------------|
| `LLMInference.h` | 94 | C++ class declaration. Manages `llama_model`, `llama_context`, `llama_sampler`, `mtmd_context` (multimodal). |
| `LLMInference.cpp` | 498 | Implementation: `loadModel()`, `startCompletion()`, `completionLoop()`, `stopCompletion()`, `loadMmproj()`, `startCompletionWithImage()`, `benchModel()`. |
| `gguf_bridge.cpp` | 263 | JNI bridge functions mapping Kotlin calls to `LLMInference` methods. |
| `gguf_bridge_stub.cpp` | 157 | Stub implementations for non-ARM64 builds. |
| `GGUFReader.cpp` | 59 | Native GGUF file header reader using ggml. |
| `CMakeLists.txt` | 191 | Build config. Links llama.cpp, mtmd (multimodal), ggml. Builds 7 ARM64 variants with different CPU feature flags. Optional Vulkan GPU backend. |

### Native Library Variants (ARM64)

```
llama_android                          — Universal fallback
llama_android_v8_2_fp16                — ARMv8.2 + FP16
llama_android_v8_2_fp16_dotprod        — ARMv8.2 + FP16 + dotprod
llama_android_v8_4_fp16_dotprod        — ARMv8.4 + FP16 + dotprod
llama_android_v8_4_fp16_dotprod_sve    — ARMv8.4 + FP16 + dotprod + SVE
llama_android_v8_4_fp16_dotprod_i8mm   — ARMv8.4 + FP16 + dotprod + i8mm
llama_android_v8_4_fp16_dotprod_i8mm_sve — ARMv8.4 + all features
```

### Public APIs

```kotlin
// GgufEngine — JNI wrapper
@Singleton class GgufEngine {
    suspend fun loadModel(path: String, params: InferenceParams): LoadResult
    fun generate(prompt: String): Flow<String>
    suspend fun stopGeneration()
    suspend fun loadMmproj(mmprojPath: String): Boolean
    fun supportsVision(): Boolean
    suspend fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
}

// LlmInferenceManager — High-level API
@Singleton class LlmInferenceManager {
    val isModelLoaded: AtomicBoolean
    val isInferenceRunning: Boolean
    val liveStats: StateFlow<InferencePerformanceTracker.LiveStats>
    suspend fun loadModel(path: String, params: InferenceParams): Flow<LoadStatus>
    fun generate(prompt: String): Flow<GenerationStatus>
    suspend fun stopGeneration()
}

// OpenClawEngine — Agent
class OpenClawEngine {
    suspend fun executeWithTools(prompt: String, tools: List<Tool>): Flow<String>
}

// Status types
sealed interface LoadStatus { Started, Progress, Loaded, Error, Cancelled }
sealed interface GenerationStatus { Started, Generating, Complete, Error, Cancelled }
```

### Dependencies

```kotlin
implementation(project(":core-domain"))
implementation(libs.hilt.android)
implementation(libs.coroutines.core)
// Native: llama.cpp, ggml, mtmd (multimodal)
```

---

## runtime-safetensors/ — Safetensors Runtime

**Namespace**: `com.masterllm.runtime.safetensors`  
**Type**: Android Library  
**Lines of code**: ~259 (1 Kotlin file)

### Purpose

Runtime for loading and running Safetensors-format models (alternative to GGUF).

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `SafetensorsEngine.kt` | 259 | Engine for Safetensors model loading and inference. |

### Dependencies

```kotlin
implementation(project(":core-domain"))
```

---

## runtime-imagegen/ — Image Generation Runtime

**Namespace**: `com.masterllm.runtime.imagegen`  
**Type**: Android Library  
**Lines of code**: ~633 (2 Kotlin files)

### Purpose

Diffusion model runtime for image generation during roleplay sessions.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `ImageGenEngine.kt` | 568 | Diffusion pipeline engine. Loads Stable Diffusion models, runs denoising loop, provides generation progress via `ImageGenProgress` flow. |
| `ImageModelInspector.kt` | 65 | Inspects diffusion model files (UNet, VAE, tokenizer) for compatibility. |

### Public APIs

```kotlin
class ImageGenEngine {
    suspend fun loadModel(modelPath: String): Boolean
    fun generate(prompt: String, negativePrompt: String, steps: Int, cfgScale: Float, width: Int, height: Int): Flow<ImageGenProgress>
    suspend fun unload()
}

sealed interface ImageGenProgress {
    data class Step(val current: Int, val total: Int) : ImageGenProgress
    data class Complete(val imagePath: String) : ImageGenProgress
    data class Error(val message: String) : ImageGenProgress
}
```

---

## feature-chat/ — Chat Feature

**Namespace**: `com.masterllm.feature.chat`  
**Type**: Android Library (Compose)  
**Lines of code**: ~2,825 (3 Kotlin files)

### Purpose

Main chat interface. Handles conversation management, model loading, inference, multimodal (image) input, ASR (voice input), TTS (voice output), web search integration, and performance benchmarks.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `ChatScreen.kt` | 932 | Main chat UI. Conversation list sidebar, message bubbles with markdown rendering, input bar with mic/image/search buttons, streaming indicator, model status bar. |
| `ChatViewModel.kt` | 1,696 | Central ViewModel managing: conversation CRUD, model loading (GGUF/Safetensors/Ollama), streaming inference, multimodal image input, Vosk ASR, KittenTTS output, web search via OpenClaw tools, benchmark execution, thinking token parsing. |
| `TaskTemplatesScreen.kt` | 197 | Pre-built task templates for quick conversation starts. |

### ChatViewModel Data Classes

```kotlin
enum class ChatBackend { LOCAL, OLLAMA }

data class GenerationStats(
    val modelDisplayName: String, val backend: String,
    val modelLoadDurationMs: Long, val promptTokens: Int,
    val generatedTokens: Int, val firstTokenLatencyMs: Long,
    val durationMs: Long, val promptTokensPerSecond: Double,
    val decodeTokensPerSecond: Double, val threadCount: Int,
    val gpuLayers: Int, val contextSize: Int
)

enum class ModelLoadStatus { IDLE, LOADING, LOADED, ERROR }
```

### Dependencies

```kotlin
implementation(project(":core-domain"))
implementation(project(":core-data"))
implementation(project(":core-ollama"))
implementation(project(":runtime-gguf"))
implementation(project(":runtime-imagegen"))
implementation(project(":runtime-safetensors"))
implementation(project(":core-ui"))
```

---

## feature-roleplay/ — Guided Sessions

**Namespace**: `com.masterllm.feature.roleplay`  
**Type**: Android Library (Compose)  
**Lines of code**: ~1,764 (2 Kotlin files)

### Purpose

Therapeutic guided session interface with 5 pre-built templates. Manages roleplay sessions, character configuration, and model inference with specialized system prompts.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `RoleplayScreen.kt` | 665 | UI for guided sessions. Template selection grid, session configuration, chat interface with character context. Templates: Anxiety Relief, Panic Attack Support, Sleep & Rest, Daily Check-in, Crisis Support. |
| `RoleplayViewModel.kt` | 1,099 | Session management, model loading, inference with roleplay-specific system prompts, image generation integration, character visual consistency. |

### RoleplayViewModel Data Classes

```kotlin
data class RoleplayGenerationStats(
    val modelDisplayName: String, val backend: String,
    val promptTokens: Int, val generatedTokens: Int,
    val firstTokenLatencyMs: Long, val durationMs: Long
)

data class RoleplayModelRuntimeInfo(
    val modelId: String?, val modelDisplayName: String,
    val backend: String, val statusLabel: String,
    val threadCount: Int, val gpuLayers: Int
)
```

### Dependencies

```kotlin
implementation(project(":core-domain"))
implementation(project(":core-data"))
implementation(project(":runtime-gguf"))
implementation(project(":runtime-imagegen"))
implementation(project(":runtime-safetensors"))
implementation(project(":core-ui"))
```

---

## feature-settings/ — Settings

**Namespace**: `com.masterllm.feature.settings`  
**Type**: Android Library (Compose)  
**Lines of code**: ~1,870 (5 Kotlin files)

### Purpose

Settings screen for theme, voice, thinking toggle, inference parameters, crisis helplines, model management, and Ollama configuration.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `SettingsScreen.kt` | 514 | Settings UI with sections: Appearance, Voice, Thinking, Inference, Storage, Ollama, Crisis Resources, About. |
| `SettingsViewModel.kt` | 332 | Manages settings state. `SettingsAction` sealed interface for all user actions. Reads/writes via `SettingsRepository`. |
| `OllamaSettingsSection.kt` | 195 | Ollama server configuration section (host, keep-alive, system prompt, connection test). |
| `OllamaModelExplorerScreen.kt` | 663 | Browse and manage models on connected Ollama server. |
| `OllamaModelExplorerViewModel.kt` | 166 | ViewModel for Ollama model browsing. |

### SettingsViewModel Public APIs

```kotlin
data class SettingsUiState(
    val hfUsername: String, val theme: String,
    val defaultThreadCount: Int, val gpuAccelerationEnabled: Boolean,
    val modelStoragePath: String, val ollamaHost: String,
    val ollamaEnabled: Boolean, val ollamaConnectionStatus: String?
)

sealed interface SettingsAction {
    data class ThemeChanged(val theme: String) : SettingsAction
    data class ThreadCountChanged(val count: Int) : SettingsAction
    data class GpuAccelerationChanged(val enabled: Boolean) : SettingsAction
    data class OllamaHostChanged(val host: String) : SettingsAction
    data object TestOllamaConnection : SettingsAction
    // ... 10+ more actions
}
```

### Dependencies

```kotlin
implementation(project(":core-domain"))
implementation(project(":core-data"))
implementation(project(":core-ollama"))
implementation(project(":core-ui"))
implementation(project(":runtime-gguf"))
```

---

## feature-auth/ — Authentication

**Namespace**: `com.masterllm.feature.auth`  
**Type**: Android Library (Compose)  
**Lines of code**: ~303 (2 Kotlin files)

### Purpose

HuggingFace token authentication screen for accessing gated models.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `AuthScreen.kt` | 186 | Token input UI with validation and help links. |
| `AuthViewModel.kt` | 117 | Token validation via `ValidateHfTokenUseCase`. |

---

## feature-marketplace/ — Model Marketplace

**Namespace**: `com.masterllm.feature.marketplace`  
**Type**: Android Library (Compose)  
**Lines of code**: ~3,338 (2 Kotlin files)

### Purpose

HuggingFace model marketplace for browsing, searching, and downloading models.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `MarketplaceScreen.kt` | 1,897 | Search results, model cards, file picker, download progress. Filters by format (GGUF/Safetensors/Diffusers). |
| `MarketplaceViewModel.kt` | 1,441 | HF API integration, model format detection, download management. |

---

## feature-model-manager/ — Model Manager

**Namespace**: `com.masterllm.feature.model.manager`  
**Type**: Android Library (Compose)  
**Lines of code**: ~205 (2 Kotlin files)

### Purpose

Local model management: view downloaded models, delete, check storage usage.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `ModelManagerScreen.kt` | 145 | List of downloaded models with size, format, delete action. |
| `ModelManagerViewModel.kt` | 60 | Model list and deletion logic. |

---

## feature-performance/ — Performance Monitor

**Namespace**: `com.masterllm.feature.performance`  
**Type**: Android Library (Compose)  
**Lines of code**: ~880 (2 Kotlin files)

### Purpose

Real-time performance monitoring during inference: tokens/sec, CPU/GPU usage, memory, battery.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `PerformanceMonitorScreen.kt` | 850 | Charts and real-time stats display. |
| `PerformanceMonitorViewModel.kt` | 30 | Reads from `InferencePerformanceTracker`. |

---

## feature-image-gen/ — Image Generation UI

**Namespace**: `com.masterllm.feature.image.gen`  
**Type**: Android Library (Compose)  
**Lines of code**: ~429 (2 Kotlin files)

### Purpose

Image generation interface for roleplay character/scene visualization.

### Key Files

| File | Lines | Description |
|------|-------|-------------|
| `ImageGenScreen.kt` | 252 | Image generation UI with prompt input, style selector, progress display. |
| `ImageGenViewModel.kt` | 177 | Manages `ImageGenEngine`, handles generation lifecycle. |

---

## Module Line Count Summary

| Module | Kotlin Lines | Native Lines | Total |
|--------|-------------|--------------|-------|
| `app/` | 4,203 | — | 4,203 |
| `core-domain/` | 721 | — | 721 |
| `core-data/` | 1,610 | — | 1,610 |
| `core-network/` | 170 | — | 170 |
| `core-ollama/` | 491 | — | 491 |
| `core-ui/` | 559 | — | 559 |
| `runtime-gguf/` | 1,896 | 1,262 | 3,158 |
| `runtime-safetensors/` | 259 | — | 259 |
| `runtime-imagegen/` | 633 | — | 633 |
| `feature-chat/` | 2,825 | — | 2,825 |
| `feature-roleplay/` | 1,764 | — | 1,764 |
| `feature-settings/` | 1,870 | — | 1,870 |
| `feature-auth/` | 303 | — | 303 |
| `feature-marketplace/` | 3,338 | — | 3,338 |
| `feature-model-manager/` | 205 | — | 205 |
| `feature-performance/` | 880 | — | 880 |
| `feature-image-gen/` | 429 | — | 429 |
| **Total** | **21,156** | **1,262** | **22,418** |

---

## Settings.gradle.kts Module Registration

```kotlin
rootProject.name = "MasterLLM"

include(":app")

// Core modules
include(":core-data")
include(":core-domain")
include(":core-network")
include(":core-ollama")
include(":core-ui")

// Feature modules
include(":feature-performance")
include(":feature-auth")
include(":feature-marketplace")
include(":feature-model-manager")
include(":feature-chat")
include(":feature-image-gen")
include(":feature-roleplay")
include(":feature-settings")

// Runtime modules
include(":runtime-gguf")
include(":runtime-safetensors")
include(":runtime-imagegen")
```
