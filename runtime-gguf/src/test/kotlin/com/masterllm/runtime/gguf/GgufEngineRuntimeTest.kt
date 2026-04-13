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
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@Ignore("Requires packaged Android native GGUF libraries")
@OptIn(ExperimentalCoroutinesApi::class)
class GgufEngineRuntimeTest {

    @Test
    fun loadAndGenerate_worksWithValidGgufHeader() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns createTempDir(prefix = "gguf-runtime-")

        val ggufFile = createMinimalGgufFile()
        try {
            val engine = GgufEngine(context)
            engine.load(
                modelPath = ggufFile.absolutePath,
                params = InferenceParams(
                    contextSize = 2048,
                    numThreads = 1,
                    useMmap = false,
                    useMlock = false,
                ),
            )
            assertThat(engine.isModelLoaded()).isTrue()

            val output = engine
                .getResponseAsFlow("hello on-device runtime")
                .take(32)
                .toList()
                .joinToString(separator = "")

            assertThat(output).isNotEmpty()
            assertThat(output.length).isGreaterThan(16)

            engine.close()
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
