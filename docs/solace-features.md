# Solace — Feature Reference Documentation

> A private, on-device mental health companion for anxiety, trauma, and crisis support.

---

## Table of Contents

- [Overview](#overview)
- [App Identity](#app-identity)
- [Therapeutic Session Templates](#therapeutic-session-templates)
  - [Anxiety Relief](#anxiety-relief)
  - [Panic Attack Support](#panic-attack-support)
  - [Sleep & Rest](#sleep--rest)
  - [Daily Check-in](#daily-check-in)
  - [Crisis Support](#crisis-support)
- [Crisis Resources](#crisis-resources)
- [System Prompt Architecture](#system-prompt-architecture)
- [Color Palette & Theming](#color-palette--theming)
- [Privacy & Safety](#privacy--safety)
- [Technical Implementation](#technical-implementation)
- [Accessibility](#accessibility)

---

## Overview

Solace is a mental health companion app designed to provide immediate, private, and compassionate support for individuals experiencing anxiety, panic attacks, sleep difficulties, emotional distress, and crisis situations. Unlike cloud-based mental health tools, Solace runs its AI inference entirely on-device using a quantized large language model, ensuring that sensitive conversations never leave the user's phone.

Solace is **not** a replacement for professional mental health care. It is a companion tool that offers evidence-based coping techniques, grounding exercises, and crisis resource routing in a warm, non-judgmental interface.

---

## App Identity

| Property        | Value                                                |
|-----------------|------------------------------------------------------|
| **Name**        | Solace                                               |
| **Purpose**     | Mental health companion for anxiety, trauma, suicidal thoughts |
| **AI Model**    | Gemma 4 E2B (Q4_K_M quantization)                   |
| **Runtime**     | llama.cpp (on-device)                                |
| **Privacy**     | All inference on-device, no data sent to servers     |
| **Platforms**   | Android (primary), iOS (planned)                     |

---

## Therapeutic Session Templates

Solace provides five specialized session types, each with its own visual identity, tone, system prompt, and therapeutic techniques. Users select a session type from the home screen, which configures the AI companion's behavior for that interaction.

### Anxiety Relief

> **Purpose:** Help users manage generalized anxiety through grounding, breathing, and cognitive techniques.

| Property         | Value                              |
|------------------|------------------------------------|
| **Icon**         | `Air` (Material Icons)            |
| **Icon Color**   | Blue `#5C9CE6`                     |
| **Gradient**     | `#E3F0FF` → `#D0E8FF`             |
| **Border Color** | `#9BBFE8`                          |

**Therapeutic Techniques:**

- **Box Breathing (4-4-4-4):** Inhale 4 seconds, hold 4 seconds, exhale 4 seconds, hold 4 seconds. Repeat for 4 cycles.
- **5-4-3-2-1 Grounding:** Name 5 things you see, 4 you can touch, 3 you hear, 2 you smell, 1 you taste.
- **Progressive Muscle Relaxation:** Systematically tense and release muscle groups from toes to head.
- **Cognitive Reframing:** Identify anxious thoughts, examine evidence, generate balanced alternatives.

**Tone & Behavior:**
The system prompt configures Solace to be warm, calming, and unhurried. It avoids rushing to solutions and prioritizes validating the user's experience before offering techniques.

**Implementation:**
```
SessionConfig(
    icon = Icons.Default.Air,
    iconColor = Color(0xFF5C9CE6),
    gradient = Brush.verticalGradient(listOf(Color(0xFFE3F0FF), Color(0xFFD0E8FF))),
    borderColor = Color(0xFF9BBFE8),
    systemPrompt = SystemPromptLoader.anxietyRelief
)
```

---

### Panic Attack Support

> **Purpose:** Provide immediate stabilization during a panic attack with sensory grounding and slow breathing.

| Property         | Value                              |
|------------------|------------------------------------|
| **Icon**         | `FavoriteBorder` (Material Icons) |
| **Icon Color**   | Red `#E57373`                      |
| **Gradient**     | `#FFEBEE` → `#FFCDD2`             |
| **Border Color** | `#E57373`                          |

**Therapeutic Techniques:**

- **Slow Breathing (4-6 pattern):** Inhale for 4 seconds, exhale for 6 seconds. The extended exhale activates the parasympathetic nervous system.
- **5 Things Technique:** "Name 5 things you can see right now." — anchors attention to the present.
- **Feet Grounding:** "Press your feet into the floor. Notice the pressure, the temperature, the texture."
- **Cold Object Technique:** Hold ice or run wrists under cold water to interrupt the panic response via the dive reflex.

**Tone & Behavior:**
Solace does not rush to solutions. The focus is entirely on immediate stabilization — slow breathing, sensory anchoring, and reassurance that the episode will pass. Cognitive work is deferred.

**Key Design Decisions:**
- No "what caused this?" questions during active panic
- Short, clear sentences only
- Breathing guidance uses visual pacer when available
- Session can be ended at any time without summary

---

### Sleep & Rest

> **Purpose:** Guide users toward sleep through relaxation, body scanning, and gentle mental exercises.

| Property         | Value                              |
|------------------|------------------------------------|
| **Icon**         | `Bedtime` (Material Icons)        |
| **Icon Color**   | Purple `#7E57C2`                   |
| **Gradient**     | `#EDE7F6` → `#D1C4E9`             |
| **Border Color** | `#9575CD`                          |

**Therapeutic Techniques:**

- **Body Scan:** Progressive awareness from toes to crown, noticing sensations without judgment.
- **Guided Visualization:** Imagining a peaceful scene — a quiet beach, a forest clearing, a warm room.
- **Worry Acknowledgment:** "Let's write down that worry so your mind knows it won't be forgotten. Now let it go for tonight."
- **Breathing Exercises:** Slow, rhythmic breathing with longer exhales to promote drowsiness.

**Tone & Behavior:**
Soft, slow, dreamlike. Responses become progressively shorter and quieter as the session continues. Solace avoids stimulating topics and redirects anxious thoughts gently.

**Special Behaviors:**
- TTS voice speed reduced by 15%
- Response length capped after initial exchange
- Darker theme applied automatically
- "Goodnight" message offered as natural exit point

---

### Daily Check-in

> **Purpose:** Support regular emotional self-awareness through mood tracking and reflective conversation.

| Property         | Value                              |
|------------------|------------------------------------|
| **Icon**         | `CalendarToday` (Material Icons)  |
| **Icon Color**   | Green `#66BB6A`                    |
| **Gradient**     | `#E8F5E9` → `#C8E6C9`            |
| **Border Color** | `#81C784`                          |

**Therapeutic Techniques:**

- **Mood Tracking:** "How are you feeling today, really?" — invites honest reflection.
- **Emotional Naming:** Help users put specific words to complex feelings (e.g., "frustrated" vs. "angry" vs. "disappointed").
- **Identifying Positives:** "What's one small thing that went well today, even if the day was hard?"
- **Reflection:** Gentle follow-up questions that help users understand patterns over time.

**Tone & Behavior:**
Conversational and warm — like checking in with a trusted friend. Solace asks open-ended questions and reflects back what it hears without judgment.

**Data Persistence:**
- Mood entries stored locally in Room/SQLite
- Weekly mood summary available on home screen
- No mood data transmitted externally
- Optional mood history chart (7-day, 30-day views)

---

### Crisis Support

> **Purpose:** Provide immediate, compassionate support during suicidal ideation or emotional crisis, with safety planning and resource routing.

| Property         | Value                              |
|------------------|------------------------------------|
| **Icon**         | `Shield` (Material Icons)         |
| **Icon Color**   | Orange `#FF8A65`                   |
| **Gradient**     | `#FFF3E0` → `#FFE0B2`            |
| **Border Color** | `#FFAB91`                          |

**Therapeutic Approach (5-Step Model):**

1. **Acknowledge Pain:** "I hear you. What you're feeling right now is real, and it matters."
2. **Listen Actively:** Let the user express without interruption or premature problem-solving.
3. **Assess Safety:** "Are you thinking about hurting yourself right now?" — direct, non-euphemistic.
4. **Provide Resources:** Surface crisis helpline numbers with tap-to-call integration.
5. **Safety Planning:** Help identify coping strategies, safe people to contact, and reasons for living.

**Critical Behavior Rules:**
- Solace **always** encourages professional help in crisis mode
- Never minimizes or dismisses suicidal thoughts
- Never keeps crisis conversations secret — explicitly offers resource connections
- Crisis keywords (e.g., "kill myself", "end it all", "don't want to be here") trigger immediate resource overlay
- System prompt escalation path is deterministic, not probabilistic

**Resources Surfaced:**
See [Crisis Resources](#crisis-resources) section below.

---

## Crisis Resources

Solace maintains a curated, region-aware list of crisis resources that are always accessible from the home screen and settings, and are proactively surfaced during crisis sessions.

| Service                            | Contact              | Region  | Access Method     |
|------------------------------------|----------------------|---------|-------------------|
| 988 Suicide & Crisis Lifeline      | Call or text 988     | US      | Tap-to-call/text  |
| iCall India                        | 9152987821           | India   | Tap-to-call       |
| Vandrevala Foundation               | 18602662345          | India   | Tap-to-call       |
| Crisis Text Line                   | Text HOME to 741741  | US/CA/UK| Tap-to-text       |

**Implementation Notes:**
- Resources are stored in a local JSON asset (`crisis_resources.json`)
- `TapToCallHelper` handles `ACTION_DIAL` intent for phone numbers
- SMS intent for Crisis Text Line
- Resources displayed as prominent cards with large touch targets (minimum 48dp)
- Resources are **never** gated behind any user action — always one tap away

---

## System Prompt Architecture

### Overview

Solace uses a `SystemPromptLoader` to inject context-specific instructions into the LLM before each session. The system prompt defines:

- **Persona:** Compassionate, non-judgmental mental health companion
- **Boundaries:** Not a therapist, not a doctor, always recommends professional help
- **Technique guidance:** Specific exercises for the selected session type
- **Crisis escalation:** Keyword detection rules and response protocols
- **Output format:** Thinking block delimiters, response length guidelines

### System Prompt Visibility

| Artifact        | User Visibility | TTS Output | Stored |
|-----------------|-----------------|------------|--------|
| System prompt   | Never shown     | Never read | No     |
| Thinking blocks | Stripped         | Stripped   | No     |
| Response text   | Displayed        | Spoken     | No     |

### Thinking Blocks

Solace uses thinking blocks (`<think>...</think>`) for internal reasoning:

- **Generated by:** Gemma 4 E2B during inference
- **Stripped by:** Response parser before display
- **Never sent to:** TTS engine, logs, or analytics
- **Purpose:** Allows the model to reason through therapeutic decisions without exposing clinical reasoning to the user

```kotlin
fun stripThinkingBlocks(response: String): String {
    return response.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
}
```

### Crisis Keyword Detection

The system prompt instructs the model to recognize crisis indicators. A secondary keyword detection layer runs on the user's input as a safety net:

**Trigger phrases include:**
- "kill myself", "end my life", "want to die"
- "don't want to be here anymore", "no reason to live"
- "better off dead", "can't go on"
- "suicide", "self-harm", "cutting"

When detected:
1. Crisis resources overlay is shown immediately
2. System prompt switches to crisis mode
3. Response tone shifts to direct, calm, and focused on safety

---

## Color Palette & Theming

Solace uses a calming, muted color palette designed to reduce visual stimulation and create a sense of safety.

### Core Theme Colors

| Role               | Dark Mode     | Light Mode    |
|---------------------|---------------|---------------|
| **Primary**         | `#8BBAD4`     | `#5B8DB8`     |
| **Secondary**       | `#A8D5BA`     | `#A8D5BA`     |
| **Tertiary**        | `#F4A97F`     | `#F4A97F`     |
| **Background**      | `#121618`     | `#F8F6F3`     |
| **Surface**         | `#1A1F22`     | `#FFFFFF`     |
| **Error**           | `#E57373`     | `#E57373`     |
| **On Primary**      | `#FFFFFF`     | `#FFFFFF`     |
| **On Background**   | `#E8E6E3`     | `#1A1A1A`     |

### Design Philosophy

- **Soft blues** for primary interactions — calming, trustworthy
- **Sage green** for positive states — growth, stability
- **Warm peach** for gentle highlights — warmth, approachability
- **Deep charcoal backgrounds** — low visual stress, OLED-friendly
- **No harsh contrasts** — all text-to-background ratios meet WCAG AA but avoid stark black-on-white

### Session-Specific Palettes

Each session template defines its own gradient and accent color (see individual session sections). These colors are applied to:

- Session card background
- Header gradient
- Icon tint
- Border accents
- Bubble highlights during active session

---

## Privacy & Safety

### On-Device Inference

| Aspect              | Detail                                           |
|---------------------|--------------------------------------------------|
| **Model**           | Gemma 4 E2B Q4_K_M (GGUF format)                |
| **Size**            | ~2.5 GB quantized                                |
| **Runtime**         | llama.cpp compiled for ARM64                     |
| **Inference**       | All chat processing happens on-device             |
| **Network**         | No API calls during chat sessions                 |
| **Model Download**  | One-time download from HuggingFace, cached locally |
| **Model Storage**   | App internal storage, not accessible to other apps |

### Data Handling

| Data Type           | Storage          | Transmitted    | Retention          |
|---------------------|------------------|----------------|--------------------|
| Chat messages       | Local only       | Never          | Until user deletes |
| Mood entries        | Local SQLite     | Never          | Until user deletes |
| Crisis events       | Not logged       | Never          | N/A                |
| System prompts      | In-memory only   | Never          | Not persisted      |
| Thinking blocks     | In-memory only   | Never          | Stripped, not persisted |

### Safety Guardrails

- Solace is **not** a medical device and does not provide diagnoses
- Crisis sessions always surface professional resources
- The system prompt explicitly prevents the model from:
  - Providing medical advice or medication recommendations
  - Encouraging users to stop prescribed treatments
  - Minimizing suicidal ideation
  - Keeping secrets about self-harm
- A secondary keyword detection layer acts as a safety net beyond the system prompt

---

## Technical Implementation

### Model Loading

```kotlin
// Model initialization (simplified)
val modelPath = File(context.filesDir, "models/gemma-4-e2b-q4_k_m.gguf")
val llamaContext = LlamaContext(
    modelPath = modelPath.absolutePath,
    nCtx = 4096,
    nThreads = Runtime.getRuntime().availableProcessors()
)
```

### Session Initialization Flow

1. User selects session type from home screen
2. `SessionConfig` is created with session-specific parameters
3. `SystemPromptLoader.load(sessionType)` returns the appropriate system prompt
4. System prompt is prepended to conversation context
5. LLM context is initialized with system prompt + conversation history
6. First user message triggers inference

### Response Pipeline

```
User Input
    → Crisis keyword check (safety net)
    → Session system prompt (if new session)
    → Append to LLM context
    → llama.cpp inference
    → Strip thinking blocks
    → Display response
    → Send to TTS (if voice enabled)
```

### Text-to-Speech Integration

- Thinking blocks are **always** stripped before TTS
- TTS voice is configured for calm, moderate pacing
- Sleep sessions use 15% slower TTS speed
- Crisis sessions use clear, steady pacing (no speed adjustment)

---

## Accessibility

- All session cards meet WCAG AA contrast requirements
- Touch targets minimum 48dp
- TTS available for all AI responses
- Voice input supported via system speech recognition
- High-contrast mode respects system accessibility settings
- Font scaling supported up to 200%
- Screen reader labels on all interactive elements
- Crisis resources are always reachable within 2 taps from any screen

---

*Solace is a companion, not a clinician. If you or someone you know is in crisis, please contact a professional or call a crisis helpline immediately.*
