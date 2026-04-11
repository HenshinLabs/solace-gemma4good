package com.masterllm.runtime.gguf

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine for loading and running GGUF format models via llama.cpp JNI.
 * Native integration will be added in Phase 4.
 */
@Singleton
class GgufEngine @Inject constructor() {
    fun isAvailable(): Boolean = false // Will return true once JNI is loaded
}
