package com.masterllm.core.domain.repository

import com.masterllm.core.domain.model.LlmModel
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for model management operations.
 */
interface ModelRepository {
    fun getDownloadedModels(): Flow<List<LlmModel>>
    suspend fun getModelById(id: String): LlmModel?
    suspend fun deleteModel(id: String)
    suspend fun saveModel(model: LlmModel)
}
