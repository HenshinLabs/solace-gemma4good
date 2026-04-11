package com.masterllm.runtime.imagegen

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine for running image generation models (Stable Diffusion) via Chaquopy/diffusers.
 * Python bridge will be added in a later phase.
 */
@Singleton
class ImageGenEngine @Inject constructor() {
    fun isAvailable(): Boolean = false
}
