package com.masterllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.ImageFrequency
import com.masterllm.core.ui.components.GradientCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenAuth: () -> Unit = {},
    onOpenModelManager: () -> Unit = {},
    onOpenImageGen: () -> Unit = {},
    onOpenPerformance: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

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
                item {
                    SettingsSectionCard(
                        icon = Icons.Default.AccountCircle,
                        title = "Account & Access",
                        subtitle = "Manage Hugging Face authentication for gated models",
                    ) {
                        Text(
                            text = if (state.hfUsername.isBlank()) {
                                "Not signed in to Hugging Face"
                            } else {
                                "Signed in as ${state.hfUsername}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = onOpenAuth) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Manage Hugging Face Login")
                        }
                    }
                }

                item {
                    SettingsSectionCard(
                        icon = Icons.Default.ModelTraining,
                        title = "Runtime & Performance",
                        subtitle = "Tune inference speed, memory behavior, and acceleration",
                    ) {
                        LabeledSlider(
                            title = "Default threads",
                            description = "Higher values can improve speed but increase thermal and battery load.",
                            valueText = state.defaultThreadCount.toString(),
                            value = state.defaultThreadCount.toFloat(),
                            valueRange = 1f..16f,
                            onValueChange = { viewModel.onAction(SettingsAction.ThreadCountChanged(it.toInt())) },
                        )

                        Spacer(Modifier.height(8.dp))

                        LabeledSlider(
                            title = "Auto-compaction threshold",
                            description = "Compaction starts when conversation context reaches this percentage.",
                            valueText = "${state.autoCompactionThreshold}%",
                            value = state.autoCompactionThreshold.toFloat(),
                            valueRange = 50f..95f,
                            onValueChange = { viewModel.onAction(SettingsAction.AutoCompactionChanged(it.toInt())) },
                        )

                        Spacer(Modifier.height(8.dp))

                        SettingSwitchRow(
                            title = "Enable GPU acceleration",
                            subtitle = "When supported, model operations can run on native GPU paths.",
                            checked = state.gpuAccelerationEnabled,
                            onCheckedChange = { viewModel.onAction(SettingsAction.GpuAccelerationChanged(it)) },
                        )

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = onOpenPerformance,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Performance Monitor")
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "GPU driver status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.gpuDriverStatus,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = state.gpuDriverDetails,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (state.gpuValidationChecks.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Validation checks",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            state.gpuValidationChecks.forEach { check ->
                                Text(
                                    text = "• $check",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Image,
                        title = "Roleplay & Images",
                        subtitle = "Set default visual generation behavior",
                    ) {
                        FrequencyDropdown(
                            selected = state.defaultImageFrequency,
                            onSelect = { viewModel.onAction(SettingsAction.ImageFrequencyChanged(it)) },
                        )

                        Spacer(Modifier.height(8.dp))

                        SettingSwitchRow(
                            title = "Character consistency",
                            subtitle = "Reuse character references for more stable visual identity.",
                            checked = state.characterConsistencyEnabled,
                            onCheckedChange = { viewModel.onAction(SettingsAction.CharacterConsistencyChanged(it)) },
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenModelManager, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.ModelTraining, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Models")
                            }
                            OutlinedButton(onClick = onOpenImageGen, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Image, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Image Gen")
                            }
                        }
                    }
                }

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

                item {
                    SettingsSectionCard(
                        icon = Icons.Default.Cloud,
                        title = "Ollama Server",
                        subtitle = "Configure remote Ollama backend for chat inference",
                    ) {
                        OllamaSettingsSection(
                            host = state.ollamaHost,
                            enabled = state.ollamaEnabled,
                            keepAlive = state.ollamaKeepAlive,
                            systemPrompt = state.ollamaSystemPrompt,
                            connectionStatus = state.ollamaConnectionStatus,
                            connectionChecking = state.ollamaConnectionChecking,
                            onHostChanged = { viewModel.onAction(SettingsAction.OllamaHostChanged(it)) },
                            onEnabledChanged = { viewModel.onAction(SettingsAction.OllamaEnabledChanged(it)) },
                            onKeepAliveChanged = { viewModel.onAction(SettingsAction.OllamaKeepAliveChanged(it)) },
                            onSystemPromptChanged = { viewModel.onAction(SettingsAction.OllamaSystemPromptChanged(it)) },
                            onTestConnection = { viewModel.onAction(SettingsAction.TestOllamaConnection) },
                        )
                    }
                }

                item {
                    SettingsSectionCard(
                        icon = Icons.Default.ModelTraining,
                        title = "Storage",
                        subtitle = "Choose where downloaded models are stored",
                    ) {
                        OutlinedTextField(
                            value = state.modelStoragePath,
                            onValueChange = { viewModel.onAction(SettingsAction.ModelStoragePathChanged(it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom model storage path (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Default path: ${state.defaultModelStoragePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Effective path: ${state.effectiveModelStoragePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (state.usingDefaultStoragePath) {
                                "Using default app download folder"
                            } else {
                                "Using custom storage folder"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.onAction(SettingsAction.SaveModelStoragePath) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Save path")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.onAction(SettingsAction.ModelStoragePathChanged(""))
                                    viewModel.onAction(SettingsAction.SaveModelStoragePath)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Use default")
                            }
                        }
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

@Composable
fun SettingSwitchRow(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencyDropdown(
    selected: ImageFrequency,
    onSelect: (ImageFrequency) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Default image frequency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ImageFrequency.entries.forEach { freq ->
                DropdownMenuItem(
                    text = { Text(freq.displayName) },
                    onClick = {
                        onSelect(freq)
                        expanded = false
                    },
                )
            }
        }
    }
}

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
        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}
