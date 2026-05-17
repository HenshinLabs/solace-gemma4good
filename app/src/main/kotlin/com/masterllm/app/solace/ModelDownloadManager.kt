package com.masterllm.app.solace

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "Solace.ModelDownloadMgr"

        // REVIEW: Update these after verifying the exact file from HuggingFace.
        // Using unsloth conversion — smaller file, widely tested.
        const val MODEL_FILENAME = "gemma-4-E2B-it-Q4_K_M.gguf"
        const val MODEL_SHA256 = "9378bc471710229ef165709b62e34bfb62231420ddaf6d729e727305b5b8672d"
        const val MODEL_SIZE_BYTES = 3_340_000_000L // ~3.11 GB — approximate
        const val MODEL_DISPLAY_NAME = "Gemma 4 E2B (Q4_K_M)"

        // REVIEW: This URL must point to a reliable CDN or direct HuggingFace download link.
        // The default points to the unsloth community GGUF repo on HuggingFace.
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"

        // mmproj for vision support
        const val MMPROJ_FILENAME = "gemma-4-E2B-it.BF16-mmproj.gguf"
        const val MMPROJ_DOWNLOAD_URL =
            "https://huggingface.co/bjivanovich/Gemma4-E2B-Vision-GGUF/resolve/main/gemma-4-E2B-it.BF16-mmproj.gguf"
        const val MMPROJ_SIZE_BYTES = 986_833_408L // ~941 MB

        const val CONTEXT_LENGTH = 131072 // 128K verified from model card
        const val ARCHITECTURE = "gemma4"
        const val EOS_TOKEN = "<eos>"

        private const val PREFS_NAME = "solace_model_prefs"
        private const val KEY_MODEL_READY = "model_ready"
        private const val KEY_MMPROJ_READY = "mmproj_ready"
        private const val KEY_MODEL_SHA = "model_sha256"

        private const val DOWNLOAD_BUFFER_SIZE = 65536
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 600_000 // 10 min for large file
    }

    sealed class DownloadStatus {
        data object CheckingLocal : DownloadStatus()
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long,
        ) : DownloadStatus() {
            val progressPercent: Float
                get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) * 100f else 0f
        }

        data object Verifying : DownloadStatus()
        data object Ready : DownloadStatus()
        data class Error(val message: String, val retryable: Boolean = true) : DownloadStatus()
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getModelDirectory(): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        dir.mkdirs()
        return dir
    }

    fun getModelFile(): File = File(getModelDirectory(), MODEL_FILENAME)

    fun isModelReady(): Boolean {
        val modelFile = getModelFile()
        return prefs.getBoolean(KEY_MODEL_READY, false) &&
            modelFile.exists() &&
            modelFile.length() > 0L
    }

    fun getMmprojFile(): File = File(getModelDirectory(), MMPROJ_FILENAME)

    fun isMmprojReady(): Boolean {
        val mmprojFile = getMmprojFile()
        return prefs.getBoolean(KEY_MMPROJ_READY, false) &&
            mmprojFile.exists() &&
            mmprojFile.length() > 0L
    }

    /**
     * Downloads the mmproj file for vision support.
     * Call after the main model is downloaded.
     */
    fun ensureMmprojReady(): Flow<DownloadStatus> = flow {
        emit(DownloadStatus.CheckingLocal)

        val mmprojFile = getMmprojFile()

        if (isMmprojReady()) {
            Log.i(TAG, "mmproj already available at: ${mmprojFile.absolutePath}")
            emit(DownloadStatus.Ready)
            return@flow
        }

        val partialFile = File(getModelDirectory(), "$MMPROJ_FILENAME.partial")

        if (mmprojFile.exists() && mmprojFile.length() > 0L) {
            markMmprojReady()
            emit(DownloadStatus.Ready)
            return@flow
        }

        // Download mmproj
        try {
            downloadModel(partialFile) { bytesDownloaded, totalBytes ->
                emit(DownloadStatus.Downloading(bytesDownloaded, totalBytes))
            }
        } catch (e: IOException) {
            partialFile.delete()
            emit(DownloadStatus.Error("mmproj download failed: ${e.message}", retryable = true))
            return@flow
        }

        // Move partial to final
        if (!partialFile.renameTo(mmprojFile)) {
            partialFile.copyTo(mmprojFile, overwrite = true)
            partialFile.delete()
        }

        markMmprojReady()
        Log.i(TAG, "mmproj ready: ${mmprojFile.absolutePath}")
        emit(DownloadStatus.Ready)
    }.flowOn(Dispatchers.IO)

    fun ensureModelReady(): Flow<DownloadStatus> = flow {
        emit(DownloadStatus.CheckingLocal)

        val modelFile = getModelFile()

        if (isModelReady()) {
            Log.i(TAG, "Model already available at: ${modelFile.absolutePath}")
            emit(DownloadStatus.Ready)
            return@flow
        }

        // Check if partial download exists (for resume)
        val partialFile = File(getModelDirectory(), "$MODEL_FILENAME.partial")

        if (modelFile.exists() && modelFile.length() > 0L) {
            // File exists but not verified — verify now
            emit(DownloadStatus.Verifying)
            if (verifySha256(modelFile)) {
                markModelReady()
                emit(DownloadStatus.Ready)
                return@flow
            } else {
                Log.w(TAG, "Existing model file failed SHA-256 verification, re-downloading")
                modelFile.delete()
            }
        }

        // Download
        try {
            downloadModel(partialFile) { bytesDownloaded, totalBytes ->
                emit(DownloadStatus.Downloading(bytesDownloaded, totalBytes))
            }
        } catch (e: IOException) {
            partialFile.delete()
            emit(DownloadStatus.Error("Download failed: ${e.message}", retryable = true))
            return@flow
        }

        // Move partial to final
        if (!partialFile.renameTo(modelFile)) {
            partialFile.copyTo(modelFile, overwrite = true)
            partialFile.delete()
        }

        // Verify
        emit(DownloadStatus.Verifying)
        if (verifySha256(modelFile)) {
            markModelReady()
            Log.i(TAG, "Model verified and ready: ${modelFile.absolutePath}")
            emit(DownloadStatus.Ready)
        } else {
            modelFile.delete()
            emit(DownloadStatus.Error("File integrity check failed. Please retry.", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadModel(
        targetFile: File,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        targetFile.parentFile?.mkdirs()

        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L

        val connection = URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Solace-Android/1.0")

        // Resume support
        if (existingBytes > 0L) {
            connection.setRequestProperty("Range", "bytes=$existingBytes-")
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode

            val totalBytes: Long
            val append: Boolean

            when (responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // Resume supported
                    totalBytes = existingBytes + connection.contentLengthLong
                    append = true
                }
                HttpURLConnection.HTTP_OK -> {
                    totalBytes = connection.contentLengthLong
                    append = false
                    if (existingBytes > 0L) {
                        // Server doesn't support resume — start over
                        targetFile.delete()
                    }
                }
                else -> {
                    throw IOException("HTTP $responseCode: ${connection.responseMessage}")
                }
            }

            var bytesDownloaded = if (append) existingBytes else 0L

            connection.inputStream.buffered(DOWNLOAD_BUFFER_SIZE).use { input ->
                FileOutputStream(targetFile, append).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!currentCoroutineContext().isActive) {
                            throw IOException("Download cancelled")
                        }
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        // Throttle progress updates to ~350ms
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 350L) {
                            onProgress(bytesDownloaded, totalBytes)
                            lastProgressUpdate = now
                        }
                    }
                    output.flush()
                }
            }
            onProgress(bytesDownloaded, totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha256(file: File): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(DOWNLOAD_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = hash.equals(MODEL_SHA256, ignoreCase = true)
            if (!matches) {
                Log.w(TAG, "SHA-256 mismatch: expected=$MODEL_SHA256, actual=$hash")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 verification failed: ${e.message}")
            false
        }
    }

    private fun markModelReady() {
        prefs.edit()
            .putBoolean(KEY_MODEL_READY, true)
            .putString(KEY_MODEL_SHA, MODEL_SHA256)
            .apply()
    }

    private fun markMmprojReady() {
        prefs.edit()
            .putBoolean(KEY_MMPROJ_READY, true)
            .apply()
    }

    fun getModelPath(): String = getModelFile().absolutePath
    fun getMmprojPath(): String = getMmprojFile().absolutePath

    fun getAvailableStorageBytes(): Long {
        return try {
            val dir = getModelDirectory()
            dir.mkdirs()
            dir.usableSpace
        } catch (e: Exception) {
            -1L
        }
    }

    fun deleteModel() {
        getModelFile().delete()
        File(getModelDirectory(), "$MODEL_FILENAME.partial").delete()
        prefs.edit()
            .putBoolean(KEY_MODEL_READY, false)
            .remove(KEY_MODEL_SHA)
            .apply()
    }
}
