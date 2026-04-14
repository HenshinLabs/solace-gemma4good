package com.masterllm.runtime.safetensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceSafetensorsLoadTest {

    @Test
    fun safetensors_load_succeeds_and_reports_metadata() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val providedPath = args.getString("model_path")

        val safetensorsFile = providedPath
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile && it.length() > 0L }
            ?: createValidSafetensorsFile()

        try {
            val engine = SafetensorsEngine()
            val modelInfo = engine.loadModel(safetensorsFile.absolutePath).getOrThrow()

            assertTrue("Engine should report loaded state", engine.isAvailable())
            assertEquals("Expected exactly one tensor in fixture", 1, modelInfo.tensorCount)
            assertTrue(
                "Expected metadata format=pt, got ${modelInfo.metadata}",
                modelInfo.metadata["format"] == "pt"
            )
            assertTrue(
                "Expected known tensor name to be present",
                engine.hasTensor("model.embed_tokens.weight")
            )
        } finally {
            if (providedPath == null) {
                safetensorsFile.delete()
            }
        }
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

        val targetDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File.createTempFile("device_model", ".safetensors", targetDir).also { file ->
            file.outputStream().use { out ->
                out.write(headerLength)
                out.write(headerBytes)
                out.write(tensorBytes)
            }
        }
    }
}
