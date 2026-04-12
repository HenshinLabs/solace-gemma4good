package com.masterllm.runtime.gguf

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.masterllm.core.domain.model.InferenceParams
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class GgufEngineRuntimeTest {

    @Test
    fun loadAndGenerate_worksWithValidGgufHeader() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns createTempDir(prefix = "gguf-runtime-")

        val ggufFile = createMinimalGgufFile()
        try {
            val engine = GgufEngine(context)
            engine.updateRuntimeConfig(
                engine.getRuntimeConfig().copy(
                    enableGpuOffload = false,
                    streamDelayMs = 0L,
                )
            )

            val loadResult = engine.loadModel(
                path = ggufFile.absolutePath,
                threadCount = 1,
                gpuLayers = 0,
                contextSize = 2048,
            )
            assertThat(loadResult.isSuccess).isTrue()
            assertThat(engine.isModelLoaded()).isTrue()

            val output = engine.generate(
                prompt = "hello on-device runtime",
                params = InferenceParams(maxTokens = 64),
            ).take(32).toList().joinToString(separator = "")

            assertThat(output).isNotEmpty()
            assertThat(output.length).isGreaterThan(16)
        } finally {
            ggufFile.delete()
        }
    }

    private fun createMinimalGgufFile(): File {
        val file = File.createTempFile("runtime", ".gguf")

        val header = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("GGUF".toByteArray(StandardCharsets.US_ASCII))
                putInt(3)
                putLong(1L)
                putLong(0L)
            }
            .array()

        file.outputStream().use { out ->
            out.write(header)
            out.write(ByteArray(128) { 0x1 })
        }
        return file
    }
}
