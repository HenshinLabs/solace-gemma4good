package com.masterllm.runtime.safetensors

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class SafetensorsEngineTest {

    @Test
    fun loadModel_validFile_parsesTensorInfo() = runTest {
        val file = createValidSafetensorsFile()

        try {
            val engine = SafetensorsEngine()
            val info = engine.loadModel(file.absolutePath).getOrThrow()

            assertThat(info.tensorCount).isEqualTo(1)
            assertThat(info.totalTensorBytes).isEqualTo(16L)
            assertThat(info.metadata).containsEntry("format", "pt")
            assertThat(info.tensors.first().name).isEqualTo("model.embed_tokens.weight")
            assertThat(info.tensors.first().shape).containsExactly(2L, 2L).inOrder()
            assertThat(engine.hasTensor("model.embed_tokens.weight")).isTrue()
            assertThat(engine.isAvailable()).isTrue()
        } finally {
            file.delete()
        }
    }

    @Test
    fun loadModel_wrongExtension_returnsFailure() = runTest {
        val file = File.createTempFile("weights", ".bin")
        file.writeBytes(byteArrayOf(0x00, 0x01))

        try {
            val engine = SafetensorsEngine()
            val result = engine.loadModel(file.absolutePath)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("Expected a .safetensors file")
        } finally {
            file.delete()
        }
    }

    @Test
    fun loadModel_missingFile_returnsFailure() = runTest {
        val engine = SafetensorsEngine()
        val result = engine.loadModel("/tmp/does-not-exist-model.safetensors")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("does not exist")
    }

    private fun createValidSafetensorsFile(): File {
        val headerJson =
            """{"__metadata__":{"format":"pt"},"model.embed_tokens.weight":{"dtype":"F32","shape":[2,2],"data_offsets":[0,16]}}"""

        val headerBytes = headerJson.toByteArray(StandardCharsets.UTF_8)
        val headerLength = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(headerBytes.size.toLong())
            .array()

        val tensorBytes = ByteArray(16) { index -> index.toByte() }

        return File.createTempFile("model", ".safetensors").also { file ->
            file.outputStream().use { out ->
                out.write(headerLength)
                out.write(headerBytes)
                out.write(tensorBytes)
            }
        }
    }
}
