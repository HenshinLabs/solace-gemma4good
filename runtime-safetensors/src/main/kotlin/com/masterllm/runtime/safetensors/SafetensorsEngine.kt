package com.masterllm.runtime.safetensors

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine for loading and running SafeTensors models via Chaquopy/transformers.
 * Python bridge will be added in a later phase.
 */
@Singleton
class SafetensorsEngine @Inject constructor() {
    fun isAvailable(): Boolean = false
}
