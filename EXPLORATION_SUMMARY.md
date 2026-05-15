# Codebase Exploration Summary — Master LLM → Solace Migration

**Date**: 2026-05-15
**Purpose**: Full audit of the Master LLM Android app prior to Gemma 4 E2B migration and Solace mental health companion transformation.

---

## 0.1 — Annotated File Tree

```
Master_LLM_app/
├── build.gradle.kts                    # Root build — plugin version catalog
├── settings.gradle.kts                 # 15 Gradle modules declared
├── gradle.properties                   # JVM/Kotlin/Android build flags
├── gradle/libs.versions.toml           # Central dependency version catalog
├── gradlew / gradlew.bat              # Gradle wrapper scripts
├── .gitmodules                         # Submodules: llama.cpp, ollama, opencode
├── .gitignore
├── THIRD_PARTY_NOTICES.md
├── branding/masterllm-logo.svg         # Current app logo (SVG source)
│
├── app/                                # Main application module
│   ├── build.gradle.kts                # App build config, SDK 36, NDK, ONNX dep
│   ├── proguard-rules.pro              # ProGuard: Hilt, Retrofit, Gson, Ollama, perf tracker
│   ├── src/main/
│   │   ├── AndroidManifest.xml         # [RELEVANT] Permissions, cleartext, services
│   │   ├── assets/
│   │   │   ├── kittentts/              # [TTS] Bundled ONNX TTS model
│   │   │   │   ├── config.json
│   │   │   │   ├── kitten_tts_nano_v0_8.onnx  # 24 MB neural vocoder
│   │   │   │   └── voices.npz                 # Speaker embeddings (8 voices)
│   │   │   └── turnip/                 # Vulkan ICD driver for GPU acceleration
│   │   │       ├── libvulkan_freedreno.so
│   │   │       └── icd.d/freedreno_icd.aarch64.json
│   │   ├── kotlin/com/masterllm/app/
│   │   │   ├── MainActivity.kt         # [UI] Single activity, edge-to-edge, theme
│   │   │   ├── MasterLLMApplication.kt # [APP] Hilt entry, Timber, BundledModelManager init
│   │   │   ├── AppThemeViewModel.kt    # [UI] Theme state management
│   │   │   ├── navigation/
│   │   │   │   ├── MasterLLMNavHost.kt # [UI] 5-tab bottom nav, 15 destinations
│   │   │   │   ├── GgufEngineViewModel.kt # [INFERENCE] Engine injection wrapper
│   │   │   │   ├── KittenTtsEngine.kt  # [TTS] ONNX neural TTS engine
│   │   │   │   ├── VoiceScreen.kt      # [ASR/TTS] Speech recognition + TTS UI
│   │   │   │   ├── AgentScreen.kt      # [UI] Agent tool-calling chat UI
│   │   │   │   └── OllamaServeScreen.kt # [UI] Local Ollama server management
│   │   │   └── openclaw/
│   │   │       ├── AgentViewModel.kt   # [AGENT] Multi-turn agent state management
│   │   │       ├── OpenClawEngine.kt   # [AGENT] Tool-calling inference loop
│   │   │       └── ToolRegistry.kt     # [AGENT] 6 built-in tools
│   │   └── res/
│   │       ├── drawable/               # App logo vectors
│   │       ├── mipmap-anydpi-v26/      # Adaptive icon XML
│   │       ├── values/
│   │       │   ├── strings.xml         # [RELEVANT] App name "Master LLM"
│   │       │   ├── themes.xml          # [UI] App theme definition
│   │       │   └── ic_launcher_colors.xml # Launcher icon colors
│   │       └── xml/
│   │           └── network_security_config.xml # Cleartext allowed globally
│
├── core-domain/                        # Pure Kotlin domain layer
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../domain/
│       ├── model/
│       │   ├── LlmModel.kt            # [MODEL] 9 data classes, 6 enums
│       │   └── TextRuntimeModelResolver.kt # [MODEL] Quant scoring, format fallback
│       ├── repository/
│       │   └── ModelRepository.kt      # [DATA] 5 repository interfaces
│       └── usecase/
│           └── UseCases.kt             # [LOGIC] 8 domain use cases
│
├── core-data/                          # Room DB + DataStore + repos
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../data/
│       ├── BundledModelManager.kt      # [MODEL] Qwen3.5-0.8B asset extraction
│       ├── db/
│       │   ├── AppDatabase.kt          # [DATA] Room v2, 5 entities
│       │   ├── Daos.kt                 # [DATA] 5 DAOs
│       │   └── ModelEntity.kt          # [DATA] Room entity definitions
│       ├── di/DataModule.kt            # [DI] DB + DAO providers
│       ├── mapper/EntityMappers.kt     # [DATA] Entity ↔ domain mappers
│       └── repository/
│           ├── ConversationRepositoryImpl.kt
│           ├── ModelRepositoryImpl.kt
│           ├── RoleplayRepositoryImpl.kt
│           ├── CharacterVisualCacheRepositoryImpl.kt
│           └── SettingsRepositoryImpl.kt # [SETTINGS] Encrypted HF token + DataStore
│
├── core-network/                       # HuggingFace API (Retrofit)
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../network/
│       ├── HuggingFaceApi.kt           # [NETWORK] Search, download, whoami
│       ├── HfAuth.kt                   # [AUTH] Token normalization
│       ├── model/NetworkModels.kt      # [NETWORK] JSON response classes
│       └── di/NetworkModule.kt         # [DI] OkHttp + Retrofit singleton
│
├── core-ollama/                        # Ollama API (raw OkHttp, ndjson streaming)
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../ollama/
│       ├── api/OllamaApiService.kt     # [NETWORK] Chat, pull, curated library
│       ├── model/OllamaModels.kt       # [NETWORK] Data classes
│       └── di/OllamaModule.kt          # [DI] Singleton provider
│
├── core-ui/                            # Shared Compose UI
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../ui/
│       ├── components/
│       │   ├── MarkdownMessageText.kt  # [UI] Markwon markdown rendering + code copy
│       │   └── SharedComponents.kt     # [UI] GradientCard, EmptyState, Loading, etc.
│       ├── theme/
│       │   ├── Theme.kt               # [UI] Dark/light color schemes (green/brown)
│       │   └── Type.kt                # [UI] Typography definitions
│       └── util/ConnectivityUtil.kt    # [UTIL] Network connectivity check
│
├── runtime-gguf/                       # LLM inference engine (JNI → llama.cpp)
│   ├── build.gradle.kts               # NDK: arm64-v8a + x86_64, C++20
│   └── src/
│       ├── main/
│       │   ├── kotlin/.../gguf/
│       │   │   ├── GgufEngine.kt       # [INFERENCE] Core engine, CPU detection, streaming
│       │   │   ├── GGUFReader.kt       # [INFERENCE] JNI GGUF metadata reader
│       │   │   ├── GgufHeaderParser.kt # [INFERENCE] Pure Kotlin GGUF parser
│       │   │   ├── GgufRuntimeCoordinator.kt # [INFERENCE] Thread-safe mutex wrapper
│       │   │   ├── LlmInferenceManager.kt    # [INFERENCE] State management, flows
│       │   │   ├── InferencePerformanceTracker.kt # [PERF] CPU/GPU/thermal monitoring
│       │   │   └── PerformanceUsageSampler.kt     # [PERF] CPU/GPU snapshot deltas
│       │   └── cpp/
│       │       ├── CMakeLists.txt       # [BUILD] Multi-ABI llama.cpp builds, Vulkan
│       │       ├── LLMInference.h       # [NATIVE] C++ inference class header
│       │       ├── LLMInference.cpp     # [NATIVE] llama.cpp wrapper implementation
│       │       ├── GGUFReader.cpp       # [NATIVE] JNI GGUF metadata parsing
│       │       ├── gguf_bridge.cpp      # [NATIVE] JNI bridge (15+ exported functions)
│       │       └── gguf_bridge_stub.cpp # [NATIVE] Dev/test stub
│       ├── test/                        # Unit tests
│       └── androidTest/                 # On-device inference tests
│
├── runtime-safetensors/                # SafeTensors weight loading
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../safetensors/
│       └── SafetensorsEngine.kt        # [INFERENCE] F16/BF16/F32 tensor parsing
│
├── runtime-imagegen/                   # Image generation (Kotlin diffusion pipeline)
│   ├── build.gradle.kts
│   └── src/main/kotlin/.../imagegen/
│       ├── ImageGenEngine.kt           # [INFERENCE] Diffusion pipeline
│       └── ImageModelInspector.kt      # [INFERENCE] Backend detection
│
├── feature-chat/                       # Main chat interface
│   └── src/main/kotlin/.../chat/
│       ├── ChatScreen.kt              # [UI] 1357 lines — dual-pane chat
│       ├── ChatViewModel.kt           # [LOGIC] 1443 lines — inference orchestration
│       └── TaskTemplatesScreen.kt     # [UI] 10 task template presets
│
├── feature-settings/                   # Settings hub
│   └── src/main/kotlin/.../settings/
│       ├── SettingsScreen.kt          # [UI] HF token, GPU, threads, theme
│       ├── SettingsViewModel.kt       # [LOGIC] Settings persistence
│       ├── OllamaModelExplorerScreen.kt # [UI] Ollama library browser
│       ├── OllamaModelExplorerViewModel.kt
│       └── OllamaSettingsSection.kt   # [UI] Ollama config section
│
├── feature-auth/                       # HuggingFace authentication
├── feature-model-manager/              # Downloaded model management
├── feature-marketplace/                # HF model discovery + download
├── feature-performance/                # Real-time inference monitoring
├── feature-roleplay/                   # Character roleplay with images
├── feature-image-gen/                  # Text-to-image generation
│
├── testing/
│   ├── testing-fixtures/               # TestFixtures.sampleGgufModel()
│   ├── testing-robot/                  # ChatRobot Compose UI test helper
│   └── testing-shared/                 # FakeTokenRepository, MainDispatcherRule
│
├── llama.cpp/                          # Git submodule (not initialized, empty)
├── ollama/                             # Git submodule
├── opencode/                           # Git submodule
│
├── docs/
│   ├── architecture/modules.md         # Module dependency documentation
│   ├── architecture/runtime.md         # Runtime module documentation
│   ├── api/                            # API documentation
│   ├── development/                    # Build/env/testing docs
│   └── autoresearch/                   # Auto-research specs
│
└── release-notes/                      # Version release notes (v1.0.1 → v1.0.33)
```

