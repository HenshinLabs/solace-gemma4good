package com.masterllm.core.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and extracts the Vosk speech recognition model.
 * Model: vosk-model-small-en-us-0.15 (~40 MB)
 */
class VoskModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "Solace.VoskModelDL"
        private val MODEL_URLS = listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        )
        private const val MODEL_DIR = "vosk-model"
        private const val PREFS_NAME = "vosk_model_prefs"
        private const val KEY_DOWNLOADED = "model_downloaded"
        private const val BUFFER_SIZE = 16384
        private const val MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 60000
        private const val READ_TIMEOUT_MS = 300000
    }

    sealed class DownloadStatus {
        data object Checking : DownloadStatus()
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadStatus() {
            val progressPercent: Float
                get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) * 100f else 0f
        }
        data object Extracting : DownloadStatus()
        data object Ready : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isModelReady(): Boolean {
        val modelDir = File(context.filesDir, MODEL_DIR)
        return prefs.getBoolean(KEY_DOWNLOADED, false) &&
            modelDir.exists() &&
            modelDir.list()?.isNotEmpty() == true
    }

    fun ensureModelReady(): Flow<DownloadStatus> = flow {
        emit(DownloadStatus.Checking)

        if (isModelReady()) {
            emit(DownloadStatus.Ready)
            return@flow
        }

        val modelDir = File(context.filesDir, MODEL_DIR)
        modelDir.mkdirs()

        val zipFile = File(context.cacheDir, "vosk-model.zip")
        var downloaded = false
        var lastError: Exception? = null

        // Try each URL with retries
        for (url in MODEL_URLS) {
            if (downloaded) break
            for (attempt in 1..MAX_RETRIES) {
                try {
                    Log.i(TAG, "Downloading from $url (attempt $attempt/$MAX_RETRIES)")
                    emit(DownloadStatus.Downloading(0, 0))
                    downloadFile(url, zipFile)
                    downloaded = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Download attempt $attempt failed for $url: ${e.message}")
                    zipFile.delete()
                    if (attempt < MAX_RETRIES) {
                        delay(2000L * attempt) // Backoff
                    }
                }
            }
        }

        if (!downloaded) {
            val msg = "Failed to download Vosk model after ${MODEL_URLS.size * MAX_RETRIES} attempts. " +
                "Last error: ${lastError?.message ?: "Unknown"}. " +
                "Please check your internet connection."
            Log.e(TAG, msg)
            modelDir.deleteRecursively()
            emit(DownloadStatus.Error(msg))
            return@flow
        }

        try {
            emit(DownloadStatus.Extracting)
            extractZip(zipFile, modelDir)
            zipFile.delete()

            prefs.edit().putBoolean(KEY_DOWNLOADED, true).apply()
            Log.i(TAG, "Vosk model extracted to: ${modelDir.absolutePath}")
            emit(DownloadStatus.Ready)
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}")
            modelDir.deleteRecursively()
            zipFile.delete()
            emit(DownloadStatus.Error("Extraction failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun downloadFile(
        urlStr: String,
        outputFile: File,
    ) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SolaceApp/1.0")
        conn.setRequestProperty("Accept", "application/octet-stream")

        try {
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw java.io.IOException("HTTP $responseCode from $urlStr")
            }

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

        } finally {
            conn.disconnect()
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream(), BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                // Prevent zip path traversal
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip path traversal detected: ${entry.name}")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (zip.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
