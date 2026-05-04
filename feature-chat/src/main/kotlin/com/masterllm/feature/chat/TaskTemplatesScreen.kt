package com.masterllm.feature.chat

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class TaskTemplate(
    val title: String,
    val icon: ImageVector,
    val systemPrompt: String,
    val starterPrompt: String,
)

val builtInTasks = listOf(
    TaskTemplate(
        title = "Code Assistant",
        icon = Icons.Default.Code,
        systemPrompt = "You are an expert programming assistant. Provide clean, well-documented code. Explain your reasoning and suggest best practices.",
        starterPrompt = "Help me write code for:\n\n",
    ),
    TaskTemplate(
        title = "Creative Writer",
        icon = Icons.Default.Create,
        systemPrompt = "You are a creative writer with an engaging, descriptive style. Write vivid prose that captures the reader's imagination.",
        starterPrompt = "Write a story about:\n\n",
    ),
    TaskTemplate(
        title = "Math Tutor",
        icon = Icons.Default.School,
        systemPrompt = "You are a patient math tutor. Explain concepts step-by-step with worked examples. Use clear notation and verify answers.",
        starterPrompt = "Help me solve this math problem:\n\n",
    ),
    TaskTemplate(
        title = "Language Translator",
        icon = Icons.Default.Translate,
        systemPrompt = "You are a professional translator. Translate text accurately while preserving tone and nuance. Provide both literal and natural translations when helpful.",
        starterPrompt = "Translate this text:\n\n",
    ),
    TaskTemplate(
        title = "Summarizer",
        icon = Icons.Default.Summarize,
        systemPrompt = "You are a concise summarizer. Extract key points and present them as bullet points or a short paragraph. Preserve all important facts.",
        starterPrompt = "Summarize this text:\n\n",
    ),
    TaskTemplate(
        title = "Email Composer",
        icon = Icons.Default.Email,
        systemPrompt = "You are a professional email writer. Write clear, concise, and polite emails. Adjust tone based on the recipient.",
        starterPrompt = "Write an email for:\n\n",
    ),
    TaskTemplate(
        title = "Debugging Helper",
        icon = Icons.Default.Build,
        systemPrompt = "You are a debugging expert. Find bugs, explain root causes, and suggest fixes. Always verify solutions for correctness.",
        starterPrompt = "Help me debug this:\n\n",
    ),
    TaskTemplate(
        title = "Learning Coach",
        icon = Icons.Default.Psychology,
        systemPrompt = "You are a knowledgeable learning coach. Explain concepts simply using analogies and examples. Check understanding before moving on.",
        starterPrompt = "Explain this concept to me:\n\n",
    ),
    TaskTemplate(
        title = "Brainstormer",
        icon = Icons.Default.Lightbulb,
        systemPrompt = "You are a creative brainstormer. Generate diverse, innovative ideas. Encourage lateral thinking and explore different angles.",
        starterPrompt = "Brainstorm ideas for:\n\n",
    ),
    TaskTemplate(
        title = "Custom",
        icon = Icons.Default.Tune,
        systemPrompt = "",
        starterPrompt = "",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskTemplatesScreen(
    onBackClick: () -> Unit,
    onApplyTemplate: (systemPrompt: String, starterPrompt: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Task Templates") },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(builtInTasks) { task ->
                TaskTemplateCard(
                    task = task,
                    onApply = {
                        val systemPrompt = if (task.systemPrompt.isNotBlank()) task.systemPrompt else ""
                        val starterPrompt = if (task.starterPrompt.isNotBlank()) task.starterPrompt else ""
                        onApplyTemplate(systemPrompt, starterPrompt)
                        onBackClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskTemplateCard(
    task: TaskTemplate,
    onApply: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Icon(
                    task.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = task.systemPrompt.ifBlank { "Custom task — configure your own system prompt." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply Template")
            }
        }
    }
}
