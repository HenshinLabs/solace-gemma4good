package com.masterllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                GradientCard {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.hfUsername.isBlank()) "Not signed in to Hugging Face"
                        else "Signed in as ${state.hfUsername}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpenAuth) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Manage Hugging Face Login")
                    }
                }
            }

            item {
                GradientCard {
                    Text("Runtime", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Threads: ${state.defaultThreadCount}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.defaultThreadCount.toFloat(),
                        valueRange = 1f..16f,
                        onValueChange = { viewModel.onAction(SettingsAction.ThreadCountChanged(it.toInt())) },
                    )

                    Text("Auto-compaction threshold: ${state.autoCompactionThreshold}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.autoCompactionThreshold.toFloat(),
                        valueRange = 50f..95f,
                        onValueChange = { viewModel.onAction(SettingsAction.AutoCompactionChanged(it.toInt())) },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Enable GPU acceleration")
                        Switch(
                            checked = state.gpuAccelerationEnabled,
                            onCheckedChange = { viewModel.onAction(SettingsAction.GpuAccelerationChanged(it)) },
                        )
                    }
                }
            }

            item {
                GradientCard {
                    Text("Roleplay & Images", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    FrequencyDropdown(
                        selected = state.defaultImageFrequency,
                        onSelect = { viewModel.onAction(SettingsAction.ImageFrequencyChanged(it)) },
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Character consistency")
                        Switch(
                            checked = state.characterConsistencyEnabled,
                            onCheckedChange = { viewModel.onAction(SettingsAction.CharacterConsistencyChanged(it)) },
                        )
                    }

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
                GradientCard {
                    Text("Appearance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    ThemeSelector(
                        selected = state.theme,
                        onSelect = { viewModel.onAction(SettingsAction.ThemeChanged(it)) },
                    )
                }
            }

            item {
                GradientCard {
                    Text("Storage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.modelStoragePath,
                        onValueChange = { viewModel.onAction(SettingsAction.ModelStoragePathChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model storage path") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.onAction(SettingsAction.SaveModelStoragePath) }) {
                        Text("Save path")
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
                modifier = Modifier.padding(16.dp),
            ) {
                Text(error)
            }
        }
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
private fun ThemeSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
