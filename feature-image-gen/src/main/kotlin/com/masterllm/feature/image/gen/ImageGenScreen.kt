package com.masterllm.feature.image.gen

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.ui.components.EmptyState
import com.masterllm.core.ui.components.GradientCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenScreen(
    modifier: Modifier = Modifier,
    viewModel: ImageGenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Image Generation") })
        },
    ) { padding ->
        if (state.availableModels.isEmpty()) {
            EmptyState(
                icon = Icons.Default.PhotoLibrary,
                title = "No image models available",
                subtitle = "Download a Diffusers or SafeTensors model from Marketplace first.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                GradientCard {
                    Text("Prompt", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.prompt,
                        onValueChange = { viewModel.onAction(ImageGenAction.PromptChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Describe the image you want...") },
                        minLines = 2,
                        maxLines = 5,
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.negativePrompt,
                        onValueChange = { viewModel.onAction(ImageGenAction.NegativePromptChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Negative prompt") },
                        placeholder = { Text("blurry, low quality, distorted") },
                        minLines = 1,
                        maxLines = 3,
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Model", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    ModelDropdown(
                        selectedModelId = state.selectedModelId,
                        models = state.availableModels.map { it.id to it.displayName.ifBlank { it.repoId } },
                        onSelect = { viewModel.onAction(ImageGenAction.SelectModel(it)) },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Steps: ${state.steps}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.steps.toFloat(),
                        onValueChange = { viewModel.onAction(ImageGenAction.StepsChanged(it.toInt())) },
                        valueRange = 5f..80f,
                    )

                    Text("CFG: %.1f".format(state.cfgScale), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = state.cfgScale,
                        onValueChange = { viewModel.onAction(ImageGenAction.CfgScaleChanged(it)) },
                        valueRange = 1f..20f,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.width.toString(),
                            onValueChange = { viewModel.onAction(ImageGenAction.WidthChanged(it.toIntOrNull() ?: state.width)) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Width") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.height.toString(),
                            onValueChange = { viewModel.onAction(ImageGenAction.HeightChanged(it.toIntOrNull() ?: state.height)) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Height") },
                            singleLine = true,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.onAction(ImageGenAction.Generate) },
                            enabled = !state.isGenerating && state.prompt.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Generate")
                        }

                        OutlinedButton(
                            onClick = {
                                if (state.isGenerating) viewModel.onAction(ImageGenAction.Stop)
                                else viewModel.onAction(ImageGenAction.ClearImage)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.isGenerating) "Stop" else "Clear")
                        }
                    }

                    if (state.isGenerating) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Generating ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                state.generatedImage?.let { bitmap ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Generated image",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            Snackbar(
                action = {
                    TextButton(onClick = { viewModel.onAction(ImageGenAction.DismissError) }) {
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
private fun ModelDropdown(
    selectedModelId: String?,
    models: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = models.firstOrNull { it.first == selectedModelId }?.second ?: "Select model"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
