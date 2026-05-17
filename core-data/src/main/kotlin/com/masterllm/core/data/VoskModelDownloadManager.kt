package com.masterllm.core.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model"
        private const val PREFS_NAME = "vosk_model_prefs"
        private const val KEY_DOWNLOADED = "model_downloaded"
        private const val BUFFER_SIZE = 8192
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

        try {
            // Download zip
            emit(DownloadStatus.Downloading(0, 0))

            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            val totalBytes = conn.contentLength.toLong()

            val zipFile = File(context.cacheDir, "vosk-model.zip")
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastEmit = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 350L) {
                            emit(DownloadStatus.Downloading(totalRead, totalBytes))
                            lastEmit = now
                        }
                    }
                }
            }
            conn.disconnect()

            // Extract zip
            emit(DownloadStatus.Extracting)
            extractZip(zipFile, modelDir)
            zipFile.delete()

            prefs.edit().putBoolean(KEY_DOWNLOADED, true).apply()
            Log.i(TAG, "Vosk model extracted to: ${modelDir.absolutePath}")
            emit(DownloadStatus.Ready)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            modelDir.deleteRecursively()
            emit(DownloadStatus.Error("Download failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
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
