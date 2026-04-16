# Architecture Overview

Master LLM follows **Clean Architecture** principles with a layered approach combining **MVVM** for presentation. The architecture ensures separation of concerns, testability, and maintainability.

## Clean Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                       │
│              (Compose UI + ViewModels)                       │
├─────────────────────────────────────────────────────────────┤
│                    Application Layer                        │
│              (Use Cases + View States)                      │
├─────────────────────────────────────────────────────────────┤
│                      Domain Layer                           │
│              (Entities + Repository Interfaces)             │
├─────────────────────────────────────────────────────────────┤
│                       Data Layer                            │
│       (Repository Impl + Room + Network + DataStore)        │
└─────────────────────────────────────────────────────────────┘
```

## Module Architecture

### Core Modules

| Module | Responsibility |
|--------|-----------------|
| `core-domain` | Domain models, repository interfaces, use cases |
| `core-data` | Room database, repository implementations, mappers |
| `core-network` | HuggingFace API client, authentication |
| `core-ui` | Shared Compose components, theming |

### Feature Modules

| Module | Responsibility |
|--------|-----------------|
| `feature-auth` | User authentication flow |
| `feature-chat` | LLM chat interface |
| `feature-marketplace` | Browse/search HuggingFace models |
| `feature-model-manager` | Model download and management |
| `feature-roleplay` | Character-based conversations |
| `feature-settings` | App preferences |

### Runtime Modules

| Module | Responsibility |
|--------|-----------------|
| `runtime-gguf` | On-device GGUF model inference via llama.cpp |
| `runtime-safetensors` | Safetensors model loading |
| `runtime-imagegen` | AI image generation engine |

## Data Flow

```
User Action → ViewModel → UseCase → Repository → Data Source
                ↓
            UI State → Compose UI
```

## Dependency Rules

- **Domain layer** has no dependencies on other layers
- **Core modules** can depend on each other but not on feature modules
- **Feature modules** can depend on core modules
- **Runtime modules** are isolated and accessed via interfaces

## Key Patterns

### Dependency Injection
Hilt is used throughout for dependency injection, following constructor injection pattern.

### State Management
- ViewModels expose `StateFlow` for UI state
- Single source of truth per feature
- Immutable state objects

### Repository Pattern
Domain defines repository interfaces, data layer provides implementations.

## Native Integration

The `runtime-gguf` module bridges Kotlin with native C++ code via JNI:
- GGUF file parsing (C++)
- llama.cpp model loading
- Token generation loop
- Memory management

See [Runtime Modules](runtime.md) for details.