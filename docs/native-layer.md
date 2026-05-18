# Native Layer — C++/JNI/llama.cpp Integration

## Overview

Solace uses a native C++ inference engine powered by [llama.cpp](https://github.com/ggerganov/llama.cpp) for running Gemma 4 E2B GGUF models on Android. The native layer includes multimodal (vision) support via the `mtmd` library.

## File Structure

```
runtime-gguf/src/main/cpp/
├── CMakeLists.txt          # Build configuration (191 lines)
├── LLMInference.h          # C++ class declaration (94 lines)
├── LLMInference.cpp        # C++ implementation (498 lines)
├── gguf_bridge.cpp         # JNI bridge functions
├── gguf_bridge_stub.cpp    # Stub for non-native builds
└── GGUFReader.cpp          # GGUF file header parser

llama.cpp/                  # Git submodule (ggerganov/llama.cpp)
├── ggml/                   # Tensor library
├── common/                 # Shared utilities
├── include/                # Public headers (llama.h, common.h, chat.h)
├── tools/mtmd/             # Multimodal library (clip.cpp, mtmd.cpp, mtmd-helper.cpp)
└── vendor/                 # Third-party dependencies
```

## C++ Class: LLMInference

### Header (LLMInference.h)

```cpp
class LLMInference {
private:
    llama_context* _ctx = nullptr;
    llama_model* _model = nullptr;
    llama_sampler* _sampler = nullptr;
    llama_token _currToken;
    llama_batch* _batch = nullptr;
    mtmd_context* _mtmd_ctx = nullptr;  // Multimodal
    std::vector<llama_chat_message> _messages;
    std::string _chatTemplate;
    // ... performance tracking fields

public:
    void loadModel(const char* model_path, float minP, float temperature, ...);
    void addChatMessage(const char* message, const char* role);
    void startCompletion(const char* query);
    std::string completionLoop();
    void stopCompletion();
    bool loadMmproj(const char* mmproj_path);
    bool supportsVision() const;
    void startCompletionWithImage(const char* query, const unsigned char* image_data, uint32_t nx, uint32_t ny);
    std::string benchModel(int pp, int tg, int pl, int nr);
};
```

### Model Loading (`loadModel`)

1. Calls `ggml_backend_load_all()` to load available backends (CPU, Vulkan, etc.)
2. Creates model with `llama_model_load_from_file()` — configures mmap, mlock, GPU layers
3. Creates context with `llama_init_from_model()` — sets n_ctx, n_batch, n_ubatch, threads
4. Builds sampler chain:
   - `llama_sampler_init_top_k(topK)` — top-k filtering
   - `llama_sampler_init_top_p(topP, 1)` — nucleus sampling
   - `llama_sampler_init_min_p(minP, 1)` — minimum probability
   - `llama_sampler_init_penalties(repeatLastN, repeatPenalty, ...)` — repetition penalty
   - `llama_sampler_init_temp(temperature)` + `llama_sampler_init_dist(seed)` — temperature + distribution
   - Or `llama_sampler_init_greedy()` if temperature <= 0
5. Reads chat template from GGUF metadata or uses provided template
6. Stores thread count and GPU layer configuration

### Text Generation

**Start (`startCompletion`):**
1. Adds user message to conversation history
2. Applies chat template via `common_chat_templates_init()` + `common_chat_templates_apply()`
3. Tokenizes prompt via `common_tokenize()`
4. Creates batch with prompt tokens

**Loop (`completionLoop`):**
1. Checks context size — throws if full
2. Calls `llama_decode(_ctx, *_batch)` — runs model
3. Samples token via `llama_sampler_sample(_sampler, _ctx, -1)`
4. Checks for end-of-generation (EOG)
5. Converts token to text via `common_token_to_piece()`
6. Tracks prompt processing vs generation speed
7. Updates batch for next iteration (single token)

**Stop (`stopCompletion`):**
- If generation reached EOG and storeChats is enabled, saves assistant response to history

### Multimodal Vision

**Load mmproj (`loadMmproj`):**
```cpp
mtmd_context_params mparams = mtmd_context_params_default();
mparams.use_gpu = (_configuredGpuLayers > 0);
mparams.n_threads = _configuredThreads;
mparams.warmup = true;
_mtmd_ctx = mtmd_init_from_file(mmproj_path, _model, mparams);
```

**Image Completion (`startCompletionWithImage`):**
1. Builds formatted prompt with chat template
2. Inserts `<__media__>` marker at the start of the last user turn
3. Creates `mtmd_bitmap` from raw RGB bytes: `mtmd_bitmap_init(nx, ny, image_data)`
4. Tokenizes with `mtmd_tokenize(ctx, chunks, &text, bitmaps, 1)`
5. Evaluates chunks: `mtmd_helper_eval_chunks()` — handles both text and image encoding
6. Clears context memory and sets up batch for token generation

## JNI Bridge (gguf_bridge.cpp)

Maps Kotlin JNI calls to C++ methods:

| JNI Function | C++ Method |
|---|---|
| `loadModel(modelPtr, ...)` | `LLMInference::loadModel()` |
| `addChatMessage(modelPtr, message, role)` | `LLMInference::addChatMessage()` |
| `startCompletion(modelPtr, prompt)` | `LLMInference::startCompletion()` |
| `completionLoop(modelPtr)` | `LLMInference::completionLoop()` |
| `stopCompletion(modelPtr)` | `LLMInference::stopCompletion()` |
| `loadMmproj(modelPtr, path)` | `LLMInference::loadMmproj()` |
| `supportsVision(modelPtr)` | `LLMInference::supportsVision()` |
| `startCompletionWithImage(modelPtr, prompt, data, w, h)` | `LLMInference::startCompletionWithImage()` |
| `benchModel(modelPtr, pp, tg, pl, nr)` | `LLMInference::benchModel()` |
| `getResponseGenerationSpeed(modelPtr)` | `LLMInference::getResponseGenerationSpeed()` |
| `getPromptProcessingSpeed(modelPtr)` | `LLMInference::getPromptProcessingSpeed()` |
| `getConfiguredThreadCount(modelPtr)` | `LLMInference::getConfiguredThreads()` |
| `getConfiguredGpuLayers(modelPtr)` | `LLMInference::getConfiguredGpuLayers()` |
| `getContextSizeUsed(modelPtr)` | `LLMInference::getContextSizeUsed()` |
| `closeNative(modelPtr)` | `delete LLMInference*` |

The `modelPtr` is a `jlong` cast of `LLMInference*`.

## CMake Build Configuration

### Key Settings

```cmake
cmake_minimum_required(VERSION 3.22.1)
set(CMAKE_CXX_STANDARD 20)
set(BUILD_SHARED_LIBS ON)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_TOOLS OFF)      # mtmd added manually
set(LLAMA_BUILD_COMMON ON)
set(LLAMA_CURL OFF)
set(GGML_LLAMAFILE OFF)
```

### Multimodal Library

```cmake
add_subdirectory(../../../../llama.cpp llama.cpp)
set(LLAMA_INSTALL_VERSION "0.0.0" CACHE STRING "" FORCE)
add_subdirectory(${LLAMA_DIR}/tools/mtmd mtmd)
```

### Build Targets

Seven ARM64-optimized libraries, selected at runtime based on CPU features:

| Library | CPU Features | Description |
|---|---|---|
| `llama_android` | Any | Universal fallback |
| `llama_android_v8_2_fp16` | ARMv8.2-A + FP16 | Basic ARM64 optimization |
| `llama_android_v8_2_fp16_dotprod` | + Dot Product | Integer dot product |
| `llama_android_v8_4_fp16_dotprod` | ARMv8.4-A | Memory tagging, etc. |
| `llama_android_v8_4_fp16_dotprod_sve` | + SVE | Scalable Vector Extension |
| `llama_android_v8_4_fp16_dotprod_i8mm` | + I8MM | Int8 matrix multiply |
| `llama_android_v8_4_fp16_dotprod_i8mm_sve` | + SVE + I8MM | Full optimization |

### Vulkan GPU Backend

Auto-detected at build time:
```cmake
find_path(VULKAN_HPP_INCLUDE_DIR NAMES vulkan/vulkan.hpp ...)
if(VULKAN_HPP_INCLUDE_DIR)
    set(GGML_VULKAN ON)
endif()
```

### Linking

Each library links:
- `llama` — core llama.cpp
- `common` — shared utilities (tokenization, chat templates)
- `mtmd` — multimodal library
- `log` — Android logging
- `android` — Android native APIs
- `vulkan` — (optional) GPU backend

## Kotlin JNI Wrapper (GgufEngine.kt)

### CPU Feature Detection

Static init block reads `/proc/cpuinfo` and detects:
- **FP16**: `fphp`, `fp16` — half-precision floating point
- **Dot Product**: `asimddp`, `dotprod` — integer dot product
- **SVE**: `sve` — Scalable Vector Extension
- **I8MM**: `i8mm` — Int8 matrix multiply
- **ARMv8.2+**: `asimd` + `crc32` + `aes`
- **ARMv8.4+**: `dcpop` + `uscat`

### Core Type Detection

Maps CPU part IDs to core types:
- **Efficiency**: Cortex-A55 (0xD05), A53 (0xD03), A510 (0xD46), A520 (0xD4E)
- **Performance**: Cortex-A76 (0xD0B), A77 (0xD0D), A78 (0xD0E), X1 (0xD44), X2 (0xD4D), X3 (0xD4F)
- **Prime**: Cortex-A715 (0xD4A), A720 (0xD4B), X4 (0xD41), X925 (0xD57)

### SoC Detection

- **Qualcomm Kryo**: Checks for "Kryo" or "Hardware\t: Qualcomm" in cpuinfo
- **MediaTek Dimensity**: Checks for "Dimensity" or "Hardware\t: MT"

### Library Selection

```kotlin
val candidateLibraries = mutableListOf<String>()
if (!isEmulated) {
    if (supportsArm64V8a()) {
        when {
            isAtLeastArmV84 && hasSve && hasI8mm && hasFp16 && hasDotProd ->
                candidateLibraries += "llama_android_v8_4_fp16_dotprod_i8mm_sve"
            // ... other combinations
        }
    }
}
candidateLibraries += "llama_android"  // Always add fallback
```

Tries each library in order until one loads successfully.

## Multimodal Pipeline (End-to-End)

```
┌─────────────┐    ┌──────────────┐    ┌───────────────┐
│  Kotlin UI   │───▶│ ChatViewModel │───▶│  GgufEngine   │
│ (image pick) │    │ (bitmap→RGB)  │    │ (JNI call)    │
└─────────────┘    └──────────────┘    └───────┬───────┘
                                               │
                                        ┌──────▼──────┐
                                        │ gguf_bridge  │
                                        │ (JNI native) │
                                        └──────┬──────┘
                                               │
                                        ┌──────▼──────┐
                                        │ LLMInference │
                                        │ startCompletion│
                                        │ WithImage()   │
                                        └──────┬──────┘
                                               │
                              ┌─────────────────┼─────────────────┐
                              │                 │                 │
                       ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐
                       │  Chat Template│  │ mtmd_bitmap │  │ mtmd_tokenize│
                       │  + marker    │  │ (RGB→bitmap)│  │ (text+image)│
                       └─────────────┘  └─────────────┘  └──────┬──────┘
                                                               │
                                                        ┌──────▼──────┐
                                                        │ mtmd_helper │
                                                        │ _eval_chunks│
                                                        │ (encode+decode)│
                                                        └──────┬──────┘
                                                               │
                                                        ┌──────▼──────┐
                                                        │ completion  │
                                                        │ Loop()      │
                                                        │ (token gen) │
                                                        └─────────────┘
```

### Image Processing (Kotlin Side)

```kotlin
private fun bitmapToRgbBytes(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val bytes = ByteArray(width * height * 3)
    var offset = 0
    for (pixel in pixels) {
        bytes[offset++] = (pixel shr 16 and 0xFF).toByte()  // R
        bytes[offset++] = (pixel shr 8 and 0xFF).toByte()   // G
        bytes[offset++] = (pixel and 0xFF).toByte()          // B
    }
    return bytes
}
```

### Marker Insertion (C++ Side)

For Gemma 4, the media marker is inserted at the start of the last user turn:

```cpp
std::string marker = mtmd_default_marker();  // "<__media__>"
size_t last_user_pos = prompt.rfind("<|turn>user\n");
if (last_user_pos != std::string::npos) {
    size_t content_start = last_user_pos + std::string("<|turn>user\n").length();
    prompt_with_marker = prompt.substr(0, content_start) + marker + "\n" + prompt.substr(content_start);
}
```

## GGUF Reader

A separate native library (`ggufreader`) for reading GGUF file headers without loading the full model:

- Reads model metadata (context size, chat template, architecture)
- Used by `GgufEngine.load()` to determine parameters before full model load
- Lightweight — only parses header, not tensor data

## Performance Optimization

### Batch Sizes

Automatically selected based on thread count:
- 12+ threads: nBatch=1024, nUbatch=512
- 8+ threads: nBatch=512, nUbatch=256
- 4+ threads: nBatch=256, nUbatch=128
- <4 threads: nBatch=128, nUbatch=64

### Thread Count

Default: `Runtime.getRuntime().availableProcessors()` clamped to [1, 16]

### GPU Offload

Layers selected based on model size:
- ≤2GB: 48 layers
- ≤4GB: 36 layers
- ≤8GB: 24 layers
- >8GB: 16 layers

### Compiler Flags

All libraries compiled with:
- `-O3` — maximum optimization
- `-ffast-math` — fast floating point
- `-fvisibility=hidden` — hide symbols
- `-ffunction-sections` + `-fdata-sections` — enable GC
- `-Wl,--gc-sections` — garbage collect unused sections
- `-flto` — link-time optimization
- ARM64 variants: `-march=armv8.x-a+features`