---

## 0.2 — Build System Audit

### Root Build (`build.gradle.kts`)
- Plugin version catalog based
- Kotlin 2.1.0, AGP via version catalog

### App Build (`app/build.gradle.kts`)
| Field | Value |
|-------|-------|
| compileSdk | 36 |
| minSdk | 31 (Android 12) |
| targetSdk | 36 |
| Kotlin JVM target | 17 |
| NDK ABIs | arm64-v8a, x86_64 |

### Dependencies (from `gradle/libs.versions.toml`)
| Library | Version |
|---------|---------|
| Kotlin | 2.1.0 |
| Compose BOM | 2024.12 |
| Room | 2.6.1 |
| Retrofit | 2.11.0 |
| Hilt | (via version catalog) |
| Coroutines | 1.9.0 |
| ONNX Runtime Android | 1.18.0 |

### Native Build
- CMake 3.22.1, C++20
- 7 ARM64 library variants (v8.2 → v8.4 with optional SVE/i8mm)
- Plus x86_64 generic
- Vulkan support optional
- Links against llama.cpp submodule at `../../../../llama.cpp`

### Model Bundling
- **BundledModelManager.kt** copies `Qwen3.5-0.8B-Q4_K_M.gguf` from APK `assets/` to `context.filesDir/models/` on first launch
- Constants: MODEL_ID="bundled_qwen3.5_0.8b", CONTEXT_LENGTH=262144, architecture="qwen35"

