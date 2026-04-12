package com.masterllm.core.data.repository

import com.masterllm.core.data.db.*
import com.masterllm.core.data.mapper.*
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val modelDao: ModelDao,
) : ModelRepository {

    override fun getDownloadedModels(): Flow<List<LlmModel>> =
        modelDao.getDownloadedModels().map { list -> list.map { it.toDomain() } }

    override suspend fun getModelById(id: String): LlmModel? =
        modelDao.getById(id)?.toDomain()

    override suspend fun deleteModel(id: String) =
        modelDao.deleteById(id)

    override suspend fun saveModel(model: LlmModel) =
        modelDao.insert(model.toEntity())

    override suspend fun updateDownloadState(id: String, state: DownloadState, localPath: String?) =
        modelDao.updateDownloadState(id, state.name, localPath)

    override suspend fun getModelByRepoAndFile(repoId: String, fileName: String): LlmModel? =
        modelDao.getByRepoAndFile(repoId, fileName)?.toDomain()
}
