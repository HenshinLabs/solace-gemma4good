package com.masterllm.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the Master LLM app.
 */
@Database(
    entities = [
        ModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        RoleplaySessionEntity::class,
        CharacterVisualCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun roleplaySessionDao(): RoleplaySessionDao
    abstract fun characterVisualCacheDao(): CharacterVisualCacheDao
}
