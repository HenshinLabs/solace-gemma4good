# Master LLM - Mobile LLM Inference Application

Master LLM is a production-ready Android application that brings large language model (LLM) inference to mobile devices. It supports on-device GGUF model execution via llama.cpp, HuggingFace model marketplace integration, character roleplay features, and AI image generation.

## Features

- **On-Device LLM Inference**: Run GGUF quantized models locally using llama.cpp with native C++ backend
- **Model Marketplace**: Browse and download models from HuggingFace Hub
- **Model Manager**: Download, organize, and manage multiple LLM models
- **Chat Interface**: Interactive conversation with LLM models
- **Roleplay**: Create and interact with character-based AI personas
- **Image Generation**: AI-powered image generation (FLUX support planned)
- **Settings**: Customizable app preferences and configurations

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.1.0 |
| UI Framework | Jetpack Compose (BOM 2024.12.01) |
| Architecture | Clean Architecture + MVVM |
| Dependency Injection | Hilt 2.53.1 |
| Database | Room 2.6.1 |
| Networking | Retrofit 2.11.0 + OkHttp 4.12.0 |
| Async | Kotlin Coroutines 1.9.0 |
| Navigation | Navigation Compose 2.8.5 |
| LLM Runtime | llama.cpp (Git submodule) |

## Project Structure

```
Master_LLM/
├── app/                    # Main application module
├── core-data/              # Data layer (Room, repositories)
├── core-domain/            # Domain layer (models, use cases)
├── core-network/           # Network layer (HuggingFace API)
├── core-ui/                # Shared UI components
├── feature-auth/           # Authentication feature
├── feature-chat/           # Chat feature
├── feature-image-gen/      # Image generation feature
├── feature-marketplace/    # Model marketplace feature
├── feature-model-manager/  # Model management feature
├── feature-roleplay/       # Roleplay feature
├── feature-settings/       # Settings feature
├── runtime-gguf/           # GGUF model runtime (native C++)
├── runtime-safetensors/    # Safetensors model runtime
├── runtime-imagegen/       # Image generation runtime
├── llama.cpp/              # llama.cpp git submodule
└── testing/                # Testing utilities
```

## Quick Start

1. Clone the repository with submodules:
   ```bash
   git clone --recurse-submodules https://github.com/your-org/MasterLLM.git
   ```

2. Set up Android development environment (see [Development Setup](development/environment.md))

3. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```

4. Install on device:
   ```bash
   ./gradlew installDebug
   ```

## Documentation

- [Architecture Overview](architecture/README.md)
- [Module Documentation](architecture/modules.md)
- [Runtime Modules](architecture/runtime.md)
- [Development Setup](development/environment.md)
- [Build Instructions](development/build.md)
- [Testing Guide](development/testing.md)
- [Core API Reference](api/core-api.md)
- [Feature API Reference](api/feature-api.md)

## License

See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) for third-party licenses.

## Contributing

Contributions are welcome! Please ensure all tests pass and code follows the project's coding standards before submitting PRs.