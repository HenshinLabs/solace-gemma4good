package com.masterllm.runtime.imagegen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class ImageModelInspectorTest {

    @Test
    fun detectBackend_safetensorsFile_returnsSafetensorsBackend() {
        val file = File.createTempFile("weights", ".safetensors")
        try {
            val backend = ImageModelInspector.detectBackend(file)
            assertThat(backend).isEqualTo(ImageBackendType.SAFETENSORS)
        } finally {
            file.delete()
        }
    }

    @Test
    fun detectBackend_directoryWithModelIndex_returnsDiffusersBackend() {
        val dir = createTempDir(prefix = "diffusers-")
        try {
            File(dir, "model_index.json").writeText("{}")
            File(dir, "unet.safetensors").writeText("weights")

            val backend = ImageModelInspector.detectBackend(dir)
            assertThat(backend).isEqualTo(ImageBackendType.DIFFUSERS)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseConditioningScale_eulerScheduler_returnsExpectedScale() {
        val file = File.createTempFile("model_index", ".json")
        try {
            file.writeText(
                """
                {
                  "scheduler": {
                    "_class_name": "EulerDiscreteScheduler"
                  }
                }
                """.trimIndent()
            )

            val scale = ImageModelInspector.parseConditioningScale(file)
            assertThat(scale).isEqualTo(1.18f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalizeDimension_clampsAndRoundsToMultipleOf64() {
        assertThat(ImageModelInspector.normalizeDimension(255)).isEqualTo(256)
        assertThat(ImageModelInspector.normalizeDimension(513)).isEqualTo(512)
        assertThat(ImageModelInspector.normalizeDimension(2048)).isEqualTo(1024)
    }
}