---

## 0.3 — LlamaCPP Integration Audit

### Submodule Status
- **Pinned commit**: `c08d28d08871715fd68accffaeeb76ddcaede658` (build b39)
- **Status**: Not initialized (directory is empty)
- **Gemma 4 support**: YES — `LLM_ARCH_GEMMA4` present at this commit

### JNI Bridge Files

| File | Role |
|------|------|
| `runtime-gguf/src/main/cpp/gguf_bridge.cpp` | JNI marshaling — 15+ exported functions |
| `runtime-gguf/src/main/cpp/LLMInference.h` | C++ class header |
| `runtime-gguf/src/main/cpp/LLMInference.cpp` | llama.cpp wrapper (model load, sampling, generation) |
| `runtime-gguf/src/main/cpp/GGUFReader.cpp` | GGUF metadata parsing (context size, chat template) |
| `runtime-gguf/src/main/cpp/gguf_bridge_stub.cpp` | Development/test stub |

### Native Library Loading (`GgufEngine.kt`)
- Reads `/proc/cpuinfo` for ARM features (fp16, dotprod, SVE, i8mm)
- Loads best-match library: `llama_android_v8_4_fp16_dotprod_i8mm_sve` → fallback chain → `llama_android`
- Emulator detection via Build.HARDWARE

