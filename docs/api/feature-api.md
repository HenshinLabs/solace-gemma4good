# Feature API Reference

Screens, ViewModels, and public APIs for all feature modules.

## feature-auth

### AuthViewModel

Authentication state management.

```kotlin
class AuthViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<AuthUiState>
    val token: StateFlow<String?>

    fun saveToken(token: String)
    fun clearToken()
}
```

### AuthUiState

```kotlin
data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val error: String? = null
)
```

### AuthScreen

```kotlin
@Composable
fun AuthScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onAuthenticated: () -> Unit
)
```

---

## feature-chat

### ChatViewModel

Chat message handling and LLM interaction.

```kotlin
class ChatViewModel @Inject constructor(
    private val ggufEngine: GgufEngine,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    val uiState: StateFlow<ChatUiState>
    val messages: StateFlow<List<ChatMessage>>

    fun sendMessage(text: String)
    fun loadConversation(id: String)
    fun selectModel(modelId: String)
    fun clearChat()
}
```

### ChatUiState

```kotlin
data class ChatUiState(
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val selectedModel: LlmModel? = null,
    val error: String? = null
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long
)

enum class MessageRole { USER, ASSISTANT }
```

### ChatScreen

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToMarketplace: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
)
```

---

## feature-marketplace

### MarketplaceViewModel

HuggingFace model browsing and download.

```kotlin
class MarketplaceViewModel @Inject constructor(
    private val api: HuggingFaceApi,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<MarketplaceUiState>
    val models: StateFlow<List<HfModelInfo>>

    fun searchModels(query: String)
    fun downloadModel(modelId: String)
    fun cancelDownload(modelId: String)
}
```

### MarketplaceUiState

```kotlin
data class MarketplaceUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val error: String? = null
)

sealed class DownloadProgress {
    data object Idle
    data class InProgress(val bytes: Long, val total: Long)
    data object Completed
    data class Failed(val message: String)
}
```

### MarketplaceScreen

```kotlin
@Composable
fun MarketplaceScreen(
    viewModel: MarketplaceViewModel = hiltViewModel(),
    onNavigateToChat: () -> Unit = {}
)
```

---

## feature-model-manager

### ModelManagerViewModel

Local model management.

```kotlin
class ModelManagerViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val textRuntimeModelResolver: TextRuntimeModelResolver
) : ViewModel() {

    val uiState: StateFlow<ModelManagerUiState>
    val models: StateFlow<List<LlmModel>>

    fun deleteModel(modelId: String)
    fun refreshModels()
    fun setActiveModel(modelId: String)
}
```

### ModelManagerUiState

```kotlin
data class ModelManagerUiState(
    val isLoading: Boolean = false,
    val totalStorage: Long = 0,
    val error: String? = null
)
```

### ModelManagerScreen

```kotlin
@Composable
fun ModelManagerScreen(
    viewModel: ModelManagerViewModel = hiltViewModel()
)
```

---

## feature-roleplay

### RoleplayViewModel

Character-based conversations.

```kotlin
class RoleplayViewModel @Inject constructor(
    private val roleplayRepository: RoleplayRepository,
    private val characterVisualCache: CharacterVisualCacheRepository,
    private val ggufEngine: GgufEngine
) : ViewModel() {

    val uiState: StateFlow<RoleplayUiState>
    val characters: StateFlow<List<Character>>
    val activeConversation: StateFlow<RoleplayConversation?>

    fun loadCharacters()
    fun createCharacter(character: Character)
    fun selectCharacter(characterId: String)
    fun sendMessage(text: String)
}
```

### RoleplayUiState

```kotlin
data class RoleplayUiState(
    val isLoading: Boolean = false,
    val selectedCharacter: Character? = null,
    val error: String? = null
)

data class Character(
    val id: String,
    val name: String,
    val description: String,
    val visualUrl: String?,
    val personality: String,
    val createdAt: Long
)

data class RoleplayConversation(
    val id: String,
    val characterId: String,
    val messages: List<RoleplayMessage>,
    val createdAt: Long
)

data class RoleplayMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long
)
```

### RoleplayScreen

```kotlin
@Composable
fun RoleplayScreen(
    viewModel: RoleplayViewModel = hiltViewModel(),
    onNavigateToCharacterEditor: () -> Unit = {}
)
```

---

## feature-settings

### SettingsViewModel

App configuration management.

```kotlin
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState>
    val theme: StateFlow<Theme>

    fun setTheme(theme: Theme)
    fun setDefaultModel(modelId: String?)
    fun clearCache()
    fun getAppVersion(): String
}
```

### SettingsUiState

```kotlin
data class SettingsUiState(
    val isLoading: Boolean = false,
    val defaultModelId: String? = null,
    val cacheSize: Long = 0,
    val appVersion: String = ""
)
```

### SettingsScreen

```kotlin
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToAuth: () -> Unit = {}
)
```

---

## Runtime Engine APIs

### GgufEngine

Main interface for GGUF model inference.

```kotlin
interface GgufEngine {
    suspend fun loadModel(file: File, header: GgufHeader): ModelContext
    suspend fun unloadModel(context: ModelContext)
    fun generate(context: ModelContext, prompt: String, params: InferenceParams): Flow<String>
    fun getPerformanceStats(): PerformanceStats
}

data class ModelContext(
    val modelPath: String,
    val contextId: Long,
    val metadata: GgufMetadata
)

data class InferenceParams(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val seed: Long? = null
)

data class PerformanceStats(
    val tokensPerSecond: Float,
    val memoryUsedMb: Float,
    val inferenceTimeMs: Long
)
```

### SafetensorsEngine

Safetensors format support.

```kotlin
interface SafetensorsEngine {
    suspend fun loadModel(file: File): SafetensorsContext
    fun getTensorInfo(context: SafetensorsContext): List<TensorInfo>
}

data class SafetensorsContext(
    val filePath: String,
    val tensors: List<TensorInfo>
)

data class TensorInfo(
    val name: String,
    val shape: List<Int>,
    val dtype: String,
    val offset: Long
)
```

### ImageGenEngine

AI image generation interface.

```kotlin
interface ImageGenEngine {
    suspend fun generate(params: ImageGenParams): GeneratedImage
    fun getModelInfo(): ImageModelInfo
}

data class ImageGenParams(
    val prompt: String,
    val negativePrompt: String = "",
    val steps: Int = 20,
    val width: Int = 512,
    val height: Int = 512,
    val guidanceScale: Float = 7.5f,
    val seed: Long? = null
)

data class GeneratedImage(
    val imageData: ByteArray,
    val width: Int,
    val height: Int,
    val seed: Long
)

data class ImageModelInfo(
    val name: String,
    val version: String,
    val supportedSizes: List<Pair<Int, Int>>
)