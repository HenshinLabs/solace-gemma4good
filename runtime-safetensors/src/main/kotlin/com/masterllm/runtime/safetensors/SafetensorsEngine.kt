package com.masterllm.runtime.safetensors

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Engine for loading and running SafeTensors models via Chaquopy/transformers.
 * This implementation provides a production-safe parser/validator so the
 * app can load and inspect local .safetensors weights before inference.
 */
@Singleton
class SafetensorsEngine @Inject constructor() {

    data class TensorInfo(
        val name: String,
        val dtype: String,
        val shape: List<Long>,
        val byteOffsetStart: Long,
        val byteOffsetEnd: Long,
    ) {
        val byteSize: Long get() = byteOffsetEnd - byteOffsetStart
    }

    data class ModelInfo(
        val path: String,
        val fileSizeBytes: Long,
        val headerSizeBytes: Long,
        val tensorCount: Int,
        val totalTensorBytes: Long,
        val dtypeHistogram: Map<String, Int>,
        val metadata: Map<String, String>,
        val tensors: List<TensorInfo>,
    )

    private var loadedModel: ModelInfo? = null

    fun isAvailable(): Boolean = loadedModel != null

    fun getLoadedModelInfo(): ModelInfo? = loadedModel

    suspend fun loadModel(path: String): Result<ModelInfo> {
        return runCatching {
            withContext(Dispatchers.IO) {
                val modelFile = File(path)
                require(modelFile.exists() && modelFile.isFile) {
                    "SafeTensors file does not exist: $path"
                }
                require(modelFile.extension.equals("safetensors", ignoreCase = true)) {
                    "Expected a .safetensors file, got: ${modelFile.name}"
                }

                val parsed = parseModel(modelFile)
                loadedModel = parsed
                Timber.i(
                    "SafetensorsEngine: Loaded ${modelFile.name} (${parsed.tensorCount} tensors, " +
                        "${parsed.totalTensorBytes} bytes of tensor data)"
                )
                parsed
            }
        }.onFailure { error ->
            loadedModel = null
            Timber.e(error, "SafetensorsEngine: Failed to load model")
        }
    }

    fun unloadModel() {
        loadedModel = null
    }

    fun hasTensor(tensorName: String): Boolean {
        return loadedModel?.tensors?.any { it.name == tensorName } == true
    }

    private fun parseModel(file: File): ModelInfo {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            require(fileSize > 8L) { "SafeTensors file too small: ${file.name}" }

            val headerSizeBytes = ByteArray(8)
            raf.readFully(headerSizeBytes)
            val headerSize = ByteBuffer.wrap(headerSizeBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long

            require(headerSize > 0L) { "Invalid SafeTensors header size: $headerSize" }
            require(headerSize < fileSize - 8L) {
                "Corrupt SafeTensors header length: $headerSize (file size: $fileSize)"
            }

            val headerBytes = ByteArray(headerSize.toInt())
            raf.readFully(headerBytes)
            val headerJson = String(headerBytes, StandardCharsets.UTF_8)
            val root = JsonParser.parseString(headerJson).asJsonObject

            val metadataMap = mutableMapOf<String, String>()
            root.getAsJsonObject("__metadata__")?.let { meta ->
                for ((key, value) in meta.entrySet()) {
                    metadataMap[key] = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        value.asString
                    } else {
                        value.toString()
                    }
                }
            }

            val dataSectionOffset = 8L + headerSize
            val tensors = mutableListOf<TensorInfo>()
            val dtypeHistogram = linkedMapOf<String, Int>()
            var totalTensorBytes = 0L

            for ((key, tensorElement) in root.entrySet()) {
                if (key == "__metadata__") continue

                require(tensorElement.isJsonObject) {
                    "Tensor entry '$key' is not an object"
                }
                val tensorObj = tensorElement.asJsonObject

                val dtype = tensorObj.get("dtype")?.asString.orEmpty()
                require(dtype.isNotBlank()) { "Tensor '$key' missing dtype" }

                val shapeJson = tensorObj.getAsJsonArray("shape")
                    ?: throw IllegalArgumentException("Tensor '$key' missing shape")
                val shape = buildList(shapeJson.size()) {
                    for (index in 0 until shapeJson.size()) {
                        add(shapeJson[index].asLong)
                    }
                }

                val offsets = tensorObj.getAsJsonArray("data_offsets")
                    ?: throw IllegalArgumentException("Tensor '$key' missing data_offsets")
                require(offsets.size() == 2) {
                    "Tensor '$key' has invalid data_offsets length ${offsets.size()}"
                }

                val relativeStart = offsets[0].asLong
                val relativeEnd = offsets[1].asLong
                require(relativeStart >= 0L && relativeEnd >= 0L && relativeEnd >= relativeStart) {
                    "Tensor '$key' has invalid offsets [$relativeStart, $relativeEnd]"
                }

                val absoluteStart = dataSectionOffset + relativeStart
                val absoluteEnd = dataSectionOffset + relativeEnd
                require(absoluteEnd <= fileSize) {
                    "Tensor '$key' extends beyond file bounds"
                }

                val tensorInfo = TensorInfo(
                    name = key,
                    dtype = dtype,
                    shape = shape,
                    byteOffsetStart = absoluteStart,
                    byteOffsetEnd = absoluteEnd,
                )
                tensors += tensorInfo
                dtypeHistogram[dtype] = (dtypeHistogram[dtype] ?: 0) + 1
                totalTensorBytes += tensorInfo.byteSize
            }

            require(tensors.isNotEmpty()) { "No tensors found in ${file.name}" }

            return ModelInfo(
                path = file.absolutePath,
                fileSizeBytes = fileSize,
                headerSizeBytes = headerSize,
                tensorCount = tensors.size,
                totalTensorBytes = totalTensorBytes,
                dtypeHistogram = dtypeHistogram,
                metadata = metadataMap,
                tensors = tensors,
            )
        }
    }
}
