package com.masterllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun OllamaSettingsSection(
    host: String,
    enabled: Boolean,
    keepAlive: String,
    systemPrompt: String,
    connectionStatus: String?,
    connectionChecking: Boolean,
    onHostChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onKeepAliveChanged: (String) -> Unit,
    onSystemPromptChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onOpenOllamaExplorer: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingSwitchRow(
            title = "Enable Ollama Remote Backend",
            subtitle = "Use a remote Ollama server instead of local GGUF inference",
            checked = enabled,
            onCheckedChange = onEnabledChanged,
        )

        if (enabled) {
            HorizontalDivider()

            OutlinedTextField(
                value = host,
                onValueChange = onHostChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ollama Host URL") },
                placeholder = { Text("http://localhost:11434") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = onTestConnection,
                        enabled = !connectionChecking,
                    ) {
                        if (connectionChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Test Connection")
                        }
                    }
                },
            )

            connectionStatus?.let { status ->
                val isConnected = status.startsWith("Connected")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (isConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.height(16.dp),
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("System Prompt") },
                minLines = 2,
                maxLines = 5,
            )

            HorizontalDivider()

            Text(
                text = "Keep-Alive Duration",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "How long to keep the model loaded (seconds). 0 = unload immediately, -1 = always loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val keepAliveOptions = listOf(
                "Never" to "0",
                "1 minute" to "60",
                "5 minutes" to "300",
                "10 minutes" to "600",
                "30 minutes" to "1800",
                "Always" to "-1",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                keepAliveOptions.forEach { (label, value) ->
                    FilterChip(
                        selected = keepAlive == value,
                        onClick = { onKeepAliveChanged(value) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            OutlinedTextField(
                value = keepAlive,
                onValueChange = onKeepAliveChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Custom keep-alive (seconds)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            HorizontalDivider()

            Text(
                text = "Model Library",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Browse and download models from the Ollama library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onOpenOllamaExplorer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ModelTraining, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Browse Ollama Library")
            }
        }
    }
}
