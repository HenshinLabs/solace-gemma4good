package com.masterllm.core.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KittenTtsEngine @Inject constructor() {

    private var engine: OnnxTtsEngine? = null
    private var sampleRate = 24000
    private var currentAudioTrack: AudioTrack? = null

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelFile = copyAssetToInternal(context, "kittentts/kitten_tts_nano_v0_8.onnx")
            val voicesFile = copyAssetToInternal(context, "kittentts/voices.npz")

            engine = OnnxTtsEngine(
                modelPath = modelFile.absolutePath,
                voicesPath = voicesFile.absolutePath,
            )
            Log.i(TAG, "KittenTTS engine initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "KittenTTS init failed: ${e.message}")
            false
        }
    }

    fun isAvailable(): Boolean = engine != null

    suspend fun speak(
        text: String,
        voiceIndex: Int = 0,
        speed: Float = 1.0f,
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            val eng = engine ?: return@withContext false
            stop()
            val audioData = eng.generateAudio(text, voiceIndex, speed)
            playAudio(audioData, sampleRate)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            currentAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        currentAudioTrack = null
    }

    fun destroy() {
        stop()
        engine?.close()
        engine = null
    }

    private fun playAudio(audioData: ShortArray, sampleRate: Int) {
        stop()
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val byteBuffer = ByteBuffer.allocate(audioData.size * 2)
        byteBuffer.asShortBuffer().put(audioData)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, byteBuffer.capacity()))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        currentAudioTrack = audioTrack
        audioTrack.write(byteBuffer.array(), 0, byteBuffer.capacity())
        audioTrack.play()
    }

    private fun copyAssetToInternal(context: Context, assetPath: String): File {
        val parts = assetPath.split("/")
        val fileName = parts.last()
        val targetDir = File(context.filesDir, "tts_models/${parts.dropLast(1).joinToString("/")}")
        targetDir.mkdirs()
        val targetFile = File(targetDir, fileName)
        if (!targetFile.exists()) {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return targetFile
    }

    private class OnnxTtsEngine(
        modelPath: String,
        voicesPath: String,
    ) : AutoCloseable {
        private val env = ai.onnxruntime.OrtEnvironment.getEnvironment("kitten-tts")
        private val session: ai.onnxruntime.OrtSession
        private val voices: List<FloatArray>

        init {
            val opts = ai.onnxruntime.OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(2)
            session = env.createSession(modelPath, opts)
            voices = loadVoices(voicesPath)
        }

        fun generateAudio(text: String, voiceIdx: Int, speed: Float): ShortArray {
            val tokens = tokenizeText(text)
            if (tokens.isEmpty()) return ShortArray(0)

            val inputIds = tokens.map { it.toLong() }.toLongArray()
            val inputShape = longArrayOf(1, tokens.size.toLong())
            val inputBuffer = java.nio.LongBuffer.wrap(inputIds)
            val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(env, inputBuffer, inputShape)

            val voiceTensor = selectVoice(voiceIdx)

            val results = session.run(mapOf(
                "input_ids" to inputTensor,
                "speaker_embedding" to voiceTensor,
            ))

            val output = results.get("audio") as ai.onnxruntime.OnnxTensor
            val totalSamples = output.info.shape[2].toInt()
            if (totalSamples <= 0) return ShortArray(0)

            val floatBuffer = output.getFloatBuffer()
            val audioFloats = FloatArray(totalSamples)
            floatBuffer.get(audioFloats)

            return audioFloats.map { (it * 32767).toInt().coerceIn(-32768, 32767).toShort() }.toShortArray()
        }

        private fun tokenizeText(text: String): List<Int> {
            val vocab = " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!?-'\"():;"
            return text.map { c ->
                val idx = vocab.indexOf(c)
                if (idx >= 0) idx + 1 else 0
            }.filter { it > 0 }.take(512)
        }

        private fun selectVoice(index: Int): ai.onnxruntime.OnnxTensor {
            val embedding = if (voices.isNotEmpty()) {
                voices[index.coerceIn(0, voices.size - 1)]
            } else {
                FloatArray(256)
            }
            val embedBuffer = java.nio.FloatBuffer.wrap(embedding)
            return ai.onnxruntime.OnnxTensor.createTensor(env, embedBuffer, longArrayOf(1, 256))
        }

        private fun loadVoices(path: String): List<FloatArray> {
            return try {
                val result = mutableListOf<FloatArray>()
                val file = File(path)
                if (!file.exists()) {
                    Log.w(TAG, "Voices file not found: $path")
                    return emptyList()
                }
                val zip = ZipInputStream(file.inputStream().buffered())
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".npy") || entry.name.contains("voice")) {
                        val bytes = zip.readBytes()
                        val floats = parseNpyFloats(bytes)
                        if (floats.isNotEmpty() && floats.size >= 256) {
                            result.add(floats)
                        }
                    }
                    entry = zip.nextEntry
                }
                zip.close()
                Log.i(TAG, "Loaded ${result.size} voices from NPZ")
                result
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voices: ${e.message}")
                // Create default voice embeddings as fallback
                List(5) { FloatArray(256) { (it + 1).toFloat() / 256f } }
            }
        }

        private fun parseNpyFloats(data: ByteArray): FloatArray {
            return try {
                val version = data.getOrNull(7) ?: return FloatArray(0)
                val headerLen = if (version <= 1) {
                    java.nio.ByteBuffer.wrap(data, 8, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                } else {
                    java.nio.ByteBuffer.wrap(data, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                }
                val headerStart = if (version <= 1) 10 else 12
                val header = String(data, headerStart, headerLen, Charsets.US_ASCII)
                val shapeMatch = Regex("\\(\\s*([0-9]+)\\s*,?\\s*([0-9]+)?\\s*\\)").find(header)
                val totalElements = if (shapeMatch != null) {
                    val dim1 = shapeMatch.groupValues[1].toIntOrNull() ?: 1
                    val dim2 = shapeMatch.groupValues[2].toIntOrNull() ?: 1
                    dim1 * dim2
                } else {
                    val descrMatch = Regex("'descr'.*'<f4'").find(header)
                    if (descrMatch != null) {
                        val shapeStr = header.substringAfter("'shape':").substringBefore("}").trim(' ', ',')
                        val dims = shapeStr.trim('(', ')').split(',').mapNotNull { it.trim().toIntOrNull() }
                        if (dims.isNotEmpty()) dims.fold(1) { a, b -> a * b } else data.size / 4 - 2
                    } else {
                        data.size / 4 - 2
                    }
                }
                if (totalElements <= 0) return FloatArray(0)
                val dataStart = headerStart + headerLen
                val padding = (dataStart % 64).let { if (it == 0) 0 else 64 - it }
                val dataOffset = dataStart + padding
                val expectedSize = totalElements * 4
                if (dataOffset + expectedSize > data.size) return FloatArray(0)
                val floatData = FloatArray(totalElements)
                java.nio.ByteBuffer.wrap(data, dataOffset, expectedSize)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .get(floatData)
                floatData
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse NPY: ${e.message}")
                FloatArray(0)
            }
        }

        override fun close() {
            session.close()
        }
    }

    companion object {
        private const val TAG = "KittenTtsEngine"
    }
}
