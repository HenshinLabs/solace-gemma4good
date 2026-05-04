package com.masterllm.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Voice tab screen for speech-to-text (ASR) and text-to-speech (TTS) operations.
 * Supports small on-device models from HuggingFace like Microsoft's Wav2Vec2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(modifier: Modifier = Modifier) {
    var selectedMode by remember { mutableStateOf(VoiceMode.SPEECH_TO_TEXT) }
    var transcript by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("microsoft/wav2vec2-base-960h") }
    var ttsText by remember { mutableStateOf("") }
    var isSpeaking by remember { mutableStateOf(false) }

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
                            text = "Model",
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
                                label = { Text("ASR / TTS Model") },
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
                                    text = { Text("microsoft/wav2vec2-base-960h (ASR)") },
                                    onClick = {
                                        selectedModel = "microsoft/wav2vec2-base-960h"
                                        expanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("facebook/mms-tts-eng (TTS)") },
                                    onClick = {
                                        selectedModel = "facebook/mms-tts-eng"
                                        expanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("espnet/kan-bayashi_ljspeech_vits (TTS)") },
                                    onClick = {
                                        selectedModel = "espnet/kan-bayashi_ljspeech_vits"
                                        expanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("openai/whisper-base (ASR)") },
                                    onClick = {
                                        selectedModel = "openai/whisper-base"
                                        expanded = false
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Small on-device models for speech recognition and synthesis. Download from the Explore tab.",
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
                                    onClick = { isRecording = !isRecording },
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
                                    text = if (isRecording) "Recording... Tap to stop" else "Tap to start recording",
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
                                    onClick = { isSpeaking = !isSpeaking },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = ttsText.isNotBlank() && !isProcessing,
                                ) {
                                    Icon(
                                        if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                        contentDescription = null,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(if (isSpeaking) "Stop" else "Speak")
                                }
                            }
                        }
                    }

                    item {
                        // Info card
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "About On-Device Voice",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "• Speech-to-Text (ASR): Converts your voice to text using models like Wav2Vec2 or Whisper",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "• Text-to-Speech (TTS): Synthesizes natural speech from text using VITS or MMS-TTS",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "• All processing happens locally on your device - no cloud required",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "• Download voice models from the Explore tab (filter by 'automatic-speech-recognition' or 'text-to-speech')",
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
