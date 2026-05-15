package com.masterllm.core.domain.repository

import com.masterllm.core.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository for model CRUD operations.
 */
interface ModelRepository {
    fun getDownloadedModels(): Flow<List<LlmModel>>
    suspend fun getModelById(id: String): LlmModel?
    suspend fun deleteModel(id: String)
    suspend fun saveModel(model: LlmModel)
    suspend fun updateDownloadState(id: String, state: DownloadState, localPath: String? = null)
    suspend fun getModelByRepoAndFile(repoId: String, fileName: String): LlmModel?
}

/**
 * Repository for conversations and messages.
 */
interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    fun getConversationsByMode(mode: ConversationMode): Flow<List<Conversation>>
    suspend fun getConversationById(id: String): Conversation?
    suspend fun createConversation(conversation: Conversation): String
    suspend fun updateConversation(conversation: Conversation)
    suspend fun deleteConversation(id: String)

    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun addMessage(message: Message): String
    suspend fun updateMessage(message: Message)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesAfter(conversationId: String, timestamp: Long)
    suspend fun getMessageCount(conversationId: String): Int
}

/**
 * Repository for roleplay sessions.
 */
interface RoleplayRepository {
    fun getAllSessions(): Flow<List<RoleplaySession>>
    suspend fun getSessionById(id: String): RoleplaySession?
    suspend fun getSessionByConversationId(conversationId: String): RoleplaySession?
    suspend fun createSession(session: RoleplaySession): String
    suspend fun updateSession(session: RoleplaySession)
    suspend fun deleteSession(id: String)
}

/**
 * Repository for character visual cache.
 */
interface CharacterVisualCacheRepository {
    suspend fun getEntry(characterName: String, sessionId: String): CharacterVisualEntry?
    suspend fun saveEntry(entry: CharacterVisualEntry)
    suspend fun getEntriesForSession(sessionId: String): List<CharacterVisualEntry>
    suspend fun deleteEntriesForSession(sessionId: String)
}

/**
 * Repository for user settings / preferences.
 */
interface SettingsRepository {
    fun getHfToken(): Flow<String>
    suspend fun setHfToken(token: String)

    fun getHfUsername(): Flow<String>
    suspend fun setHfUsername(username: String)

    fun getAutoCompactionThreshold(): Flow<Int>
    suspend fun setAutoCompactionThreshold(percent: Int)

    fun getDefaultThreadCount(): Flow<Int>
    suspend fun setDefaultThreadCount(count: Int)

    fun getTheme(): Flow<String>
    suspend fun setTheme(theme: String)

    fun getDefaultImageFrequency(): Flow<ImageFrequency>
    suspend fun setDefaultImageFrequency(freq: ImageFrequency)

    fun getCharacterConsistencyEnabled(): Flow<Boolean>
    suspend fun setCharacterConsistencyEnabled(enabled: Boolean)

    fun getGpuAccelerationEnabled(): Flow<Boolean>
    suspend fun setGpuAccelerationEnabled(enabled: Boolean)

    fun getModelStoragePath(): Flow<String>
    suspend fun setModelStoragePath(path: String)

    fun getOllamaHost(): Flow<String>
    suspend fun setOllamaHost(host: String)

    fun getOllamaEnabled(): Flow<Boolean>
    suspend fun setOllamaEnabled(enabled: Boolean)

    fun getOllamaKeepAlive(): Flow<String>
    suspend fun setOllamaKeepAlive(keepAlive: String)

    fun getOllamaSystemPrompt(): Flow<String>
    suspend fun setOllamaSystemPrompt(prompt: String)

    // ─── Solace / Gemma 4 Settings ─────────────────────────────

    fun getShowThinking(): Flow<Boolean>
    suspend fun setShowThinking(show: Boolean)

    fun getThinkingBudget(): Flow<Int>
    suspend fun setThinkingBudget(tokens: Int)

    fun getContextLength(): Flow<Int>
    suspend fun setContextLength(length: Int)

    fun getVoiceOutputEnabled(): Flow<Boolean>
    suspend fun setVoiceOutputEnabled(enabled: Boolean)

    fun getVoiceInputEnabled(): Flow<Boolean>
    suspend fun setVoiceInputEnabled(enabled: Boolean)
}
