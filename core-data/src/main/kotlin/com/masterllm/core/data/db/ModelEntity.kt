package com.masterllm.core.data.db

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting downloaded model metadata.
 */
@Entity(
    tableName = "models",
    indices = [Index(value = ["downloadState"])]
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val repoId: String = "",
    val fileName: String = "",
    val displayName: String = "",
    val format: String = "GGUF",
    val sizeBytes: Long = 0L,
    val quantization: String = "",
    val localPath: String? = null,
    val downloadState: String = "NOT_DOWNLOADED",
    val contextLength: Int = 4096,
    val parameterCount: String = "",
    val downloadedAt: Long = 0L,
    val description: String = "",
)

/**
 * Room entity for conversations.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String = "New Conversation",
    val mode: String = "CHAT",
    val modelId: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
)

/**
 * Room entity for messages.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"]),
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String = "",
    val role: String = "USER",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val attachedImagePath: String? = null,
)

/**
 * Room entity for roleplay sessions.
 */
@Entity(tableName = "roleplay_sessions")
data class RoleplaySessionEntity(
    @PrimaryKey val id: String,
    val conversationId: String = "",
    val title: String = "",
    val genre: String = "",
    val premise: String = "",
    val aiCharacterName: String = "",
    val aiCharacterDescription: String = "",
    val aiCharacterAppearance: String = "",
    val userCharacterName: String = "",
    val userCharacterDescription: String = "",
    val userCharacterAppearance: String = "",
    val worldDetails: String = "",
    val writingStyle: String = "",
    val imageModelId: String? = null,
    val visualStyle: String = "FANTASY_ART",
    val imageFrequency: String = "EVERY_RESPONSE",
    val narrativeResponseCount: Int = 0,
    val lastImagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Room entity for character visual cache.
 */
@Entity(
    tableName = "character_visual_cache",
    primaryKeys = ["characterName", "sessionId"]
)
data class CharacterVisualCacheEntity(
    val characterName: String,
    val sessionId: String,
    val anchorPrompt: String = "",
    val lastImagePath: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
