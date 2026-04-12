package com.masterllm.core.data.repository

import com.masterllm.core.data.db.*
import com.masterllm.core.data.mapper.*
import com.masterllm.core.domain.model.CharacterVisualEntry
import com.masterllm.core.domain.repository.CharacterVisualCacheRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CharacterVisualCacheRepositoryImpl @Inject constructor(
    private val characterVisualCacheDao: CharacterVisualCacheDao,
) : CharacterVisualCacheRepository {

    override suspend fun getEntry(characterName: String, sessionId: String): CharacterVisualEntry? =
        characterVisualCacheDao.getEntry(characterName, sessionId)?.toDomain()

    override suspend fun saveEntry(entry: CharacterVisualEntry) =
        characterVisualCacheDao.insert(entry.toEntity())

    override suspend fun getEntriesForSession(sessionId: String): List<CharacterVisualEntry> =
        characterVisualCacheDao.getForSession(sessionId).map { it.toDomain() }

    override suspend fun deleteEntriesForSession(sessionId: String) =
        characterVisualCacheDao.deleteForSession(sessionId)
}
