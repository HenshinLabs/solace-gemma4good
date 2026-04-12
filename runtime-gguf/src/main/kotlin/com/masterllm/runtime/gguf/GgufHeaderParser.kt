package com.masterllm.runtime.gguf

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal data class GgufHeader(
    val version: Int,
    val tensorCount: Long,
    val metadataKvCount: Long,
)

internal object GgufHeaderParser {
    fun parse(file: File): GgufHeader {
        FileInputStream(file).channel.use { channel ->
            val headerBuffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            val read = channel.read(headerBuffer)
            require(read >= 24) { "Invalid GGUF header: expected 24 bytes, got $read" }

            headerBuffer.flip()
            val magicBytes = ByteArray(4)
            headerBuffer.get(magicBytes)
            val magic = String(magicBytes, StandardCharsets.US_ASCII)
            require(magic == "GGUF") { "Invalid GGUF magic: $magic" }

            val version = headerBuffer.int
            val tensorCount = headerBuffer.long
            val metadataKvCount = headerBuffer.long
            require(version in 1..10_000) { "Unsupported GGUF version: $version" }
            require(tensorCount >= 0) { "Corrupt GGUF tensor count" }
            require(metadataKvCount >= 0) { "Corrupt GGUF metadata count" }

            return GgufHeader(version, tensorCount, metadataKvCount)
        }
    }
}
