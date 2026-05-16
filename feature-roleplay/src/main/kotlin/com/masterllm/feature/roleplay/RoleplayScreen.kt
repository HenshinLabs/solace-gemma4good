package com.masterllm.feature.roleplay

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.core.domain.model.Message
import com.masterllm.core.domain.model.MessageRole
import com.masterllm.core.ui.components.MarkdownMessageText
import com.masterllm.core.ui.components.TypingIndicator

// ─── Therapeutic Session Templates ──────────────────────────────

private data class GuidedSessionTemplate(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val iconTint: Color,
    val gradientColors: List<Color>,
    val systemPrompt: String,
    val aiCharacterName: String = "Solace",
    val aiCharacterDescription: String = "",
    val genre: String = "Therapy",
)

private val guidedSessionTemplates = listOf(
    GuidedSessionTemplate(
        id = "anxiety_relief",
        title = "Anxiety Relief",
        description = "Guided breathing and grounding exercises to ease anxious thoughts and restore calm.",
        icon = Icons.Default.Air,
        iconTint = Color(0xFF5C9CE6),
        gradientColors = listOf(Color(0xFFE3F0FF), Color(0xFFD0E8FF)),
        genre = "Anxiety Relief",
        aiCharacterName = "Solace",
        aiCharacterDescription = "A calm, empathetic therapeutic companion who specializes in anxiety management through guided breathing, grounding techniques, and cognitive reframing.",
        systemPrompt = "You are Solace, a warm and calming mental health companion. Your role is to help the user manage anxiety through gentle, evidence-based techniques.\n\n" +
            "Begin by warmly acknowledging the user's feelings without judgment. Then guide them through one or more of these approaches:\n" +
            "- Box breathing (inhale 4 seconds, hold 4, exhale 4, hold 4)\n" +
            "- 5-4-3-2-1 grounding (5 things you see, 4 you hear, 3 you touch, 2 you smell, 1 you taste)\n" +
            "- Progressive muscle relaxation\n" +
            "- Gentle cognitive reframing of anxious thoughts\n\n" +
            "Keep your tone soft, reassuring, and unhurried. Use short sentences. " +
            "Always check in with how the user is feeling before moving to the next technique. " +
            "If the user's anxiety seems severe, gently suggest professional resources.",
    ),
    GuidedSessionTemplate(
        id = "panic_attack",
        title = "Panic Attack Support",
        description = "Immediate stabilization techniques for moments of intense panic or distress.",
        icon = Icons.Default.FavoriteBorder,
        iconTint = Color(0xFFE57373),
        gradientColors = listOf(Color(0xFFFFEBEE), Color(0xFFFFCDD2)),
        genre = "Crisis Support",
        aiCharacterName = "Solace",
        aiCharacterDescription = "A steady, grounding presence trained in immediate panic stabilization and de-escalation techniques.",
        systemPrompt = "You are Solace, a calm and steady mental health companion. The user is experiencing or has just experienced a panic attack. Your priority is immediate stabilization.\n\n" +
            "Start with a grounding, reassuring message: they are safe, this will pass, and you are here with them.\n\n" +
            "Then gently guide them through:\n" +
            "- Slow, deliberate breathing (breathe in for 4, out for 6 — longer exhales activate the parasympathetic nervous system)\n" +
            "- Name 5 things they can see right now\n" +
            "- Press their feet firmly into the ground\n" +
            "- Hold something cold if available (ice cube, cold water)\n\n" +
            "Keep sentences very short and simple. Speak slowly through your words. " +
            "Repeat reassurance often. Do not rush to solutions — just be present and steady. " +
            "After they calm, gently check in and offer to continue talking or suggest rest.",
    ),
    GuidedSessionTemplate(
        id = "sleep_rest",
        title = "Sleep & Rest",
        description = "A calming bedtime conversation to quiet the mind and ease into restful sleep.",
        icon = Icons.Default.Bedtime,
        iconTint = Color(0xFF7E57C2),
        gradientColors = listOf(Color(0xFFEDE7F6), Color(0xFFD1C4E9)),
        genre = "Sleep & Rest",
        aiCharacterName = "Solace",
        aiCharacterDescription = "A gentle, soothing companion who helps with sleep through calming conversation, body scans, and visualization.",
        systemPrompt = "You are Solace, a gentle mental health companion helping the user wind down for sleep. Your tone should be soft, slow, and dreamlike.\n\n" +
            "Help the user quiet their mind through:\n" +
            "- A calming body scan (relax each part of the body from toes to head)\n" +
            "- Peaceful guided visualization (a quiet forest, a warm beach, a cozy room)\n" +
            "- Gentle acknowledgment of the day's worries, then helping them set those thoughts aside\n" +
            "- Slow, rhythmic breathing exercises\n\n" +
            "Use flowing, gentle language. Avoid stimulating topics. If the user shares worries, " +
            "acknowledge them warmly and gently transition back to relaxation. " +
            "Keep responses moderate in length — enough to be soothing but not so long they keep the user reading. " +
            "If the user seems very drowsy, offer a brief, warm closing and wish them restful sleep.",
    ),
    GuidedSessionTemplate(
        id = "daily_checkin",
        title = "Daily Check-in",
        description = "Mood tracking, reflection, and gentle encouragement for your everyday mental wellness.",
        icon = Icons.Default.CalendarToday,
        iconTint = Color(0xFF66BB6A),
        gradientColors = listOf(Color(0xFFE8F5E9), Color(0xFFC8E6C9)),
        genre = "Daily Check-in",
        aiCharacterName = "Solace",
        aiCharacterDescription = "A supportive companion for daily mood tracking, emotional reflection, and building healthy mental habits.",
        systemPrompt = "You are Solace, a warm and supportive mental health companion for a daily check-in. " +
            "Your goal is to help the user reflect on their emotional state and build awareness.\n\n" +
            "Start by asking how they are feeling today — genuinely, not as a formality. " +
            "Use open-ended questions. Then:\n\n" +
            "- Help them name their emotions (many people struggle to identify exactly what they feel)\n" +
            "- Gently explore what might be contributing to their mood\n" +
            "- Acknowledge their feelings without judgment\n" +
            "- Help them identify one small positive thing from their day\n" +
            "- If appropriate, suggest one small act of self-care\n\n" +
            "Be conversational and warm, not clinical. Remember this is a check-in, not therapy. " +
            "Keep things light but meaningful. End with gentle encouragement.",
    ),
    GuidedSessionTemplate(
        id = "crisis_support",
        title = "Crisis Support",
        description = "Safety planning, grounding, and compassionate support during emotional crises.",
        icon = Icons.Default.Shield,
        iconTint = Color(0xFFFF8A65),
        gradientColors = listOf(Color(0xFFFFF3E0), Color(0xFFFFE0B2)),
        genre = "Crisis Support",
        aiCharacterName = "Solace",
        aiCharacterDescription = "A compassionate companion trained in crisis de-escalation, safety planning, and providing emotional support during difficult moments.",
        systemPrompt = "You are Solace, a compassionate mental health companion. The user may be in emotional distress or crisis. " +
            "Your approach must be:\n\n" +
            "1. FIRST: Acknowledge their pain with genuine compassion. Validate their feelings. Let them know they are not alone.\n" +
            "2. Listen carefully to what they share. Do not minimize or rush to fix.\n" +
            "3. Gently assess their safety — ask if they feel safe right now.\n" +
            "4. If they express thoughts of self-harm or suicide, respond with care:\n" +
            "   - Express that you hear them and their pain matters\n" +
            "   - Gently encourage them to reach out to a crisis resource\n" +
            "   - Provide the 988 Suicide & Crisis Lifeline (call or text 988 in the US)\n" +
            "   - Provide Crisis Text Line (text HOME to 741741)\n" +
            "   - If outside the US, suggest local emergency services\n" +
            "5. Help them identify one person they trust who they could reach out to\n" +
            "6. Help them create a simple safety plan: warning signs, coping strategies, people to contact\n\n" +
            "IMPORTANT: You are not a replacement for professional help. Always gently encourage professional support. " +
            "Keep your tone warm, steady, and unhurried. There is no rush. Be fully present.",
    ),
)

