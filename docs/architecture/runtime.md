# Runtime Modules

The runtime modules handle model loading and inference. They bridge native code with Kotlin.

## runtime-gguf

**Purpose**: On-device GGUF model inference using llama.cpp

**Location**: `runtime-gguf/src/`

### Structure

```
runtime-gguf/
├── main/
│   ├── cpp/
│   │   ├── CMakeLists.txt           # Native build config
│   │   ├── GGUFReader.cpp           # GGUF file format parser
│   │   ├── GGUFReader.h
│   │   ├── gguf_bridge.cpp          # JNI bridge to Kotlin
│   │   ├── gguf_bridge_stub.cpp     # Stub for testing
│   │   ├── LLMInference.cpp         # llama.cpp wrapper
│   │   └── LLMInference.h
│   └── kotlin/
│       └── com/masterllm/runtime/gguf/
│           ├── GgufEngine.kt        # Main engine interface
│           ├── GgufHeaderParser.kt # Kotlin GGUF header parsing
│           ├── GGUFReader.kt       # Kotlin JNI wrapper
│           ├── GgufRuntimeCoordinator.kt  # Runtime coordination
│           └── PerformanceUsageSampler.kt # Performance monitoring
├── androidTest/                     # Device tests
├── test/                           # Unit tests
└── AndroidManifest.xml
```

### Key Components

#### Native Layer (C++)

| File | Purpose |
|------|---------|
| `GGUFReader.cpp` | Parse GGUF file headers and metadata |
| `LLMInference.cpp` | llama.cpp integration for model inference |
| `gguf_bridge.cpp` | JNI bridge exposing native functions to Kotlin |

#### Kotlin Layer

| Class | Purpose |
|-------|---------|
| `GgufEngine` | Main interface for model operations |
| `GGUFReader` | JNI wrapper for native GGUF reader |
| `GgufHeaderParser` | Pure Kotlin GGUF header parsing |
| `GgufRuntimeCoordinator` | Coordinates model lifecycle and resources |
| `PerformanceUsageSampler` | Monitors CPU/memory during inference |

### Build System

The native code uses CMake with the following configuration:
- Links against llama.cpp library
- Targets `arm64-v8a` and `x86_64` architectures
- Uses `CMAKE_ANDROID_NDK` for cross-compilation

### Usage Example

```kotlin
// Initialize engine
val engine = GgufEngine(context)

// Load model from file
val modelFile = File(modelPath)
val header = GgufHeaderParser.parse(modelFile)
val context = engine.loadModel(modelFile, header)

// Generate tokens
val prompt = "Hello, how are you?"
val params = InferenceParams(
    temperature = 0.7f,
    maxTokens = 512,
    topP = 0.9f
)

val stream = engine.generate(context, prompt, params)
stream.collect { token ->
    // Process token
}
```

### GGUF File Format

GGUF (General Gradient Unified Format) is a format designed by llama.cpp for storing large model weights with metadata. Key features:

- **Metadata**: Model architecture, tokenizer, quantization info
- **Tensor alignment**: Memory-mapped for efficient loading
- **Versioning**: Forward-compatible format

### Testing

- **Unit tests** (`test/`): GGUF header parsing, parameter validation
- **Device tests** (`androidTest/`): Full inference with mock models

---

## runtime-safetensors

**Purpose**: Safetensors model format support

**Location**: `runtime-safetensors/src/`

### Structure

```
runtime-safetensors/
├── main/kotlin/com/masterllm/runtime/safetensors/
│   └── SafetensorsEngine.kt    # Safetensors loading engine
├── androidTest/                # Device tests
├── test/                       # Unit tests
└── AndroidManifest.xml
```

### Safetensors Format

Safetensors is a safe, fast format for storing tensors:
- Memory-mapped for lazy loading
- No arbitrary code execution
- Header + tensor layout

---

## runtime-imagegen

**Purpose**: AI image generation engine

**Location**: `runtime-imagegen/src/`

### Structure

```
runtime-imagegen/
├── main/kotlin/com/masterllm/runtime/imagegen/
│   ├── ImageGenEngine.kt       # Main image generation engine
│   └── ImageModelInspector.kt  # Model metadata inspection
├── androidTest/                # Device tests
├── test/                       # Unit tests
└── AndroidManifest.xml
```

### Supported Models

- FLUX (planned)
- Stable Diffusion variants (architecture extensible)

### Usage Example

```kotlin
val engine = ImageGenEngine(context)

// Generate image from text
val result = engine.generate(
    prompt = "A beautiful sunset over mountains",
    negativePrompt = "blurry, low quality",
    steps = 20,
    width = 512,
    height = 512
)

// Save or display result
result.saveToFile(outputFile)
```

---

## Testing Modules

Located in `testing/` directory:

| Module | Purpose |
|--------|---------|
| `testing-shared` | Shared test utilities and mocks |
| `testing-fixtures` | Test data factories and fixtures |
| `testing-robot` | Robot pattern for UI testing |

---

## Integration Notes

### Adding a New Runtime

1. Create module under `runtime-*`
2. Implement engine interface
3. Add native dependencies if needed
4. Create DI module for Hilt injection
5. Add tests (unit + instrumented)
6. Document in this file