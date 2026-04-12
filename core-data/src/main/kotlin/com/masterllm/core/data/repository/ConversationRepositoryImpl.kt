package com.masterllm.core.data.repository

import com.masterllm.core.data.db.*
import com.masterllm.core.data.mapper.*
import com.masterllm.core.domain.model.*
import com.masterllm.core.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> =
        conversationDao.getAll().map { list -> list.map { it.toDomain() } }

    override fun getConversationsByMode(mode: ConversationMode): Flow<List<Conversation>> =
        conversationDao.getByMode(mode.name).map { list -> list.map { it.toDomain() } }

    override suspend fun getConversationById(id: String): Conversation? =
        conversationDao.getById(id)?.toDomain()

    override suspend fun createConversation(conversation: Conversation): String {
        conversationDao.insert(conversation.toEntity())
        return conversation.id
    }

    override suspend fun updateConversation(conversation: Conversation) =
        conversationDao.update(conversation.toEntity())

    override suspend fun deleteConversation(id: String) =
        conversationDao.deleteById(id)

    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> =
        messageDao.getForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun addMessage(message: Message): String {
        messageDao.insert(message.toEntity())
        return message.id
    }

    override suspend fun updateMessage(message: Message) =
        messageDao.update(message.toEntity())

    override suspend fun deleteMessage(messageId: String) =
        messageDao.deleteById(messageId)

    override suspend fun deleteMessagesAfter(conversationId: String, timestamp: Long) =
        messageDao.deleteAfter(conversationId, timestamp)

    override suspend fun getMessageCount(conversationId: String): Int =
        messageDao.countForConversation(conversationId)
}
