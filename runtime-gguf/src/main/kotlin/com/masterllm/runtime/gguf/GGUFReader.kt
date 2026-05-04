package com.masterllm.runtime.gguf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class GGUFReader {
    companion object {
        init {
            System.loadLibrary("ggufreader")
        }
    }

    private var nativeHandle: Long = 0L

    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        if (nativeHandle != 0L) {
            runCatching { closeNativeHandle(nativeHandle) }
        }
        nativeHandle = getGGUFContextNativeHandle(modelPath)
    }

    fun close() {
        if (nativeHandle != 0L) {
            runCatching { closeNativeHandle(nativeHandle) }
            nativeHandle = 0L
        }
    }

    fun getContextSize(): Long? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val contextSize = getContextSize(nativeHandle)
        return if (contextSize == -1L) {
            null
        } else {
            contextSize
        }
    }

    fun getChatTemplate(): String? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val chatTemplate = getChatTemplate(nativeHandle)
        return chatTemplate.ifEmpty { null }
    }

    private external fun getGGUFContextNativeHandle(modelPath: String): Long
    private external fun getContextSize(nativeHandle: Long): Long
    private external fun getChatTemplate(nativeHandle: Long): String
    private external fun closeNativeHandle(nativeHandle: Long)
}
