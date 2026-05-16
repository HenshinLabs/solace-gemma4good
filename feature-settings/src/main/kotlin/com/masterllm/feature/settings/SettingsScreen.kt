package com.masterllm.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.ui.components.GradientCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    // Local state for inference and behavior toggles
    var voiceEnabled by remember { mutableStateOf(true) }
    var showThinking by remember { mutableStateOf(true) }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topP by remember { mutableFloatStateOf(0.9f) }
    var topK by remember { mutableFloatStateOf(40f) }
    var contextLength by remember { mutableFloatStateOf(16384f) }
    var contextCompactionEnabled by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Downloaded Model") },
            text = {
                Text(
                    "This will permanently remove the downloaded model file from your device. " +
                        "You can re-download it later from the marketplace."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        val modelDir = File(state.effectiveModelStoragePath)
                        if (modelDir.exists()) {
                            modelDir.listFiles()?.forEach { it.delete() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── Appearance ─────────────────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Settings,
                        title = "Appearance",
                        subtitle = "Choose how the app theme is applied",
                    ) {
                        ThemeSelector(
                            selected = state.theme,
                            onSelect = { viewModel.onAction(SettingsAction.ThemeChanged(it)) },
                        )
                    }
                }

                // ── Voice ──────────────────────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.RecordVoiceOver,
                        title = "Voice",
                        subtitle = "Enable or disable text-to-speech output",
                    ) {
                        SettingSwitchRow(
                            title = "Voice output",
                            subtitle = "Read assistant responses aloud using text-to-speech.",
                            checked = voiceEnabled,
                            onCheckedChange = { voiceEnabled = it },
                        )
                    }
                }

                // ── Show Thinking ──────────────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Psychology,
                        title = "Thinking",
                        subtitle = "Control visibility of model reasoning steps",
                    ) {
                        SettingSwitchRow(
                            title = "Show thinking",
                            subtitle = "Display the model's internal reasoning before the final answer.",
                            checked = showThinking,
                            onCheckedChange = { showThinking = it },
                        )
                    }
                }

                // ── Inference Parameters ───────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Tune,
                        title = "Inference Parameters",
                        subtitle = "Fine-tune how the model generates text",
                    ) {
                        LabeledSlider(
                            title = "Temperature",
                            description = "Higher values produce more creative output; lower values are more focused.",
                            valueText = String.format("%.2f", temperature),
                            value = temperature,
                            valueRange = 0f..2f,
                            onValueChange = { temperature = it },
                        )

                        Spacer(Modifier.height(8.dp))

                        LabeledSlider(
                            title = "Top P",
                            description = "Nucleus sampling threshold. Lower values restrict to more likely tokens.",
                            valueText = String.format("%.2f", topP),
                            value = topP,
                            valueRange = 0f..1f,
                            onValueChange = { topP = it },
                        )

                        Spacer(Modifier.height(8.dp))

                        LabeledSlider(
                            title = "Top K",
                            description = "Limits sampling to the K most probable next tokens.",
                            valueText = topK.toInt().toString(),
                            value = topK,
                            valueRange = 1f..100f,
                            onValueChange = { topK = it },
                        )

                        Spacer(Modifier.height(8.dp))

                        LabeledSlider(
                            title = "Context Length",
                            description = "Maximum number of tokens the model can consider at once.",
                            valueText = contextLength.toInt().toString(),
                            value = contextLength,
                            valueRange = 2048f..131072f,
                            onValueChange = { contextLength = it },
                        )
                    }
                }

                // ── Context Compaction ─────────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Tune,
                        title = "Context Compaction",
                        subtitle = "Manage automatic conversation summarization",
                    ) {
                        SettingSwitchRow(
                            title = "Automatic context compaction",
                            subtitle = "Automatically summarize older messages when the context window fills up.",
                            checked = contextCompactionEnabled,
                            onCheckedChange = { contextCompactionEnabled = it },
                        )
                    }
                }

                // ── Delete Model ───────────────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Delete,
                        title = "Model Storage",
                        subtitle = "Manage downloaded model files",
                    ) {
                        Text(
                            text = "Downloaded model files can be several gigabytes. " +
                                "Delete them to free up storage space.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Downloaded Model")
                        }
                    }
                }

                // ── About Solace ───────────────────────────────────
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Info,
                        title = "About Solace",
                        subtitle = "Your mental health companion",
                    ) {
                        Text(
                            text = "Version 2.0.2",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Crisis Helplines",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "If you or someone you know is in crisis, please reach out:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(12.dp))

                        CrisisHelplineRow(
                            label = "988 Suicide & Crisis Lifeline (US)",
                            number = "988",
                        )
                        Spacer(Modifier.height(8.dp))
                        CrisisHelplineRow(
                            label = "iCall Counselling (India)",
                            number = "9152987821",
                        )
                        Spacer(Modifier.height(8.dp))
                        CrisisHelplineRow(
                            label = "Vandrevala Foundation (India)",
                            number = "18602662345",
                        )

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Your life has value. Help is available.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            state.error?.let { error ->
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.onAction(SettingsAction.DismissError) }) {
                            Text("Dismiss")
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                ) {
                    Text(error)
                }
            }
        }
    }
}

// ─── Crisis helpline row ────────────────────────────────────────────

@Composable
private fun CrisisHelplineRow(label: String, number: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = number,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$number")
                }
                context.startActivity(intent)
            },
        ) {
            Icon(Icons.Default.Phone, contentDescription = "Call $number")
        }
    }
}

// ─── Section card ───────────────────────────────────────────────────

@Composable
private fun SettingsSectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    GradientCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// ─── Labeled slider ─────────────────────────────────────────────────

@Composable
private fun LabeledSlider(
    title: String,
    description: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
    )
}

// ─── Switch row ─────────────────────────────────────────────────────

@Composable
internal fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

// ─── Theme selector ─────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ThemeSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "system" to "System",
            "light" to "Light",
            "dark" to "Dark",
        ).forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}
