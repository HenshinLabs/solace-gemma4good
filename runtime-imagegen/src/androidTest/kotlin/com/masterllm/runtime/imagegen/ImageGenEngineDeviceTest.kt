package com.masterllm.runtime.imagegen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.masterllm.runtime.safetensors.SafetensorsEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class ImageGenEngineDeviceTest {

    @Test
    fun loadAndGenerate_diffusersFixture_emitsFinalBitmap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureDir = File(context.cacheDir, "diffusers-fixture").apply {
            deleteRecursively()
            mkdirs()
        }

        writeDiffusersFixture(fixtureDir)

        val engine = ImageGenEngine(context, SafetensorsEngine())
        val loadResult = engine.loadModel(fixtureDir.absolutePath)
        assertThat(loadResult.isSuccess).isTrue()

        val emissions = engine.generate(
            prompt = "forest landscape",
            negativePrompt = "blurry",
            steps = 4,
            width = 256,
            height = 256,
            seed = 42L,
        ).toList()

        val complete = emissions.lastOrNull()
        assertThat(complete).isNotNull()
        assertThat(complete is ImageGenProgress.Complete).isTrue()

        val bitmap = (complete as ImageGenProgress.Complete).bitmap
        assertThat(bitmap.width).isEqualTo(256)
        assertThat(bitmap.height).isEqualTo(256)
    }

    private fun writeDiffusersFixture(root: File) {
        File(root, "model_index.json").writeText(
            """
            {
              "scheduler": {
                "_class_name": "EulerDiscreteScheduler"
              }
            }
            """.trimIndent()
        )

        val unet = File(root, "unet")
        unet.mkdirs()
        writeMinimalSafetensors(File(unet, "diffusion_pytorch_model.safetensors"))
    }

    private fun writeMinimalSafetensors(file: File) {
        val headerJson =
            """
            {
              "tiny": {
                "dtype": "F32",
                "shape": [1],
                "data_offsets": [0, 4]
              }
            }
            """.trimIndent().replace("\n", "")

        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        val lengthPrefix = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(headerBytes.size.toLong())
            .array()

        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            output.write(lengthPrefix)
            output.write(headerBytes)
            output.write(ByteArray(4) { 0x1 })
        }
    }
}
