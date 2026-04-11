package com.masterllm.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the Master LLM app.
 */
@Database(entities = [ModelEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase()
