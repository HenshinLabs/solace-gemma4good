package com.masterllm.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting downloaded model metadata.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val repoId: String = "",
    val fileName: String = "",
    val displayName: String = "",
    val format: String = "GGUF",
    val sizeBytes: Long = 0L,
    val quantization: String = "",
    val localPath: String? = null,
    val isDownloaded: Boolean = false,
)
