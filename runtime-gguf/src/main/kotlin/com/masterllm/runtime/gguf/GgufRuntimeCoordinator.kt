package com.masterllm.runtime.gguf

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Coordinates access to the singleton GGUF runtime across features.
 *
 * Chat and Roleplay both use the same native engine instance, so they must
 * share one lock to avoid concurrent close/load/generation races.
 */
@Singleton
class GgufRuntimeCoordinator @Inject constructor() {
    val engineMutex: Mutex = Mutex()
}
