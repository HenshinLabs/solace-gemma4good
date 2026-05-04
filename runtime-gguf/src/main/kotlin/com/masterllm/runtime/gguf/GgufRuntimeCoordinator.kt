package com.masterllm.runtime.gguf

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates access to the singleton GGUF runtime across features.
 *
 * Chat and Roleplay both use the same native engine instance, so they must
 * share one lock to avoid concurrent close/load/generation races.
 */
@Singleton
class GgufRuntimeCoordinator @Inject constructor() {
    private val engineMutex = Mutex()

    suspend fun <T> withEngineLock(action: suspend () -> T): T = engineMutex.withLock { action() }
}
