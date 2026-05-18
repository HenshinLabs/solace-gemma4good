# Solace — Architecture Documentation

> **Solace** is an Android mental health companion that runs **Gemma 4 E2B** locally on-device via **llama.cpp**.  
> All inference, speech recognition, and text-to-speech happen entirely on-device — no data leaves the phone.

---

## Table of Contents

1. [Overview](#overview)
2. [High-Level Architecture](#high-level-architecture)
3. [Module Structure](#module-structure)
4. [Data Flow](#data-flow)
5. [Navigation](#navigation)
6. [Native Layer (llama.cpp)](#native-layer-llamacpp)
7. [State Management](#state-management)
8. [Key Components](#key-components)
9. [Build & Dependencies](#build--dependencies)

---

## Overview

| Aspect | Detail |
|---|---|
| **App Name** | Solace |
| **Platform** | Android (minSdk 26+) |
| **LLM** | Gemma 4 E2B (GGUF format, ~3.1 GB) |
| **Multimodal** | mmproj GGUF (~941 MB) for vision |
| **Runtime** | llama.cpp via JNI (native C++) |
| **ASR** | Vosk (offline speech recognition) |
| **TTS** | KittenTtsEngine (on-device synthesis) |
| **DI** | Hilt |
| **DB** | Room |
| **UI** | Jetpack Compose |

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        PRESENTATION                             │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────────────┐  │
│  │  Chat    │ │ Roleplay │ │ Settings  │ │ Model Download   │  │
│  │  Screen  │ │  Screen  │ │  Screen   │ │    Screen        │  │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘ └────────┬─────────┘  │
│       │             │             │                │             │
│  ┌────┴─────┐ ┌────┴──────┐ ┌───┴──────┐  ┌─────┴──────────┐  │
│  │ Chat     │ │ Roleplay  │ │ Settings │  │ ModelDownload  │  │
│  │ViewModel │ │ ViewModel │ │ViewModel │  │   Manager      │  │
│  └────┬─────┘ └────┬──────┘ └───┬──────┘  └─────┬──────────┘  │
├───────┼─────────────┼───────────┼────────────────┼──────────────┤
│       │         DOMAIN         │                │              │
│  ┌────┴─────────────────────────┴────────────────┴───────────┐  │
│  │              Conversation / Message / LlmModel            │  │
│  │              InferenceParams / RoleplaySession             │  │
│  └────────────────────────┬──────────────────────────────────┘  │
├───────────────────────────┼─────────────────────────────────────┤
│       │                DATA                                    │
│  ┌────┴──────────┐ ┌────┴──────────┐ ┌─────────────────────┐   │
│  │ Room Database │ │ Repositories  │ │ SharedPreferences   │   │
│  │ (AppDatabase) │ │ (Impl classes)│ │ / DataStore         │   │
│  └───────────────┘ └───────────────┘ └─────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│                          RUNTIME                                │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  runtime-gguf                            │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │   │
│  │  │ GgufEngine  │  │ OpenClaw     │  │ ToolRegistry   │  │   │
│  │  │ (Kotlin JNI)│  │ Engine       │  │ (web_search)   │  │   │
│  │  └──────┬──────┘  └──────────────┘  └────────────────┘  │   │
│  │         │                                                │   │
│  │  ┌──────┴──────┐                                         │   │
│  │  │ gguf_bridge │  (JNI C++ functions)                    │   │
│  │  └──────┬──────┘                                         │   │
│  │         │                                                │   │
│  │  ┌──────┴──────┐                                         │   │
│  │  │ LLMInference│  (C++ class — llama.cpp)                │   │
│  │  └─────────────┘                                         │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Module Structure

Solace uses a **Gradle multi-module** architecture with clear separation of concerns.

```
Master_LLM_app/
├── app/                          # Main Android application module
├── core-data/                    # Data layer (Room, repositories)
├── core-domain/                  # Domain models (pure Kotlin)
├── core-ui/                      # Shared Compose UI components
├── core-network/                 # Network layer
├── core-ollama/                  # Ollama API integration
├── runtime-gguf/                 # llama.cpp JNI bridge (native)
├── runtime-safetensors/          # SafeTensors model support
├── runtime-imagegen/             # Image generation engine
├── feature-chat/                 # Chat UI & ViewModel
├── feature-roleplay/             # Guided therapeutic sessions
├── feature-settings/             # Settings UI
├── feature-auth/                 # Authentication
├── feature-marketplace/          # Model marketplace
├── feature-model-manager/        # Model management
├── feature-performance/          # Performance monitoring
├── feature-image-gen/            # Image generation UI
└── llama.cpp/                    # Git submodule (ggerganov/llama.cpp)
```

### Module Details

#### `app/` — Main Application Module

The entry point. Wires together all modules via Hilt and hosts the navigation graph.

| Component | Purpose |
|---|---|
| `MainActivity` | Single activity, hosts Compose nav graph |
| `MasterLLMNavHost` | Navigation host with all route definitions |
| `ModelDownloadManager` | Handles model file download/resume/verification |
| `ModelDownloadScreen` | Download progress UI shown on first launch |
| `Solace/` utilities | VoskSpeechManager, VoskModelDownloadManager, Gemma4ChatTemplate, ThinkingTokenParser, TtsTextFilter, SystemPromptLoader, SpeechRecognitionManager |
| `OpenClawEngine` | Agent orchestration engine |
| `ToolRegistry` | Registers tools (e.g., web_search) for agent use |
| `AgentViewModel` | ViewModel for agent-driven interactions |

#### `core-data/` — Data Layer

| Component | Purpose |
|---|---|
| `AppDatabase` | Room database (conversations, messages, models) |
| DAOs | Data access objects for each entity |
| `ConversationRepositoryImpl` | Conversation CRUD operations |
| `ModelRepositoryImpl` | Model metadata & download state |
| `RoleplayRepositoryImpl` | Roleplay session persistence |
| `SettingsRepositoryImpl` | App settings via DataStore |
| `BundledModelManager` | Manages pre-packaged models (Vosk, TTS) |
| `VoskSpeechManager` | Vosk ASR integration |
| `VoskModelDownloadManager` | Downloads Vosk language models |
| `KittenTtsEngine` | On-device text-to-speech |
| `TtsTextFilter` | Strips `<thinking>` blocks before TTS |

#### `core-domain/` — Domain Models

Pure Kotlin data classes with no Android dependencies.

```
Conversation          — A chat thread (id, title, timestamp)
Message               — Single message (role, content, timestamp, imageUri)
LlmModel              — Model metadata (name, path, size, format)
InferenceParams       — Generation config (temperature, top_p, max_tokens)
RoleplaySession       — Guided therapeutic session (scenario, history)
```

#### `core-ui/` — Shared UI Components

| Component | Purpose |
|---|---|
| `SolaceTheme` | Color palette, typography, shapes |
| `MarkdownMessageText` | Renders markdown in chat bubbles |
| `TypingIndicator` | Animated dots while model generates |
| `GradientCard` | Styled card with gradient background |
| `CrisisResourceBanner` | Helpline numbers shown on home screen |

#### `runtime-gguf/` — Native Runtime

The heart of on-device inference. See [Native Layer](#native-layer-llamacpp).

| Component | Purpose |
|---|---|
| `GgufEngine` | Kotlin JNI wrapper around LLMInference |
| `LLMInference` | C++ class — loads GGUF, runs inference |
| `gguf_bridge.cpp` | JNI function implementations |
| `OpenClawEngine` | Agent loop (tool calling, multi-step) |
| `ToolRegistry` | Tool definitions for agent |
| `GgufRuntimeCoordinator` | Lifecycle-aware engine management |
| `PerformanceUsageSampler` | Tracks tokens/sec, memory, CPU |
| `GGUFReader` | Parses GGUF file metadata |

---

## Data Flow

### 1. First Launch — Model Download

```
┌──────────┐     ┌───────────────────┐     ┌────────────────────┐
│ App Open │────▶│ ModelDownloadScreen│────▶│ ModelDownloadManager│
└──────────┘     └─────────┬─────────┘     └─────────┬──────────┘
                           │                         │
                     Model exists?              Download from
                           │                  HuggingFace
                      ┌────┴────┐                    │
                      │         │              ┌─────┴─────┐
                     YES        NO             │  ~3.1 GB  │
                      │         │             │  GGUF +   │
                      │         │             │  ~941 MB  │
                      │         │             │  mmproj   │
                      ▼         ▼             └───────────┘
                   HOME    Show progress
                           bar + ETA
```

### 2. Chat Inference

```
User types message
       │
       ▼
┌──────────────┐
│ ChatViewModel│
│  (StateFlow) │
└──────┬───────┘
       │
       ▼
┌──────────────┐    JNI     ┌──────────────┐    C++    ┌──────────────┐
│  GgufEngine  │──────────▶│ gguf_bridge  │─────────▶│ LLMInference │
│  (Kotlin)    │◀──────────│   .cpp       │◀─────────│  (llama.cpp) │
└──────────────┘  tokens   └──────────────┘  stream   └──────────────┘
       │
       ▼
┌──────────────┐
│  ChatScreen  │  ← Real-time token streaming via StateFlow
│  (Compose)   │
└──────────────┘
```

### 3. Vision Pipeline

```
User attaches image
       │
       ▼
Bitmap → RGB byte array
       │
       ▼
┌──────────────────────────────┐
│ GgufEngine                   │
│  .startCompletionWithImage(  │
│      prompt,                 │
│      imageBytes,             │
│      width, height           │
│  )                           │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ mtmd (multimodal) pipeline   │
│  → image encoding            │
│  → vision-language fusion    │
│  → text generation           │
└──────────────────────────────┘
```

### 4. ASR (Speech-to-Text)

```
Mic button tap
       │
       ▼
┌───────────────────┐
│ VoskSpeechManager │
│  (core-data)      │
└────────┬──────────┘
         │
    ┌────┴────┐
    │ Vosk    │  (offline, on-device)
    │ Model   │
    └────┬────┘
         │
         ▼
   Transcript → Chat input field
```

### 5. TTS (Text-to-Speech)

```
AI response received
       │
       ▼
┌──────────────┐
│ TtsTextFilter│  ← Strips <thinking> blocks
└──────┬───────┘
       │
       ▼
┌──────────────────┐
│ KittenTtsEngine  │  (on-device synthesis)
└────────┬─────────┘
         │
         ▼
    Audio playback
```

### 6. Web Search (Tool Use)

```
User taps Search button
       │
       ▼
┌──────────────────┐
│ ToolRegistry     │
│  .web_search()   │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ DuckDuckGo HTML  │  ← Scraped, no API key needed
│  search results  │
└────────┬─────────┘
         │
         ▼
   Results injected into context
         │
         ▼
   GgufEngine generates answer
```

---

## Navigation

### Route Graph

```
MODEL_DOWNLOAD ─────────────────────────────────────────────────┐
       │                                                        │
       ▼ (model ready)                                          │
     HOME ──────────────────────────────────────────────────────┤
       │                                                        │
       ├────────▶ CHAT                                          │
       │                                                        │
       ├────────▶ ROLEPLAY                                      │
       │                                                        │
       └────────▶ SETTINGS                                      │
                                                                  │
  ┌──────────────────────────────────────────────────────────────┘
  │
  ▼
Bottom Navigation Bar
┌────────┬────────┬──────────┬──────────┐
│  Home  │  Chat  │ Sessions │ Settings │
│        │        │(Roleplay)│          │
└────────┴────────┴──────────┴──────────┘
```

### Route Definitions

| Route | Screen | Entry Condition |
|---|---|---|
| `MODEL_DOWNLOAD` | ModelDownloadScreen | Model files not present |
| `HOME` | HomeScreen | Model loaded successfully |
| `CHAT` | ChatScreen | From Home or bottom bar |
| `ROLEPLAY` | RoleplayScreen | From Home or bottom bar ("Sessions") |
| `SETTINGS` | SettingsScreen | From Home or bottom bar |

---

## Native Layer (llama.cpp)

Solace embeds **llama.cpp** as a git submodule and compiles it with **mtmd (multimodal)** support for vision capabilities.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Kotlin / Android                          │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  GgufEngine  (runtime-gguf module)                     │  │
│  │    external fun loadModel(path, params): Long          │  │
│  │    external fun startCompletion(prompt): Long          │  │
│  │    external fun completionLoop(handle): String?        │  │
│  │    external fun stopCompletion(handle)                 │  │
│  │    external fun loadMmproj(path): Boolean              │  │
│  │    external fun startCompletionWithImage(...)          │  │
│  │    external fun supportsVision(): Boolean              │  │
│  └───────────────────────┬────────────────────────────────┘  │
│                          │ JNI                                │
├──────────────────────────┼────────────────────────────────────┤
│                    C++ / Native                               │
│  ┌───────────────────────┴────────────────────────────────┐  │
│  │  gguf_bridge.cpp                                       │  │
│  │    Java_*_loadModel()     → LLMInference::loadModel()  │  │
│  │    Java_*_startCompletion()                            │  │
│  │    Java_*_completionLoop()                             │  │
│  │    Java_*_stopCompletion()                             │  │
│  │    Java_*_loadMmproj()                                 │  │
│  │    Java_*_startCompletionWithImage()                   │  │
│  └───────────────────────┬────────────────────────────────┘  │
│                          │                                    │
│  ┌───────────────────────┴────────────────────────────────┐  │
│  │  LLMInference  (C++ class)                             │  │
│  │    - Loads GGUF model into memory                      │  │
│  │    - Manages llama_context, llama_model                 │  │
│  │    - Token-by-token generation loop                    │  │
│  │    - mtmd pipeline for image+text                      │  │
│  └───────────────────────┬────────────────────────────────┘  │
│                          │                                    │
│  ┌───────────────────────┴────────────────────────────────┐  │
│  │  llama.cpp libraries (pre-compiled .so)                 │  │
│  │    - libllama.so        (core inference)                │  │
│  │    - libggml.so         (tensor operations)             │  │
│  │    - libmtmd.so         (multimodal/vision)             │  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### ARM64 Optimized Variants

The build produces multiple `.so` variants for different ARM64 feature levels:

| Library | CPU Feature | Target Devices |
|---|---|---|
| `libllama-v82fp16.so` | FP16 (ARMv8.2) | Most modern phones |
| `libllama-v84dotprod.so` | SDOT (ARMv8.4) | Snapdragon 8 Gen 1+ |
| `libllama-sve.so` | SVE | ARMv9 (Dimensity 9000+) |
| `libllama-i8mm.so` | I8MM | Advanced int8 matmul |

### JNI Function Map

| Kotlin Method | C++ Function | Description |
|---|---|---|
| `loadModel(path, params)` | `Java_*_loadModel` | Load GGUF, allocate context |
| `startCompletion(prompt)` | `Java_*_startCompletion` | Begin text generation |
| `completionLoop(handle)` | `Java_*_completionLoop` | Generate next token (polling) |
| `stopCompletion(handle)` | `Java_*_stopCompletion` | Cancel ongoing generation |
| `loadMmproj(path)` | `Java_*_loadMmproj` | Load vision projector weights |
| `startCompletionWithImage(prompt, img, w, h)` | `Java_*_startCompletionWithImage` | Vision+text generation |
| `supportsVision()` | `Java_*_supportsVision` | Check if mmproj loaded |

### Build Configuration

```cmake
# CMakeLists.txt highlights
cmake_minimum_required(VERSION 3.18)
project(gguf_bridge)

# Compile llama.cpp with mtmd
add_subdirectory(llama.cpp)

# JNI bridge
add_library(gguf_bridge SHARED gguf_bridge.cpp)
target_link_libraries(gguf_bridge llama ggml mtmd)
```

---

## State Management

Solace uses a layered state management approach.

```
┌──────────────────────────────────────────────────────┐
│                    UI Layer                           │
│  Compose Screens observe StateFlow from ViewModels   │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│                  ViewModel Layer                      │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐  │
│  │ ChatViewModel│ │RoleplayVM    │ │SettingsVM    │  │
│  │ (StateFlow)  │ │(StateFlow)   │ │(StateFlow)   │  │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘  │
└─────────┼────────────────┼────────────────┼──────────┘
          │                │                │
┌─────────┴────────────────┴────────────────┴──────────┐
│               Dependency Injection (Hilt)             │
│  Binds repository interfaces to implementations      │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────┴─────────────────────────────┐
│                  Data Layer                           │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │    Room     │  │ SharedPrefs  │  │  DataStore   │  │
│  │ (messages,  │  │ (model       │  │ (settings,   │  │
│  │  convos)    │  │  download    │  │  preferences)│  │
│  │             │  │  state)      │  │              │  │
│  └────────────┘  └──────────────┘  └──────────────┘  │
└──────────────────────────────────────────────────────┘
```

### Storage Strategy

| Data | Storage | Rationale |
|---|---|---|
| Conversations & Messages | Room (SQLite) | Structured, queryable, relational |
| Model download state | SharedPreferences | Simple key-value, fast reads |
| App settings & preferences | DataStore | Type-safe, coroutine-native |
| Model files (GGUF) | Internal storage | Large binary files, app-private |

### Threading Model

```
Main Thread          │  Background Threads
─────────────────────┼──────────────────────────
Compose recomposition│  Room queries (IO)
ViewModel updates    │  llama.cpp inference (native thread)
Navigation           │  Model download (IO)
                     │  Vosk ASR (native thread)
                     │  TTS playback (audio thread)
```

---

## Key Components

### GgufEngine (runtime-gguf)

The primary interface between Kotlin and llama.cpp.

```kotlin
class GgufEngine {
    // Lifecycle
    external fun loadModel(modelPath: String, params: InferenceParams): Long
    external fun freeModel(handle: Long)

    // Text generation
    external fun startCompletion(prompt: String): Long
    external fun completionLoop(handle: Long): String?  // returns next token or null
    external fun stopCompletion(handle: Long)

    // Vision
    external fun loadMmproj(mmprojPath: String): Boolean
    external fun startCompletionWithImage(
        prompt: String, imageBytes: ByteArray, width: Int, height: Int
    ): Long
    external fun supportsVision(): Boolean
}
```

### ChatViewModel (feature-chat, 1696 lines)

The largest ViewModel. Manages:

- Message history and conversation state
- Token streaming from GgufEngine
- Image attachment and vision inference
- Web search tool invocation
- Thinking token parsing (`<thinking>` blocks)
- Auto-scroll and typing indicator state

### RoleplayViewModel (feature-roleplay, 1099 lines)

Manages guided therapeutic roleplay sessions:

- Session state machine (intro → scenario → reflection → summary)
- Therapeutic prompt injection
- Session history and progress tracking

### OpenClawEngine (runtime-gguf)

Agent orchestration layer that enables multi-step tool use:

```
User query
    │
    ▼
OpenClawEngine
    │
    ├─ Parse intent
    ├─ Select tool (ToolRegistry)
    ├─ Execute tool (e.g., web_search)
    ├─ Inject results into context
    └─ Generate final response via GgufEngine
```

### ToolRegistry (runtime-gguf)

Registers available tools for the agent:

| Tool | Description |
|---|---|
| `web_search` | DuckDuckGo HTML search, returns top results |

### CrisisResourceBanner (core-ui)

A UI component displayed prominently on the home screen showing mental health crisis helpline numbers. Always visible as a safety measure.

---

## Build & Dependencies

### Gradle Configuration

```groovy
// Root build.gradle
plugins {
    id 'com.android.application' version '8.x'
    id 'org.jetbrains.kotlin.android' version '2.x'
    id 'com.google.dagger.hilt.android' version '2.x'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.x'
}
```

### Key Dependencies

| Category | Library | Purpose |
|---|---|---|
| **UI** | Jetpack Compose | Declarative UI toolkit |
| **DI** | Hilt | Dependency injection |
| **DB** | Room | Local SQLite ORM |
| **Settings** | DataStore Preferences | Type-safe settings |
| **Navigation** | Compose Navigation | Route management |
| **ASR** | Vosk | Offline speech recognition |
| **TTS** | KittenTtsEngine | On-device text-to-speech |
| **Native** | llama.cpp (submodule) | LLM inference |
| **Network** | OkHttp/Retrofit | Model downloads |

### Git Submodule

```bash
# llama.cpp is pinned as a submodule
git submodule update --init --recursive

# Build produces ARM64 .so files
# Output: runtime-gguf/src/main/jniLibs/arm64-v8a/
```

### Model Files

| File | Size | Source | Purpose |
|---|---|---|---|
| `gemma-4-e2b-Q4_K_M.gguf` | ~3.1 GB | HuggingFace | Main LLM weights |
| `mmproj.gguf` | ~941 MB | HuggingFace | Vision projector |
| `vosk-model-*` | ~50 MB | Vosk | Speech recognition |

---

## Security & Privacy

- **All inference is on-device** — no API calls for LLM, ASR, or TTS
- **Model files stored in app-private internal storage**
- **No analytics or telemetry**
- **Web search only sends queries to DuckDuckGo** (no user identification)
- **Crisis resources are hardcoded** — always available even offline

---

## Performance Considerations

- **`PerformanceUsageSampler`** monitors tokens/sec, memory usage, and CPU load in real-time
- **ARM64 variant selection** ensures optimal SIMD instructions for the device's CPU
- **Token streaming** provides responsive UX — tokens appear as generated, not after completion
- **Model quantization** (Q4_K_M) balances quality vs. memory footprint for mobile
- **Background thread** for inference keeps UI thread unblocked

---

*Last updated: 2026-05-18*
