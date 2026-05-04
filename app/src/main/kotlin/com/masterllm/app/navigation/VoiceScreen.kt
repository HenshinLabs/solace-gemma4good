package com.masterllm.app.navigation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private const val TAG = "VoiceScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(VoiceMode.SPEECH_TO_TEXT) }
    var transcript by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("Android Built-in") }
    var ttsText by remember { mutableStateOf("") }
    var isSpeaking by remember { mutableStateOf(false) }
    var ttsStatus by remember { mutableStateOf<String?>(null) }

    // TTS engine state
    val ttsEngine = remember { mutableStateOf<TextToSpeech?>(null) }
    val ttsInitListener = remember {
        TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine.value?.language = Locale.US
                Log.i(TAG, "TTS engine initialized successfully")
            } else {
                Log.e(TAG, "TTS engine initialization failed: $status")
            }
        }
    }

    // Initialize TTS on first composition
    DisposableEffect(context) {
        val tts = TextToSpeech(context, ttsInitListener)
        ttsEngine.value = tts
        onDispose {
            tts.stop()
            tts.shutdown()
            ttsEngine.value = null
        }
    }

    // Speech recognizer state
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    // Cleanup speech recognizer
    DisposableEffect(speechRecognizer) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Voice AI") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                // Mode selector
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedMode == VoiceMode.SPEECH_TO_TEXT,
                        onClick = { selectedMode = VoiceMode.SPEECH_TO_TEXT },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Speech to Text")
                    }
                    SegmentedButton(
                        selected = selectedMode == VoiceMode.TEXT_TO_SPEECH,
                        onClick = { selectedMode = VoiceMode.TEXT_TO_SPEECH },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Text to Speech")
                    }
                }
            }

            item {
                // Model selector card
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Engine",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedModel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Voice Engine") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Android Built-in (TTS + STT)") },
                                    onClick = {
                                        selectedModel = "Android Built-in"
                                        expanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("microsoft/wav2vec2-base-960h (ASR - download required)") },
                                    onClick = {
                                        selectedModel = "microsoft/wav2vec2-base-960h"
                                        expanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("facebook/mms-tts-eng (TTS - download required)") },
                                    onClick = {
                                        selectedModel = "facebook/mms-tts-eng"
                                        expanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("openai/whisper-base (ASR - download required)") },
                                    onClick = {
                                        selectedModel = "openai/whisper-base"
                                        expanded = false
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Android Built-in works immediately. Other models require downloading from the Explore tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            when (selectedMode) {
                VoiceMode.SPEECH_TO_TEXT -> {
                    item {
                        // Recording card
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                FilledIconButton(
                                    onClick = {
                                        if (isRecording) {
                                            speechRecognizer?.stopListening()
                                            isRecording = false
                                            isProcessing = true
                                        } else {
                                            if (speechRecognizer != null) {
                                                transcript = ""
                                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                                }
                                                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                                                    override fun onReadyForSpeech(params: Bundle?) {
                                                        Log.i(TAG, "Ready for speech")
                                                    }
                                                    override fun onBeginningOfSpeech() {
                                                        Log.i(TAG, "Speech begun")
                                                    }
                                                    override fun onRmsChanged(rmsdB: Float) {}
                                                    override fun onBufferReceived(buffer: ByteArray?) {}
                                                    override fun onEndOfSpeech() {
                                                        Log.i(TAG, "Speech ended")
                                                        isRecording = false
                                                        isProcessing = true
                                                    }
                                                    override fun onError(error: Int) {
                                                        Log.e(TAG, "Speech recognition error: $error")
                                                        isRecording = false
                                                        isProcessing = false
                                                        transcript = when (error) {
                                                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try again."
                                                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Try again."
                                                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                                                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error. Speech recognition requires network."
                                                            else -> "Recognition error (code: $error)"
                                                        }
                                                    }
                                                    override fun onResults(results: Bundle?) {
                                                        isProcessing = false
                                                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                                        transcript = matches?.firstOrNull() ?: "No results"
                                                    }
                                                    override fun onPartialResults(partialResults: Bundle?) {
                                                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                                        if (!matches.isNullOrEmpty()) {
                                                            transcript = matches.firstOrNull() ?: ""
                                                        }
                                                    }
                                                    override fun onEvent(eventType: Int, params: Bundle?) {}
                                                })
                                                speechRecognizer.startListening(intent)
                                                isRecording = true
                                            } else {
                                                transcript = "Speech recognition not available on this device."
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(80.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (isRecording) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    ),
                                ) {
                                    Icon(
                                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = if (isRecording) "Stop recording" else "Start recording",
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (isRecording) "Listening... Tap to stop" else if (isProcessing) "Processing..." else "Tap to start recording",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (isRecording) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
                                }
                            }
                        }
                    }

                    item {
                        // Transcript output
                        if (transcript.isNotEmpty() || isProcessing) {
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Transcript",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (isProcessing) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.size(8.dp))
                                            Text("Processing audio...", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    } else {
                                        Text(
                                            text = transcript,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                VoiceMode.TEXT_TO_SPEECH -> {
                    item {
                        // Text input card
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Text to Speak",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = ttsText,
                                    onValueChange = { ttsText = it },
                                    label = { Text("Enter text to synthesize") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3,
                                    maxLines = 6,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val tts = ttsEngine.value
                                        if (tts == null) {
                                            ttsStatus = "TTS engine not ready"
                                            return@Button
                                        }
                                        if (isSpeaking) {
                                            tts.stop()
                                            isSpeaking = false
                                            ttsStatus = "Stopped"
                                        } else {
                                            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                                override fun onStart(utteranceId: String?) {
                                                    isSpeaking = true
                                                }
                                                override fun onDone(utteranceId: String?) {
                                                    isSpeaking = false
                                                }
                                                @Deprecated("Deprecated in API")
                                                override fun onError(utteranceId: String?) {
                                                    isSpeaking = false
                                                }
                                            })
                                            val result = tts.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
                                            if (result == TextToSpeech.SUCCESS) {
                                                isSpeaking = true
                                                ttsStatus = null
                                            } else {
                                                ttsStatus = "Failed to start TTS"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = ttsText.isNotBlank() && ttsEngine.value != null,
                                ) {
                                    Icon(
                                        if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                        contentDescription = null,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(if (isSpeaking) "Stop" else "Speak")
                                }
                                if (ttsStatus != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = ttsStatus!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        // Info card
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "About Voice Features",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Speech-to-Text (ASR): Uses Android's built-in speech recognition. Requires network for best results.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Text-to-Speech (TTS): Uses Android's built-in TTS engine. Works fully offline.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "For higher accuracy, download dedicated models (Whisper, Wav2Vec2) from the Explore tab.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Download voice models from the Explore tab (filter by 'automatic-speech-recognition' or 'text-to-speech')",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private enum class VoiceMode {
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
}
