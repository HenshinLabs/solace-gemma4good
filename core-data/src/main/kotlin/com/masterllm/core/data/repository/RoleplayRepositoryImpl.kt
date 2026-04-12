package com.masterllm.core.data.repository

import com.masterllm.core.data.db.*
import com.masterllm.core.data.mapper.*
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.RoleplayRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoleplayRepositoryImpl @Inject constructor(
    private val roleplaySessionDao: RoleplaySessionDao,
) : RoleplayRepository {

    override fun getAllSessions(): Flow<List<RoleplaySession>> =
        roleplaySessionDao.getAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getSessionById(id: String): RoleplaySession? =
        roleplaySessionDao.getById(id)?.toDomain()

    override suspend fun getSessionByConversationId(conversationId: String): RoleplaySession? =
        roleplaySessionDao.getByConversationId(conversationId)?.toDomain()

    override suspend fun createSession(session: RoleplaySession): String {
        roleplaySessionDao.insert(session.toEntity())
        return session.id
    }

    override suspend fun updateSession(session: RoleplaySession) =
        roleplaySessionDao.update(session.toEntity())

    override suspend fun deleteSession(id: String) =
        roleplaySessionDao.deleteById(id)
}
