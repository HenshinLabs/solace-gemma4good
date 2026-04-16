# Core API Reference

Domain models, repository interfaces, and key types.

## Domain Models

### LlmModel

Represents a language model entity.

```kotlin
data class LlmModel(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val quantization: String?,
    val architecture: String?,
    val vocabularySize: Int?,
    val contextLength: Int?,
    val isDownloaded: Boolean = false
)
```

**Properties**:
| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Unique identifier |
| `name` | `String` | Display name |
| `path` | `String` | File path on device |
| `size` | `Long` | Model size in bytes |
| `quantization` | `String?` | Quantization type (Q4_K_M, Q8_0, etc.) |
| `architecture` | `String?` | Model architecture |
| `vocabularySize` | `Int?` | Token vocabulary size |
| `contextLength` | `Int?` | Maximum context length |
| `isDownloaded` | `Boolean` | Download status |

### TextRuntimeModelResolver

Interface for resolving model files to runtime instances.

```kotlin
interface TextRuntimeModelResolver {
    suspend fun resolveAvailable(): List<LlmModel>
    suspend fun resolveById(id: String): LlmModel?
}
```

## Repository Interfaces

### ModelRepository

CRUD operations for model metadata.

```kotlin
interface ModelRepository {
    suspend fun getModels(): List<LlmModel>
    suspend fun getModelById(id: String): LlmModel?
    suspend fun insertModel(model: LlmModel)
    suspend fun deleteModel(id: String)
    suspend fun updateModel(model: LlmModel)
}
```

### ConversationRepository

Chat conversation persistence.

```kotlin
interface ConversationRepository {
    suspend fun getConversations(): List<Conversation>
    suspend fun getConversationById(id: String): Conversation?
    suspend fun saveConversation(conversation: Conversation)
    suspend fun deleteConversation(id: String)
}
```

### RoleplayRepository

Character and roleplay data.

```kotlin
interface RoleplayRepository {
    suspend fun getCharacters(): List<Character>
    suspend fun getCharacterById(id: String): Character?
    suspend fun saveCharacter(character: Character)
    suspend fun deleteCharacter(id: String)
}
```

### SettingsRepository

User preferences and configuration.

```kotlin
interface SettingsRepository {
    suspend fun getTheme(): Theme
    suspend fun setTheme(theme: Theme)
    suspend fun getDefaultModelId(): String?
    suspend fun setDefaultModelId(id: String?)
    suspend fun getHuggingFaceToken(): String?
    suspend fun setHuggingFaceToken(token: String?)
}
```

### CharacterVisualCacheRepository

Cached character images and visuals.

```kotlin
interface CharacterVisualCacheRepository {
    suspend fun cacheVisual(characterId: String, imageData: ByteArray)
    suspend fun getVisual(characterId: String): ByteArray?
    suspend fun clearCache()
}
```

## Use Cases

### GetModelsUseCase

```kotlin
class GetModelsUseCase(
    private val repository: ModelRepository
) {
    suspend operator fun invoke(): List<LlmModel>
}
```

### DownloadModelUseCase

```kotlin
class DownloadModelUseCase(
    private val api: HuggingFaceApi,
    private val repository: ModelRepository
) {
    suspend operator fun invoke(modelId: String, token: String?): Flow<DownloadProgress>
}
```

### DeleteModelUseCase

```kotlin
class DeleteModelUseCase(
    private val repository: ModelRepository
) {
    suspend operator fun invoke(modelId: String)
}
```

## Network Models

### HfModelInfo

HuggingFace API model response.

```kotlin
data class HfModelInfo(
    val id: String,
    val modelId: String,
    val sha: String,
    val createdAt: String,
    val lastModified: String,
    private val _private: Boolean,
    val downloads: Int,
    val likes: Int,
    val tags: List<String>
)
```

### HfFileInfo

File information for model downloads.

```kotlin
data class HfFileInfo(
    val size: Long,
    val path: String
)
```

## Enums

### Theme

```kotlin
enum class Theme {
    LIGHT,
    DARK,
    SYSTEM
}
```

### DownloadProgress

```kotlin
sealed class DownloadProgress {
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long)
    data class Completed(val model: LlmModel)
    data class Failed(val error: Throwable)
}