### Model Loading Path
- `GgufEngine.load(modelPath, params)` → JNI `loadModel()` → `LLMInference::loadModel()`
- Path comes from `BundledModelManager.getModelPath()` → `context.filesDir/models/{filename}`

### Inference Parameters (from `LLMInference.h`)
- `loadModel(path, nCtx, nThreads, nGpuLayers, nBatch, nUbatch, temperature, topP, topK, minP, repeatPenalty, flashAttn, mmap, mlock, chatTemplate, maxTokens, ropeFreqBase)`
- Sampling chain: top_k → top_p → min_p → repeat_penalty → temperature → distribution

### Token Generation Loop
- `startCompletion()` → `completionLoop()` (returns next token string) → `stopCompletion()`
- Kotlin Flow wrapping: `GgufEngine.getResponseAsFlow()` emits tokens as `Flow<String>`

### Chat Template
- Read from GGUF metadata via `GGUFReader.getChatTemplate()`
- Fallback to hardcoded default templates (standard + Qwen3.5 specific)
- Format: `<|im_start|>role\ncontent\n<|im_end|>\n`

---

## 0.4 — Existing Model Identification

| Property | Value |
|----------|-------|
| Model | Qwen3.5-0.8B-Q4_K_M |
| Format | GGUF |
| Parameters | 0.8B |
| File | Bundled in APK `assets/` directory |
| Storage | Extracted to `context.filesDir/models/` |
| Architecture string | "qwen35" |
| Context length | 262,144 |

### Hardcoded References to Old Model

| Location | Reference |
|----------|-----------|
| `core-data/.../BundledModelManager.kt` | MODEL_ID, MODEL_FILENAME, CONTEXT_LENGTH, architecture constant |
| `core-data/.../BundledModelManager.kt` | `getRecommendedInferenceParams()` — 14 params tuned for Qwen3.5 |
| `app/.../MasterLLMApplication.kt` | `BundledModelManager.initialize(this)` call |
| `runtime-gguf/.../GgufEngine.kt` | Default chat template strings for `<\|im_start\|>` format |
| `runtime-gguf/.../GgufEngine.kt` | `QWEN_CHAT_TEMPLATE` constant |
| `app/src/main/assets/` | The actual model binary (if present in APK) |

---

## 0.5 — TTS Integration Audit

