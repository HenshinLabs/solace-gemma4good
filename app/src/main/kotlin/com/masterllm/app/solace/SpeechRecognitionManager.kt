package com.masterllm.app.solace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechRecognitionManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Solace.SpeechRecMgr"
    }

    enum class ListeningState {
        IDLE, LISTENING, PROCESSING, ERROR
    }

    data class AsrState(
        val state: ListeningState = ListeningState.IDLE,
        val partialTranscript: String = "",
        val finalTranscript: String = "",
        val error: String? = null,
    )

    private val _asrState = MutableStateFlow(AsrState())
    val asrState: StateFlow<AsrState> = _asrState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var onFinalTranscript: ((String) -> Unit)? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(onResult: (String) -> Unit) {
        if (!hasMicPermission()) {
            _asrState.value = AsrState(state = ListeningState.ERROR, error = "Microphone permission required")
            return
        }
        if (!isAvailable()) {
            _asrState.value = AsrState(state = ListeningState.ERROR, error = "Speech recognition not available on this device")
            return
        }

        onFinalTranscript = onResult
        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // REVIEW: Make language configurable in Settings (en-US vs en-IN vs auto)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        _asrState.value = AsrState(state = ListeningState.LISTENING)
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping speech recognizer: ${e.message}")
        }
        speechRecognizer = null
    }

    fun destroy() {
        stopListening()
        onFinalTranscript = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _asrState.value = AsrState(state = ListeningState.LISTENING)
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _asrState.value = _asrState.value.copy(state = ListeningState.PROCESSING)
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Try again."
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error. Check your connection."
                SpeechRecognizer.ERROR_CLIENT -> "Speech recognition unavailable."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                else -> "Recognition error (code: $error)"
            }
            Log.w(TAG, "ASR error: $message (code=$error)")
            _asrState.value = AsrState(state = ListeningState.ERROR, error = message)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull() ?: ""
            _asrState.value = AsrState(
                state = ListeningState.IDLE,
                finalTranscript = transcript,
            )
            if (transcript.isNotBlank()) {
                onFinalTranscript?.invoke(transcript)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            _asrState.value = _asrState.value.copy(partialTranscript = partial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
