mini# Module Documentation

## Core Modules

### core-domain

**Purpose**: Domain layer containing business logic, entities, and repository interfaces.

**Location**: `core-domain/src/main/kotlin/com/masterllm/core/domain/`

**Key Components**:

```
core-domain/
├── model/
│   ├── LlmModel.kt          # LLM model entity
│   └── TextRuntimeModelResolver.kt  # Model resolver interface
├── repository/
│   └── ModelRepository.kt   # Model repository interface
└── usecase/
    └── UseCases.kt          # Application use cases
```

**Models**:
- `LlmModel`: Domain entity representing an LLM model with metadata (name, size, quantization, etc.)
- `TextRuntimeModelResolver`: Interface for resolving model files to runtime instances

**Repository Interfaces**:
- `ModelRepository`: CRUD operations for model metadata

**Use Cases**:
- `GetModelsUseCase`: Fetch available models
- `DownloadModelUseCase`: Download model from marketplace
- `DeleteModelUseCase`: Remove model from device

### core-data

**Purpose**: Data layer implementing repositories with Room database and local storage.

**Location**: `core-data/src/main/kotlin/com/masterllm/core/data/`

**Structure**:
```
core-data/
├── db/
│   ├── AppDatabase.kt       # Room database
│   ├── Daos.kt             # Data Access Objects
│   └── ModelEntity.kt      # Room entities
├── di/
│   └── DataModule.kt       # Hilt DI module
├── mapper/
│   └── EntityMappers.kt    # Domain ↔ Entity mappers
└── repository/
    ├── ModelRepositoryImpl.kt
    ├── ConversationRepositoryImpl.kt
    ├── RoleplayRepositoryImpl.kt
    ├── SettingsRepositoryImpl.kt
    └── CharacterVisualCacheRepositoryImpl.kt
```

**Database Schema**:
- `models` table: Downloaded model metadata
- `conversations` table: Chat history
- `characters` table: Roleplay character definitions
- `settings` table: User preferences

### core-network

**Purpose**: Network layer for HuggingFace API integration.

**Location**: `core-network/src/main/kotlin/com/masterllm/core/network/`

**Components**:
```
core-network/
├── HuggingFaceApi.kt       # Retrofit API interface
├── HfAuth.kt               # API authentication
├── model/
│   └── NetworkModels.kt   # Network DTOs
└── di/
    └── NetworkModule.kt    # Hilt DI module
```

**API Endpoints**:
- `GET /api/models`: List available models
- `GET /api/models/{id}`: Model details
- `HEAD /api/models/{id}/blob`: Check model file exists
- `GET /api/models/{id}/blob`: Download model file

### core-ui

**Purpose**: Shared UI components used across features.

**Location**: `core-ui/src/main/kotlin/com/masterllm/core/ui/`

**Components**:
```
core-ui/
├── components/
│   ├── MarkdownMessageText.kt  # Markdown rendering
│   └── SharedComponents.kt     # Reusable UI components
└── theme/
    ├── Theme.kt               # Material 3 theme
    └── Type.kt                # Typography definitions
```

---

## Feature Modules

### feature-auth

**Purpose**: User authentication handling.

**Screens**: `AuthScreen.kt`
**ViewModel**: `AuthViewModel.kt`

**Features**:
- HuggingFace token authentication
- Token persistence using EncryptedSharedPreferences
- Logout functionality

### feature-chat

**Purpose**: Main chat interface for LLM conversations.

**Screens**: `ChatScreen.kt`
**ViewModel**: `ChatViewModel.kt`

**Features**:
- Real-time message streaming
- Markdown message rendering
- Conversation history
- Model selection

### feature-marketplace

**Purpose**: Browse and search HuggingFace models.

**Screens**: `MarketplaceScreen.kt`
**ViewModel**: `MarketplaceViewModel.kt`

**Features**:
- Model search and filtering
- Model details display (size, downloads, likes)
- One-click download
- Download progress tracking

### feature-model-manager

**Purpose**: Manage downloaded models.

**Screens**: `ModelManagerScreen.kt`
**ViewModel**: `ModelManagerViewModel.kt`

**Features**:
- List all downloaded models
- Delete models
- Model storage info
- Active model selection

### feature-roleplay

**Purpose**: Character-based roleplay conversations.

**Screens**: `RoleplayScreen.kt`
**ViewModel**: `RoleplayViewModel.kt`

**Features**:
- Character creation and management
- Character profiles with visual references
- Roleplay conversation mode
- Character conversation history

### feature-settings

**Purpose**: App configuration and preferences.

**Screens**: `SettingsScreen.kt`
**ViewModel**: `SettingsViewModel.kt`

**Features**:
- Theme selection (light/dark/system)
- Default model configuration
- Cache management
- About/version info

---

## Module Dependencies

```
app
  ├── feature-auth
  │   └── core-ui, core-domain
  ├── feature-chat
  │   └── core-ui, core-domain, runtime-gguf
  ├── feature-marketplace
  │   └── core-ui, core-network, core-domain
  ├── feature-model-manager
  │   └── core-ui, core-data, core-domain
  ├── feature-roleplay
  │   └── core-ui, core-domain, core-data
  └── feature-settings
      └── core-ui, core-data, core-domain

shared dependencies:
  ├── core-domain (all features)
  ├── core-data (model-manager, roleplay, settings)
  ├── core-network (marketplace)
  └── core-ui (all features)