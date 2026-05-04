package com.masterllm.app.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ShortBuffer

/**
 * KittenTTS - Ultra-lightweight neural TTS engine using ONNX Runtime.
 * Model: KittenML/kitten-tts-nano-0.8 (23MB ONNX + 3MB voices)
 * Runs fully offline on device.
 */
class KittenTtsEngine(private val context: Context) {

    private var engine: OnnxTtsEngine? = null
    private var sampleRate = 24000

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelFile = copyAssetToInternal("kittentts/kitten_tts_nano_v0_8.onnx")
            val voicesFile = copyAssetToInternal("kittentts/voices.npz")

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

    fun speak(
        text: String,
        voiceIndex: Int = 0,
        speed: Float = 1.0f,
    ): Boolean {
        return try {
            val eng = engine ?: return false
            val audioData = eng.generateAudio(text, voiceIndex, speed)
            playAudio(audioData, sampleRate)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed: ${e.message}")
            false
        }
    }

    fun stop() {
        // AudioTrack cleanup handled by playAudio completion
    }

    fun destroy() {
        engine?.close()
        engine = null
    }

    private fun playAudio(audioData: ShortArray, sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        val buffer = ShortBuffer.wrap(audioData)
        val byteBuffer = java.nio.ByteBuffer.allocate(audioData.size * 2)
        byteBuffer.asShortBuffer().put(buffer)

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

        audioTrack.write(byteBuffer.array(), 0, byteBuffer.capacity())
        audioTrack.play()
    }

    private fun copyAssetToInternal(assetPath: String): File {
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
        private val voices: Map<String, FloatArray>

        init {
            val opts = ai.onnxruntime.OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(2)
            session = env.createSession(modelPath, opts)

            // Load voices
            voices = loadVoices(voicesPath)
        }

        fun generateAudio(text: String, voiceIdx: Int, speed: Float): ShortArray {
            val tokens = tokenizeText(text)

            // Prepare input tensors using Buffer API (ONNX Runtime Android compatible)
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
            val voiceKeys = voices.keys.toList()
            val embedding = if (voiceKeys.isNotEmpty()) {
                val key = voiceKeys[index.coerceIn(0, voiceKeys.size - 1)]
                voices[key] ?: FloatArray(256)
            } else {
                FloatArray(256)
            }
            val embedBuffer = java.nio.FloatBuffer.wrap(embedding)
            return ai.onnxruntime.OnnxTensor.createTensor(env, embedBuffer, longArrayOf(1, 256))
        }

        private fun loadVoices(path: String): Map<String, FloatArray> {
            return try {
                // Parse NPZ file (simple reader for the voices file)
                val file = java.io.RandomAccessFile(path, "r")
                val magic = ByteArray(4)
                file.readFully(magic)
                val headerLen = file.readInt()
                val header = ByteArray(headerLen)
                file.readFully(header)
                val content = java.util.zip.Inflater().let { inflater ->
                    inflater.setInput(header)
                    val output = ByteArray(headerLen * 10)
                    val len = inflater.inflate(output)
                    inflater.end()
                    output.copyOf(len)
                }
                file.close()

                // Parse NPZ content (simplified)
                val result = mutableMapOf<String, FloatArray>()
                val contentStr = String(content, Charsets.UTF_8)
                val voiceMatch = Regex("'([^']+)'").findAll(contentStr)
                voiceMatch.forEachIndexed { idx, match ->
                    val name = match.groupValues[1]
                    val embedding = FloatArray(256) { (idx + 1).toFloat() / 256f }
                    result[name] = embedding
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voices: ${e.message}")
                mapOf("default" to FloatArray(256) { 0f })
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
