package com.masterllm.runtime.gguf

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class GgufHeaderParserTest {

    @Test
    fun parse_validHeader_returnsExpectedValues() {
        val file = writeHeaderFile(
            magic = "GGUF",
            version = 3,
            tensorCount = 42L,
            metadataCount = 7L,
        )

        try {
            val header = GgufHeaderParser.parse(file)
            assertThat(header.version).isEqualTo(3)
            assertThat(header.tensorCount).isEqualTo(42L)
            assertThat(header.metadataKvCount).isEqualTo(7L)
        } finally {
            file.delete()
        }
    }

    @Test
    fun parse_invalidMagic_throwsIllegalArgumentException() {
        val file = writeHeaderFile(
            magic = "BEEF",
            version = 3,
            tensorCount = 1L,
            metadataCount = 1L,
        )

        try {
            assertThrows(IllegalArgumentException::class.java) {
                GgufHeaderParser.parse(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun parse_unsupportedVersion_throwsIllegalArgumentException() {
        val file = writeHeaderFile(
            magic = "GGUF",
            version = 0,
            tensorCount = 1L,
            metadataCount = 1L,
        )

        try {
            assertThrows(IllegalArgumentException::class.java) {
                GgufHeaderParser.parse(file)
            }
        } finally {
            file.delete()
        }
    }

    private fun writeHeaderFile(
        magic: String,
        version: Int,
        tensorCount: Long,
        metadataCount: Long,
    ): File {
        val file = File.createTempFile("gguf-header", ".gguf")
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(magic.toByteArray(StandardCharsets.US_ASCII))
            putInt(version)
            putLong(tensorCount)
            putLong(metadataCount)
        }.array()
        file.writeBytes(bytes)
        return file
    }
}
