package com.masterllm.core.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CrisisResourceBanner(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }

    if (dismissed) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "If you're in crisis, help is available",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                IconButton(
                    onClick = {
                        dismissed = true
                        onDismiss()
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            CrisisHelplineRow(
                label = "US: 988 Suicide & Crisis Lifeline",
                phoneNumber = "988",
            )
            CrisisHelplineRow(
                label = "India: iCall",
                phoneNumber = "9152987821",
            )
            CrisisHelplineRow(
                label = "India: Vandrevala Foundation",
                phoneNumber = "18602662345",
            )
            CrisisHelplineRow(
                label = "Emergency: 112 (India) / 911 (US)",
                phoneNumber = "112",
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You are not alone. Reach out to a trusted person or professional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun CrisisHelplineRow(
    label: String,
    phoneNumber: String,
) {
    val context = LocalContext.current

    TextButton(
        onClick = {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
            }
            context.startActivity(intent)
        },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
    ) {
        Icon(
            Icons.Default.Phone,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
