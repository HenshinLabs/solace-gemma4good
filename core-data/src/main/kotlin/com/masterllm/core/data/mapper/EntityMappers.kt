package com.masterllm.core.data.mapper

import com.masterllm.core.data.db.*
import com.masterllm.core.domain.model.*

// ─── Model Mappers ──────────────────────────────────────────────

fun ModelEntity.toDomain(): LlmModel = LlmModel(
    id = id,
    repoId = repoId,
    fileName = fileName,
    displayName = displayName,
    format = try { ModelFormat.valueOf(format) } catch (_: Exception) { ModelFormat.GGUF },
    sizeBytes = sizeBytes,
    quantization = quantization,
    localPath = localPath,
    downloadState = try { DownloadState.valueOf(downloadState) } catch (_: Exception) { DownloadState.NOT_DOWNLOADED },
    contextLength = contextLength,
    parameterCount = parameterCount,
    downloadedAt = downloadedAt,
    description = description,
)

fun LlmModel.toEntity(): ModelEntity = ModelEntity(
    id = id,
    repoId = repoId,
    fileName = fileName,
    displayName = displayName,
    format = format.name,
    sizeBytes = sizeBytes,
    quantization = quantization,
    localPath = localPath,
    downloadState = downloadState.name,
    contextLength = contextLength,
    parameterCount = parameterCount,
    downloadedAt = downloadedAt,
    description = description,
)

// ─── Conversation Mappers ───────────────────────────────────────

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    mode = try { ConversationMode.valueOf(mode) } catch (_: Exception) { ConversationMode.CHAT },
    modelId = modelId,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    mode = mode.name,
    modelId = modelId,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
)

// ─── Message Mappers ────────────────────────────────────────────

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = try { MessageRole.valueOf(role) } catch (_: Exception) { MessageRole.USER },
    content = content,
    timestamp = timestamp,
    attachedImagePath = attachedImagePath,
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    timestamp = timestamp,
    attachedImagePath = attachedImagePath,
)

// ─── RoleplaySession Mappers ────────────────────────────────────

fun RoleplaySessionEntity.toDomain(): RoleplaySession = RoleplaySession(
    id = id,
    conversationId = conversationId,
    title = title,
    genre = genre,
    premise = premise,
    aiCharacterName = aiCharacterName,
    aiCharacterDescription = aiCharacterDescription,
    aiCharacterAppearance = aiCharacterAppearance,
    userCharacterName = userCharacterName,
    userCharacterDescription = userCharacterDescription,
    userCharacterAppearance = userCharacterAppearance,
    worldDetails = worldDetails,
    writingStyle = writingStyle,
    imageModelId = imageModelId,
    visualStyle = try { VisualStyle.valueOf(visualStyle) } catch (_: Exception) { VisualStyle.FANTASY_ART },
    imageFrequency = try { ImageFrequency.valueOf(imageFrequency) } catch (_: Exception) { ImageFrequency.EVERY_RESPONSE },
    narrativeResponseCount = narrativeResponseCount,
    lastImagePath = lastImagePath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun RoleplaySession.toEntity(): RoleplaySessionEntity = RoleplaySessionEntity(
    id = id,
    conversationId = conversationId,
    title = title,
    genre = genre,
    premise = premise,
    aiCharacterName = aiCharacterName,
    aiCharacterDescription = aiCharacterDescription,
    aiCharacterAppearance = aiCharacterAppearance,
    userCharacterName = userCharacterName,
    userCharacterDescription = userCharacterDescription,
    userCharacterAppearance = userCharacterAppearance,
    worldDetails = worldDetails,
    writingStyle = writingStyle,
    imageModelId = imageModelId,
    visualStyle = visualStyle.name,
    imageFrequency = imageFrequency.name,
    narrativeResponseCount = narrativeResponseCount,
    lastImagePath = lastImagePath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ─── CharacterVisualCache Mappers ───────────────────────────────

fun CharacterVisualCacheEntity.toDomain(): CharacterVisualEntry = CharacterVisualEntry(
    characterName = characterName,
    sessionId = sessionId,
    anchorPrompt = anchorPrompt,
    lastImagePath = lastImagePath,
    updatedAt = updatedAt,
)

fun CharacterVisualEntry.toEntity(): CharacterVisualCacheEntity = CharacterVisualCacheEntity(
    characterName = characterName,
    sessionId = sessionId,
    anchorPrompt = anchorPrompt,
    lastImagePath = lastImagePath,
    updatedAt = updatedAt,
)