// ─── Main Screen ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleplayScreen(
    modifier: Modifier = Modifier,
    viewModel: RoleplayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = state.showSessionList,
        label = "guided_nav",
        modifier = modifier,
    ) { showList ->
        if (showList) {
            GuidedSessionListPane(state, viewModel::onAction)
        } else {
            GuidedSessionChatPane(state, viewModel::onAction)
        }
    }
}

// ─── Session Template List ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedSessionListPane(
    state: RoleplayUiState,
    onAction: (RoleplayAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Guided Sessions",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Text(
                    text = "Choose a session to begin a guided therapeutic exercise with Solace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            items(guidedSessionTemplates, key = { it.id }) { template ->
                GuidedSessionTemplateCard(
                    template = template,
                    onStartSession = {
                        onAction(RoleplayAction.SetupTitleChanged(template.title))
                        onAction(RoleplayAction.SetupGenreChanged(template.genre))
                        onAction(RoleplayAction.SetupPremiseChanged(template.description))
                        onAction(RoleplayAction.SetupAiNameChanged(template.aiCharacterName))
                        onAction(RoleplayAction.SetupAiDescChanged(template.aiCharacterDescription))
                        onAction(RoleplayAction.SetupUserNameChanged("You"))
                        onAction(RoleplayAction.SetupUserDescChanged(""))
                        onAction(RoleplayAction.CreateSession)
                    },
                )
            }

            // Show existing sessions if any
            if (state.sessions.isNotEmpty()) {
                item(key = "previous_header") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Previous Sessions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                items(state.sessions, key = { "prev_${it.id}" }) { session ->
                    PreviousSessionCard(
                        session = session,
                        onClick = { onAction(RoleplayAction.SelectSession(session.id)) },
                        onDelete = { onAction(RoleplayAction.DeleteSession(session.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuidedSessionTemplateCard(
    template: GuidedSessionTemplate,
    onStartSession: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(template.gradientColors)
                )
                .padding(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(template.iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = template.icon,
                        contentDescription = null,
                        tint = template.iconTint,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = template.genre,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = template.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onStartSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = template.iconTint,
                ),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Start Session")
            }
        }
    }
}

@Composable
private fun PreviousSessionCard(
    session: com.masterllm.core.domain.model.RoleplaySession,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = session.genre.ifEmpty { "Guided Session" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ─── Guided Session Chat Pane ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuidedSessionChatPane(
    state: RoleplayUiState,
    onAction: (RoleplayAction) -> Unit,
) {
    val listState = rememberLazyListState()
    val session = state.currentSession

    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + (if (state.streamingText.isNotEmpty()) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onAction(RoleplayAction.BackToList) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = session?.title ?: "Guided Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "with ${session?.aiCharacterName ?: "Solace"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
            )
        },
        snackbarHost = {
            state.error?.let { error ->
                Snackbar(
                    action = {
                        TextButton(onClick = { onAction(RoleplayAction.DismissError) }) {
                            Text("Dismiss")
                        }
                    },
                ) {
                    Text(error)
                }
            }
        },
        bottomBar = {
            GuidedSessionInputBar(
                text = state.inputText,
                isGenerating = state.isGenerating,
                onTextChange = { onAction(RoleplayAction.InputChanged(it)) },
                onSend = { onAction(RoleplayAction.SendMessage) },
                onStop = { onAction(RoleplayAction.StopGeneration) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Session intro card
            item(key = "session_intro") {
                SessionIntroCard(sessionTitle = session?.title ?: "Guided Session")
            }

            // Messages
            items(
                state.messages.filter { it.role != MessageRole.SYSTEM },
                key = { it.id },
            ) { msg ->
                GuidedSessionBubble(message = msg)
            }

            // Streaming text
            if (state.streamingText.isNotEmpty()) {
                item(key = "streaming") {
                    GuidedSessionBubble(
                        message = Message(
                            id = "streaming",
                            role = MessageRole.ASSISTANT,
                            content = state.streamingText,
                            isStreaming = true,
                        ),
                    )
                }
            }

            // Typing indicator
            if (state.isGenerating && state.streamingText.isEmpty()) {
                item(key = "typing") {
                    TypingIndicator(modifier = Modifier.padding(start = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionIntroCard(sessionTitle: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SelfImprovement,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Your $sessionTitle session has begun. Take your time — Solace is here to listen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

// ─── Chat Bubble ───────────────────────────────────────────────

@Composable
private fun GuidedSessionBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.SelfImprovement,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            if (!isUser) {
                Text(
                    text = "Solace",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (!isUser && !message.isStreaming && message.content.isNotBlank()) {
                        MarkdownMessageText(
                            markdown = message.content,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
                            modifier = Modifier.fillMaxWidth(),
                            onLongClick = {
                                clipboard.setText(AnnotatedString(message.content))
                            },
                        )
                    } else {
                        val finalText = message.content.ifBlank { "" }
                        if (finalText.isNotBlank()) {
                            Text(
                                text = finalText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Input Bar ─────────────────────────────────────────────────

@Composable
private fun GuidedSessionInputBar(
    text: String,
    isGenerating: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Share what's on your mind...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                enabled = !isGenerating,
            )
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
