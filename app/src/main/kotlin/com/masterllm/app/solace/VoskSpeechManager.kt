package com.masterllm.app.solace

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

/**
 * Offline speech recognition using Vosk. Replaces the unreliable Android SpeechRecognizer.
 * Requires a Vosk model to be downloaded to context.filesDir/vosk-model/.
 */
class VoskSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "Solace.VoskASR"
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_DIR = "vosk-model"
    }

    enum class ListeningState {
        IDLE, LOADING_MODEL, LISTENING, PROCESSING, ERROR
    }

    data class AsrState(
        val state: ListeningState = ListeningState.IDLE,
        val partialTranscript: String = "",
        val finalTranscript: String = "",
        val error: String? = null,
    )

    private val _asrState = MutableStateFlow(AsrState())
    val asrState: StateFlow<AsrState> = _asrState.asStateFlow()

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var onFinalTranscript: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isAvailable(): Boolean = model != null

    /**
     * Initialize the Vosk model. Call once at app start or on first use.
     * Model must be downloaded to context.filesDir/vosk-model/ beforehand.
     */
    fun initModel(onReady: (Boolean) -> Unit) {
        _asrState.value = AsrState(state = ListeningState.LOADING_MODEL)
        scope.launch {
            try {
                val modelPath = "${context.filesDir.absolutePath}/$MODEL_DIR"
                val m = Model(modelPath)
                model = m
                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: IOException) {
                Log.e(TAG, "Model load failed: ${e.message}")
                _asrState.value = AsrState(
                    state = ListeningState.ERROR,
                    error = "Speech model not found. Download it in Settings."
                )
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }

    fun startListening(onResult: (String) -> Unit) {
        if (!hasMicPermission()) {
            _asrState.value = AsrState(state = ListeningState.ERROR, error = "Microphone permission required")
            return
        }
        val m = model
        if (m == null) {
            _asrState.value = AsrState(state = ListeningState.ERROR, error = "Speech model not loaded")
            return
        }

        onFinalTranscript = onResult
        stopListening()

        try {
            val recognizer = Recognizer(m, SAMPLE_RATE)
            recognizer.setMaxAlternatives(0)
            recognizer.setWords(true)

            speechService = SpeechService(recognizer, SAMPLE_RATE)
            _asrState.value = AsrState(state = ListeningState.LISTENING)

            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parseVoskResult(hypothesis)
                    if (text.isNotBlank()) {
                        _asrState.value = _asrState.value.copy(partialTranscript = text)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = parseVoskResult(hypothesis)
                    _asrState.value = _asrState.value.copy(
                        state = ListeningState.IDLE,
                        finalTranscript = text,
                        partialTranscript = "",
                    )
                    if (text.isNotBlank()) {
                        onFinalTranscript?.invoke(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = parseVoskResult(hypothesis)
                    _asrState.value = AsrState(
                        state = ListeningState.IDLE,
                        finalTranscript = text,
                    )
                    if (text.isNotBlank()) {
                        onFinalTranscript?.invoke(text)
                    }
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "Vosk error: ${exception?.message}")
                    _asrState.value = AsrState(
                        state = ListeningState.ERROR,
                        error = "Recognition error: ${exception?.message}"
                    )
                }

                override fun onTimeout() {
                    _asrState.value = _asrState.value.copy(
                        state = ListeningState.IDLE,
                        finalTranscript = _asrState.value.partialTranscript,
                    )
                    val text = _asrState.value.finalTranscript
                    if (text.isNotBlank()) {
                        onFinalTranscript?.invoke(text)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            _asrState.value = AsrState(state = ListeningState.ERROR, error = "Failed to start: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop error: ${e.message}")
        }
        speechService = null
    }

    fun destroy() {
        stopListening()
        model?.close()
        model = null
        scope.cancel()
    }

    private fun parseVoskResult(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (e: Exception) {
            json.trim()
        }
    }
}
