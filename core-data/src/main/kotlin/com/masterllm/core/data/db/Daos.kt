package com.masterllm.core.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models WHERE downloadState = 'DOWNLOADED' ORDER BY downloadedAt DESC")
    fun getDownloadedModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE repoId = :repoId AND fileName = :fileName LIMIT 1")
    suspend fun getByRepoAndFile(repoId: String, fileName: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ModelEntity)

    @Update
    suspend fun update(entity: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE models SET downloadState = :state, localPath = :localPath WHERE id = :id")
    suspend fun updateDownloadState(id: String, state: String, localPath: String?)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE mode = :mode ORDER BY updatedAt DESC")
    fun getByMode(mode: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationEntity)

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity)

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestamp < :timestamp")
    suspend fun deleteAfter(conversationId: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: String): Int
}

@Dao
interface RoleplaySessionDao {
    @Query("SELECT * FROM roleplay_sessions ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<RoleplaySessionEntity>>

    @Query("SELECT * FROM roleplay_sessions WHERE id = :id")
    suspend fun getById(id: String): RoleplaySessionEntity?

    @Query("SELECT * FROM roleplay_sessions WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversationId(conversationId: String): RoleplaySessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoleplaySessionEntity)

    @Update
    suspend fun update(entity: RoleplaySessionEntity)

    @Query("DELETE FROM roleplay_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CharacterVisualCacheDao {
    @Query("SELECT * FROM character_visual_cache WHERE characterName = :name AND sessionId = :sessionId LIMIT 1")
    suspend fun getEntry(name: String, sessionId: String): CharacterVisualCacheEntity?

    @Query("SELECT * FROM character_visual_cache WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: String): List<CharacterVisualCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CharacterVisualCacheEntity)

    @Query("DELETE FROM character_visual_cache WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