### Microsoft Edge / Azure TTS
**NOT PRESENT.** No Microsoft Cognitive Services Speech SDK dependency exists in this codebase. The only Microsoft dependency is ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android:1.18.0`) used by KittenTTS for inference.

### Existing TTS Implementation

| Engine | Type | Status |
|--------|------|--------|
| **KittenTTS** | ONNX neural vocoder (24 MB) | Primary, offline, 8 voices |
| **Android Built-in** | System TextToSpeech | Fallback |

- **KittenTTS** (`KittenTtsEngine.kt`): Character-level tokenization → ONNX session → PCM16 → AudioTrack
- **Non-streaming**: Entire audio generated, then played
- **No SSML support**: Plain text only
- **Trigger**: Manual "Speak" button press — NOT auto-triggered after inference

---

## 0.6 — ASR (Speech-to-Text) Integration Audit

### Implementation
- **API**: Android `SpeechRecognizer` (built-in, requires network)
- **Location**: `VoiceScreen.kt` lines 117-330
- **Language**: `RecognizerIntent.EXTRA_LANGUAGE` used but locale not explicitly set
- **Partial results**: Supported via `onPartialResults()` callback

### Permission Handling
- **CRITICAL BUG**: `RECORD_AUDIO` permission is checked and requested at runtime (`VoiceScreen.kt` lines 109, 269) but **NOT declared in `AndroidManifest.xml`**
- This means ASR will silently fail — the permission dialog will not appear

### Integration Status
- **DISCONNECTED**: Recognized text is stored in a `transcript` state variable but NOT automatically sent to the inference pipeline
- UI mentions downloadable models (Whisper, Wav2Vec2) but these are **placeholder UI only** — not implemented

---

## 0.7 — UI & UX Audit

### Architecture
- **Single Activity**: `MainActivity.kt` with Jetpack Compose
- **Navigation**: 5-tab bottom nav (Home, Chat, Marketplace, Voice, Settings) + 9 secondary destinations
- **State Pattern**: Immutable `UiState` + sealed `Action` + `StateFlow` in every ViewModel

### Chat Interface (`feature-chat/`)
- **ChatScreen.kt** (1357 lines): Dual-pane — conversation list + chat messages
- `MessageBubble` composable for each message
- `ChatInputBar` with text input, image attachment, task templates
- `ModelConfigurationDialog` for inference parameter sliders
- **No existing thinking-token display mechanism**

### Existing Logo/Icon Files
| File | Purpose |
|------|---------|
| `res/drawable/ic_app_logo.xml` | App logo vector |
| `res/drawable/ic_launcher_background.xml` | Launcher background |
| `res/drawable/ic_launcher_foreground.xml` | Launcher foreground |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | Round adaptive icon |
| `res/values/ic_launcher_colors.xml` | Icon color values |
| `branding/masterllm-logo.svg` | SVG source logo |

### Theme
- Dark: Primary green (#7AC67A), background #12100E
- Light: Primary green (#4CAF50), background #FAF7F2
- Material3 color scheme with warm earth tones

---

## 0.8 — System Prompt & Conversation Logic Audit

### Current System Prompt
- **No dedicated system prompt file exists**
- Chat templates are inline in `GgufEngine.kt` (default + Qwen3.5 variants)
- System prompt content is set per-conversation via `Conversation.systemPrompt` field
- Task templates in `TaskTemplatesScreen.kt` provide 10 preset system prompts (Code Assistant, Creative Writer, etc.)

### Conversation History Management
- **Room database** (`ConversationEntity` + `MessageEntity` tables)
- Messages stored with role (USER/ASSISTANT/SYSTEM), content, timestamp
- `CompactConversationUseCase`: Summarizes old messages, keeps last 6 (3 pairs)
- System prompt prepended as first user turn or via chat template

### Special Token Handling
- Current: `<|im_start|>` / `<|im_end|>` (Qwen/ChatML format)
- No existing `<think>` / `</think>` handling
- No existing `<start_of_turn>` / `<end_of_turn>` handling

---

## 0.9 — Permissions & Manifest Audit

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

**Missing**: `RECORD_AUDIO` (required for ASR)

### Other Manifest Attributes
- `android:usesCleartextTraffic="true"` (for Ollama localhost)
- `networkSecurityConfig` allowing all cleartext
- Single activity: `MainActivity`
- No `<uses-feature>` declarations for microphone

---

## 0.10 — Change Plan: Files to Modify, Create, or Delete

### Files to CREATE

| File | Purpose |
|------|---------|
| `app/src/main/kotlin/.../solace/ModelDownloadManager.kt` | Download-on-first-launch for Gemma 4 E2B |
| `app/src/main/kotlin/.../solace/ModelDownloadScreen.kt` | Full-screen download progress UI |
| `app/src/main/kotlin/.../solace/ThinkingTokenParser.kt` | Gemma 4 thinking channel parser |
| `app/src/main/kotlin/.../solace/TtsTextFilter.kt` | Strip thinking/markdown from TTS input |
| `app/src/main/kotlin/.../solace/SpeechRecognitionManager.kt` | Enhanced ASR manager |
| `app/src/main/res/raw/system_prompt.txt` | Solace mental health companion prompt |
| `app/src/main/res/values/colors_solace.xml` | Therapeutic color palette |
| `app/src/main/res/drawable/ic_solace_logo.xml` | New vector logo |
| `lint.xml` | Static analysis configuration |
| `keystore.properties.template` | Release signing template |
| `RELEASE_CHECKLIST.md` | Pre-release verification checklist |
| `HANDOFF.md` | Migration handoff documentation |

### Files to MODIFY

| File | Changes |
|------|---------|
| `core-data/.../BundledModelManager.kt` | Replace Qwen3.5 refs with Gemma 4 E2B constants |
| `runtime-gguf/.../GgufEngine.kt` | Update default chat template for Gemma 4 format, update default params |
| `feature-chat/.../ChatScreen.kt` | Add thinking token collapsible UI section |
| `feature-chat/.../ChatViewModel.kt` | Integrate ThinkingTokenParser, system prompt loading |
| `feature-settings/.../SettingsScreen.kt` | Add thinking budget, voice, context length settings |
| `feature-settings/.../SettingsViewModel.kt` | Persist new settings |
| `core-data/.../repository/SettingsRepositoryImpl.kt` | Add new preference keys |
| `core-domain/.../repository/ModelRepository.kt` | Add settings interface methods if needed |
| `app/src/main/AndroidManifest.xml` | Add RECORD_AUDIO permission |
| `app/.../navigation/VoiceScreen.kt` | Wire ASR to inference, add TTS filtering |
| `app/.../navigation/KittenTtsEngine.kt` | Add streaming sentence-boundary TTS |
| `app/.../navigation/MasterLLMNavHost.kt` | Add model download screen route |
| `app/.../MasterLLMApplication.kt` | Replace BundledModelManager init with download check |
| `app/src/main/res/values/strings.xml` | Change app name to "Solace" |
| `app/src/main/res/values/themes.xml` | Update to Solace color palette |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Point to new logo |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Point to new logo |
| `app/build.gradle.kts` | Add MODEL_DOWNLOAD_URL, MODEL_SHA256, MODEL_FILENAME buildConfig |
| `proguard-rules.pro` | Add rules for new classes |

### Files to DELETE

| File | Reason |
|------|--------|
| Bundled Qwen3.5 GGUF asset (if present in assets/) | Replaced by download-on-launch Gemma 4 |
| `branding/masterllm-logo.svg` | Replaced by Solace branding |

### Hardcoded Strings Referencing Old Model

| Location | String/Constant |
|----------|----------------|
| `BundledModelManager.kt` | `MODEL_ID = "bundled_qwen3.5_0.8b"` |
| `BundledModelManager.kt` | `MODEL_FILENAME` (Qwen3.5 GGUF filename) |
| `BundledModelManager.kt` | `CONTEXT_LENGTH = 262144` |
| `BundledModelManager.kt` | `architecture = "qwen35"` |
| `GgufEngine.kt` | `QWEN_CHAT_TEMPLATE` constant |
| `GgufEngine.kt` | `DEFAULT_CONTEXT_SIZE = 1024L` |

---

## Risk Areas and Unknowns

1. **Gemma 4 chat template differs from prompt spec**: The reference prompt assumes `<start_of_turn>`/`<end_of_turn>` and `<think>`/`</think>`. Actual Gemma 4 uses `<|turn>`/`<turn|>` and `<|channel>thought`/`<channel|>`. All template code must use verified tokens.

2. **Thinking is configurable, not always-on**: The reference prompt states thinking is "native and non-optional." In reality, Gemma 4 E2B thinking is triggered by `<|think|>` token in the system prompt and can be disabled. When disabled on E2B, NO thinking tags are produced.

3. **Model size**: Gemma 4 E2B Q4_K_M is 3.11 GB (unsloth), not "1.2-1.6 GB" as estimated. This affects download UX and storage requirements.

4. **No Microsoft Speech SDK**: The reference prompt assumes Microsoft Edge/Azure TTS exists. It does not. TTS will use KittenTTS (ONNX) and Android built-in TTS with enhanced configuration.

5. **llama.cpp submodule not initialized**: Must run `git submodule update --init llama.cpp` before building. The pinned commit supports Gemma 4 but 2 follow-up patches are missing (BPE byte token fix, add_bos conversion flag).

6. **RoPE configuration**: Gemma 4 uses dual RoPE — sliding attention at theta=10000.0, full attention at theta=1000000.0 with proportional scaling. The `ropeFreqBase` parameter in the JNI bridge may need verification against llama.cpp's handling.

7. **RECORD_AUDIO permission**: Missing from manifest — must be added before ASR can work.

8. **ASR disconnected from inference**: Currently captured text is not sent to the LLM — must be wired up.
