# Solace Android App — User Flow Documentation

> Comprehensive guide to every user journey through the Solace mental health companion app.

---

## Table of Contents

1. [App Architecture Overview](#1-app-architecture-overview)
2. [Flow 1: First Launch & Model Download](#2-flow-1-first-launch--model-download)
3. [Flow 2: Home Screen](#3-flow-2-home-screen)
4. [Flow 3: Chat](#4-flow-3-chat)
5. [Flow 4: Voice Input (ASR)](#5-flow-4-voice-input-asr)
6. [Flow 5: Image Analysis (Multimodal)](#6-flow-5-image-analysis-multimodal)
7. [Flow 6: Web Search](#7-flow-6-web-search)
8. [Flow 7: Guided Sessions (Roleplay)](#8-flow-7-guided-sessions-roleplay)
9. [Flow 8: Settings](#9-flow-8-settings)
10. [Flow 9: TTS Playback](#10-flow-9-tts-playback)
11. [Navigation Map](#11-navigation-map)
12. [Error Handling](#12-error-handling)
13. [Accessibility & Safety](#13-accessibility--safety)

---

## 1. App Architecture Overview

### Navigation Structure

```
┌─────────────────────────────────────────────┐
│                MasterLLMApp                  │
│                                             │
│  ┌──────────────┐                           │
│  │ ModelDownload │──(model ready)──►┌──────┐│
│  │   Screen      │                 │ Home  ││
│  └──────────────┘                 └──┬───┬─┘│
│                                     │   │   │
│                              ┌──────┘   │   │
│                              ▼          │   │
│                          ┌──────┐       │   │
│                          │ Chat │       │   │
│                          └──────┘       │   │
│                                         │   │
│                              ┌──────────┘   │
│                              ▼              │
│                      ┌────────────┐         │
│                      │ Roleplay   │         │
│                      │ (Sessions) │         │
│                      └────────────┘         │
│                                             │
│                      ┌────────────┐         │
│                      │  Settings  │         │
│                      └────────────┘         │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │        Bottom Navigation Bar            ││
│  │  Home │ Chat │ Sessions │ Settings      ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

### Routes

| Route | Screen | Description |
|-------|--------|-------------|
| `model_download` | `ModelDownloadScreen` | First-launch model setup |
| `home` | `SolaceHomeScreen` | Dashboard with mood, actions, crisis info |
| `chat` | `ChatScreen` | Free-form AI conversation |
| `roleplay` | `RoleplayScreen` | Guided therapeutic sessions |
| `settings` | `SettingsScreen` | App configuration |

### Key Source Files

| Component | File |
|-----------|------|
| Navigation host | `app/.../navigation/MasterLLMNavHost.kt` |
| Model download UI | `app/.../solace/ModelDownloadScreen.kt` |
| Model download manager | `app/.../solace/ModelDownloadManager.kt` |
| Chat ViewModel | `feature-chat/.../ChatViewModel.kt` |
| Chat Screen | `feature-chat/.../ChatScreen.kt` |
| Roleplay Screen | `feature-roleplay/.../RoleplayScreen.kt` |
| Roleplay ViewModel | `feature-roleplay/.../RoleplayViewModel.kt` |
| Settings Screen | `feature-settings/.../SettingsScreen.kt` |
| Settings ViewModel | `feature-settings/.../SettingsViewModel.kt` |
| GGUF Engine | `runtime-gguf/.../GgufEngine.kt` |
| Tool Registry | `runtime-gguf/.../ToolRegistry.kt` |
| Vosk Speech Manager | `core-data/.../VoskSpeechManager.kt` |
| Vosk Model Download | `core-data/.../VoskModelDownloadManager.kt` |
| Kitten TTS Engine | `core-data/.../KittenTtsEngine.kt` |
| TTS Text Filter | `core-data/.../TtsTextFilter.kt` |
| Crisis Resource Banner | `core-ui/.../components/CrisisResourceBanner.kt` |

---

## 2. Flow 1: First Launch & Model Download

**Entry:** App opens for the first time (or model files are missing/corrupted).
**Destination:** Home screen after successful download.

### Screen States

```
CheckingLocal ──► NeedsConsent ──► Downloading ──► Verifying ──► Ready
                    │                  │
                    │                  ▼
                    │               Error ──► (Retry)
                    │
                    └──(model exists)──► Ready (skip download)
```

### Step-by-Step Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. App launches → MasterLLMNavHost startDestination =      │
│     "model_download" → ModelDownloadScreen composable       │
│                                                             │
│  2. ModelDownloadViewModel.init() calls checkLocal()        │
│     └─ downloadManager.isModelReady()                       │
│        ├─ true  → phase = Ready → auto-navigate to Home     │
│        └─ false → phase = NeedsConsent                      │
│                                                             │
│  3. ConsentCard displays:                                   │
│     ┌───────────────────────────────────────────┐           │
│     │  Model Download Required                  │           │
│     │                                           │           │
│     │  Model:   Gemma 4 E2B (Q4_K_M)           │           │
│     │  Size:    ~3.1 GB                         │           │
│     │  Vision:  mmproj (~941 MB)                │           │
│     │  Source:  HuggingFace                     │           │
│     │  Context: 128K tokens                     │           │
│     │  Storage: External files dir              │           │
│     │                                           │           │
│     │  ┌─────────────────────────────────────┐  │           │
│     │  │   Download Model (~3.1 GB)          │  │           │
│     │  └─────────────────────────────────────┘  │           │
│     │  Requires internet connection             │           │
│     └───────────────────────────────────────────┘           │
│                                                             │
│  4. User taps "Download Model (~3.1 GB)"                    │
│     └─ viewModel.startDownload()                            │
│        ├─ phase = Downloading                               │
│        └─ downloadManager.ensureModelReady().collect{}      │
│                                                             │
│  5. Main GGUF model download:                               │
│     ┌───────────────────────────────────────────┐           │
│     │  Downloading Gemma 4 E2B...               │           │
│     │  ████████████████░░░░░░░░░░  65%          │           │
│     │  2.01 GB / 3.10 GB                        │           │
│     │  65%                                      │           │
│     │  Please keep the app open while           │           │
│     │  downloading.                             │           │
│     └───────────────────────────────────────────┘           │
│                                                             │
│  6. Main model download complete → SHA-256 verification     │
│     └─ phase = Verifying → "Verifying file integrity..."    │
│                                                             │
│  7. If mmproj not downloaded:                               │
│     └─ Automatically starts mmproj download (~941 MB)       │
│        └─ Progress updates shown in same UI                 │
│                                                             │
│  8. Both files verified → phase = Ready                     │
│     └─ isReady = true                                       │
│                                                             │
│  9. LaunchedEffect(uiState.isReady) triggers                │
│     └─ onModelReady() callback                              │
│        └─ navController.navigate(HOME) {                    │
│             popUpTo(MODEL_DOWNLOAD) { inclusive = true }     │
│           }                                                 │
└─────────────────────────────────────────────────────────────┘
```

### Error States

| Error | Behavior |
|-------|----------|
| Network failure | Error message displayed, "Retry Download" button shown |
| SHA-256 mismatch | File deleted, error displayed, retry available |
| mmproj failure | Non-blocking — main model still usable, proceeds to Home |
| Storage full | Error message with retry option |

### Technical Details

- **Model:** Gemma 4 E2B Q4_K_M quantization
- **Main file:** ~3.1 GB GGUF from HuggingFace
- **Vision projector:** mmproj ~941 MB
- **Verification:** SHA-256 hash check on both files
- **Storage location:** `context.getExternalFilesDir(null)`
- **Download is resumable** via HTTP range headers

---

## 3. Flow 2: Home Screen

**Entry:** Automatic after model download, or via bottom nav "Home" tab.
**Destination:** Chat or Guided Sessions.

### Screen Layout

```
┌─────────────────────────────────────────────┐
│                                             │
│              ✦ Solace ✦                     │
│      Your compassionate AI companion        │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │  How are you feeling today?             ││
│  │                                         ││
│  │  ┌──────────────┐ ┌──────────────┐      ││
│  │  │I need to talk│ │ I'm anxious  │      ││
│  │  └──────────────┘ └──────────────┘      ││
│  │  ┌──────────────┐ ┌──────────────┐      ││
│  │  │Just checking │ │I'm in crisis │      ││
│  │  │     in       │ │              │      ││
│  │  └──────────────┘ └──────────────┘      ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 💬 Talk to Solace                       ││
│  │    Open a conversation with your AI     ││
│  │    companion                        ►   ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🧘 Guided Sessions                     ││
│  │    Therapeutic exercises for anxiety,   ││
│  │    panic, sleep, and more           ►   ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ ⚠ In crisis? You are not alone.         ││
│  │                                         ││
│  │ 📞 988 Suicide & Crisis Lifeline (US)   ││
│  │ 📞 iCall India                          ││
│  │ 📞 Vandrevala Foundation                ││
│  │                                         ││
│  │ Tap a number to call. Your life has     ││
│  │ value.                                  ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────┬─────┬──────────┬──────────┐       │
│  │Home │Chat │Sessions  │Settings  │       │
│  └─────┴─────┴──────────┴──────────┘       │
└─────────────────────────────────────────────┘
```

### Interaction Map

| Element | Action | Destination |
|---------|--------|-------------|
| "I need to talk" chip | Tap | Chat screen |
| "I'm anxious" chip | Tap | Chat screen |
| "Just checking in" chip | Tap | Chat screen |
| "I'm in crisis" chip | Tap | Chat screen |
| "Talk to Solace" card | Tap | Chat screen |
| "Guided Sessions" card | Tap | Roleplay screen |
| 988 helpline | Tap | System dialer (`tel:988`) |
| iCall India | Tap | System dialer (`tel:9152987821`) |
| Vandrevala Foundation | Tap | System dialer (`tel:18602662345`) |

### Mood Chip Behavior

All four mood chips navigate to the Chat screen. They serve as emotional entry points — the specific chip text is not passed to the chat context. The user lands on the conversation list and can start a new chat from there.

---

## 4. Flow 3: Chat

**Entry:** "Talk to Solace" card on Home, "Chat" bottom nav tab, or mood chip tap.
**Destination:** Free-form conversation with the AI model.

### Screen States

```
ConversationListPane ◄──────► ChatPane
       │                         │
       │  tap conversation        │  BackToList
       └────────►─────────────────┘
```

### Conversation List Pane

```
┌─────────────────────────────────────────────┐
│  Solace                              [+]    │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ ⚠ Crisis resources available            ││
│  │   [CrisisResourceBanner]                ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 💬 How to manage anxiety            [🗑] ││
│  │    12 messages                          ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │ 💬 Sleep tips for tonight           [🗑] ││
│  │    8 messages                           ││
│  └─────────────────────────────────────────┘│
│                                             │
│  (or EmptyState if no conversations)        │
│  ┌─────────────────────────────────────────┐│
│  │         💬                              ││
│  │  No conversations yet                   ││
│  │  Start a new chat to begin              ││
│  │        [New Chat]                       ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

### Chat Pane — Full Layout

```
┌─────────────────────────────────────────────┐
│  ◄  How to manage anxiety          ⚙       │
│                                             │
│  ┌─ Generation Status (if active) ─────────┐│
│  │ ⏳ Loading model...                     ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌──────────────────────┐                   │
│  │ I've been feeling    │                   │
│  │ really anxious       │                   │
│  │ lately               │                   │
│  └──────────────────────┘                   │
│                                             │
│      ┌──────────────────────────────────┐   │
│      │ 🤖 Solace                        │   │
│      │                                  │   │
│      │ [Thinking] ▾                     │   │
│      │                                  │   │
│      │ I hear you, and I want you to    │   │
│      │ know that what you're feeling    │   │
│      │ is valid...                      │   │
│      └──────────────────────────────────┘   │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🖼 ┃ Type a message...    🔍 🎤 [Send] ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

### Step-by-Step: Sending a Message

```
┌──────────────────────────────────────────────────────────────┐
│ 1. User types text in input field                            │
│    └─ ChatAction.InputChanged(text) → updates inputText      │
│                                                              │
│ 2. User taps Send button                                     │
│    └─ ChatAction.SendMessage → sendMessage()                 │
│                                                              │
│ 3. If no current conversation:                               │
│    └─ createNewConversation() → Room DB insert               │
│       └─ observeConversation(id) → loads messages            │
│                                                              │
│ 4. Resolve selected model:                                   │
│    └─ resolveSelectedModel(conversation)                     │
│       ├─ Checks selectedModelId from UI state                │
│       ├─ Falls back to conversation.modelId                  │
│       └─ Falls back to first available model                 │
│                                                              │
│ 5. Clear input, set isGenerating = true                      │
│    └─ streamingText = "", generationStatus = "Loading..."    │
│                                                              │
│ 6. Ensure engine ready:                                      │
│    └─ ensureEngineReady(conversation)                        │
│       ├─ If model already loaded & params match → return     │
│       └─ Else → runtimeCoordinator.withEngineLock {          │
│            ggufEngine.load(modelPath, params, gpu...)        │
│          }                                                   │
│                                                              │
│ 7. Persist user message to Room DB                           │
│    └─ Message(role=USER, content=text)                       │
│                                                              │
│ 8. Token generation loop:                                    │
│    └─ ggufEngine.getResponseAsFlow(text, maxTokens)          │
│       .collect { piece →                                     │
│          builder.append(piece)                               │
│          _uiState.update { streamingText = builder.toString }│
│       }                                                      │
│                                                              │
│ 9. On completion:                                            │
│    ├─ Sanitize generated text (strip EOG tokens, etc.)       │
│    ├─ Persist assistant message to Room DB                   │
│    ├─ Update generation stats (tokens/sec, latency, etc.)    │
│    ├─ Update conversation title from first user message      │
│    └─ If TTS enabled → trigger TTS playback                  │
│                                                              │
│ 10. Reset generation state:                                  │
│     └─ isGenerating = false, streamingText = ""              │
└──────────────────────────────────────────────────────────────┘
```

### Model Configuration Dialog

Accessible via the ⚙ icon in the chat top bar. Opens an `AlertDialog` with:

| Parameter | Range | Default |
|-----------|-------|---------|
| Temperature | 0.0 – 2.0 | 0.7 |
| Top P | 0.0 – 1.0 | 0.9 |
| Top K | 1 – 100 | 40 |
| Repeat Penalty | 1.0 – 2.0 | 1.1 |
| Max Tokens | 64 – 8192 | 512 |
| System Prompt | Free text | (empty) |

Also displays:
- Selected model info (name, format, quantization, size)
- Runtime state (status, backend, offload summary, load time)
- "Reset to Defaults" button
- "Done" button to dismiss

### Message Bubble Features

| Feature | Description |
|---------|-------------|
| User messages | Right-aligned, primary color bubble |
| AI messages | Left-aligned, surface variant bubble, Solace avatar |
| Thinking section | Collapsible `<thinking>` block with expand/collapse |
| Markdown rendering | Full markdown support for AI responses |
| Attached images | Displayed inline with rounded corners |
| Long-press | Copies message text to clipboard |
| Streaming | Real-time text appearance during generation |

### Conversation Management

| Action | Trigger | Result |
|--------|---------|--------|
| New conversation | `+` button | Creates empty conversation, opens chat pane |
| Select conversation | Tap conversation card | Loads messages, opens chat pane |
| Delete conversation | 🗑 icon on conversation card | Removes from Room DB |
| Back to list | ◄ back arrow | Returns to conversation list |

---

## 5. Flow 4: Voice Input (ASR)

**Entry:** Mic button in chat input bar.
**Destination:** Voice transcript fills input field.

### State Machine

```
                 ┌────────────────────┐
                 │   Vosk not ready   │
                 │  (model missing)   │
                 └────────┬───────────┘
                          │ tap mic
                          ▼
                 ┌────────────────────┐
                 │ Download Vosk Model│
                 │  (~40 MB download) │
                 └────────┬───────────┘
                          │ complete
                          ▼
                 ┌────────────────────┐
                 │  Vosk Model Ready  │
                 └────────┬───────────┘
                          │ tap mic
                          ▼
              ┌───────────────────────┐
              │     LISTENING         │◄──── tap mic again
              │  (partial transcripts │      to stop
              │   shown in real-time) │
              └───────────┬───────────┘
                          │ stop speaking / tap mic
                          ▼
              ┌───────────────────────┐
              │   IDLE                │
              │  finalTranscript →    │
              │  inputText field      │
              └───────────────────────┘
```

### Step-by-Step Flow

```
┌──────────────────────────────────────────────────────────────┐
│ 1. User taps mic button (🎤)                                 │
│                                                              │
│ 2. Check: voskModelReady?                                    │
│    ├─ false → ChatAction.DownloadVoskModel                   │
│    │   └─ VoskModelDownloadManager downloads ~40 MB from     │
│    │      alphacephei.com into app storage                   │
│    │   └─ On complete → voskSpeechManager.initModel()        │
│    │      └─ voskModelReady = true                           │
│    │                                                         │
│    └─ true → proceed                                         │
│                                                              │
│ 3. ChatAction.StartListening                                 │
│    └─ voskSpeechManager.startListening()                     │
│       └─ asrState.state = LISTENING                          │
│                                                              │
│ 4. Partial transcripts stream in real-time:                  │
│    └─ voskSpeechManager.asrState.collect { asrState →        │
│         _uiState.update { copy(asrState = asrState) }        │
│       }                                                      │
│                                                              │
│ 5. User stops speaking (or taps mic again):                  │
│    └─ ChatAction.StopListening                               │
│       └─ voskSpeechManager.stopListening()                   │
│          └─ asrState.state = IDLE                            │
│             └─ finalTranscript populated                     │
│                                                              │
│ 6. Transcript auto-fills input field:                        │
│    └─ if (asrState.state == IDLE &&                         │
│          asrState.finalTranscript.isNotEmpty())              │
│       → _uiState.update { inputText = finalTranscript }      │
│                                                              │
│ 7. User reviews text, taps Send (or edits first)             │
└──────────────────────────────────────────────────────────────┘
```

### UI Behavior

| State | Mic Icon | Color | Behavior |
|-------|----------|-------|----------|
| Vosk not ready | 🎤 | Muted gray | Tap triggers download |
| Ready, not listening | 🎤 | Primary blue | Tap starts listening |
| Listening | 🎤 (MicOff) | Error red | Tap stops listening |
| Generating | 🎤 | Disabled | No interaction |

---

## 6. Flow 5: Image Analysis (Multimodal)

**Entry:** Image attach button in chat input bar.
**Destination:** AI analyzes the attached image with the user's text prompt.

### Step-by-Step Flow

```
┌──────────────────────────────────────────────────────────────┐
│ 1. User taps image button (🖼)                               │
│    └─ imagePicker.launch("image/*")                          │
│       └─ Android system image picker opens                   │
│                                                              │
│ 2. User selects an image from gallery                        │
│    └─ ActivityResultContracts.GetContent callback            │
│       ├─ Opens input stream from content URI                 │
│       ├─ Copies to app storage:                              │
│       │   filesDir/chat_attachments/{timestamp}.jpg          │
│       └─ ChatAction.AttachImage(destFile.absolutePath)       │
│                                                              │
│ 3. Image preview appears in input bar:                       │
│    ┌────────────────────────────────────────────┐            │
│    │ [🖼 preview] Image attached          [✕]   │            │
│    │ 🖼 Type a message...       🔍 🎤 [Send]   │            │
│    └────────────────────────────────────────────┘            │
│    └─ pendingImageAttachment = imagePath                     │
│                                                              │
│ 4. User types optional text and taps Send                    │
│    └─ ChatAction.SendMessage → sendMessage()                 │
│                                                              │
│ 5. Vision capability check:                                  │
│    └─ ggufEngine.supportsVision()                            │
│       ├─ true (mmproj loaded) → multimodal path              │
│       └─ false → text-only fallback                          │
│                                                              │
│ 6a. MULTIMODAL PATH (mmproj available):                      │
│     ├─ Persist user message with attachedImagePath           │
│     ├─ BitmapFactory.decodeFile(imagePath) → Bitmap          │
│     ├─ bitmapToRgbBytes(bitmap) → ByteArray                  │
│     ├─ ggufEngine.startCompletionWithImage(                  │
│     │      text, rgbBytes, width, height)                    │
│     │   └─ mtmd pipeline processes image + text              │
│     ├─ Token stream loop → streaming text                    │
│     └─ ggufEngine.stopCompletion()                           │
│                                                              │
│ 6b. TEXT-ONLY FALLBACK (no mmproj):                          │
│     ├─ Persist system message:                               │
│     │   "The user has attached an image at: {path}.          │
│     │    Describe what you see..."                           │
│     └─ ggufEngine.getResponseAsFlow(text) → stream           │
│                                                              │
│ 7. Response displayed with image + text in chat              │
│    └─ pendingImageAttachment cleared                         │
└──────────────────────────────────────────────────────────────┘
```

### Image Display in Messages

- User messages with attached images show the image inline above the text
- Images are loaded from persisted file path via `BitmapFactory.decodeFile()`
- Displayed with rounded corners, max height 320dp

---

## 7. Flow 6: Web Search

**Entry:** Web search button (🔍) in chat input bar.
**Destination:** AI response enriched with web search context.

### Step-by-Step Flow

```
┌──────────────────────────────────────────────────────────────┐
│ 1. User types a query in the input field                     │
│                                                              │
│ 2. User taps web search button (🔍)                          │
│    └─ ChatAction.WebSearch(query = state.inputText)          │
│                                                              │
│ 3. performWebSearch(query) executes:                         │
│    └─ ToolRegistry.web_search(query)                         │
│       ├─ DuckDuckGo HTML search (no API key required)        │
│       ├─ Parses search results page                          │
│       └─ Returns structured results                          │
│                                                              │
│ 4. Results injected as system message:                       │
│    └─ Message(role=SYSTEM, content=searchResults)            │
│       └─ Added to conversation in Room DB                    │
│                                                              │
│ 5. Generation triggered with search context:                 │
│    └─ Model receives search results as part of context       │
│       └─ Generates informed response                         │
│                                                              │
│ 6. Response displayed in chat with streaming                 │
└──────────────────────────────────────────────────────────────┘
```

### ToolRegistry Implementation

The `ToolRegistry` class provides the `web_search()` function:
- Scrapes DuckDuckGo HTML search results
- Returns formatted text with titles, URLs, and snippets
- No API keys or external services required
- Results are context-limited to fit the model's window

---

## 8. Flow 7: Guided Sessions (Roleplay)

**Entry:** "Guided Sessions" card on Home, or "Sessions" bottom nav tab.
**Destination:** Therapeutic conversation with Solace using specialized prompts.

### Screen States

```
SessionListPane ◄────────► SessionChatPane
      │                          │
      │  tap session/template    │  BackToList
      └──────────►───────────────┘
```

### Session Templates

| Template | ID | Color | Icon | Purpose |
|----------|----|-------|------|---------|
| Anxiety Relief | `anxiety_relief` | Blue gradient | 🌬️ | Box breathing, 5-4-3-2-1 grounding, PMR |
| Panic Attack Support | `panic_attack` | Red gradient | ❤️ | Immediate stabilization, slow breathing |
| Sleep & Rest | `sleep_rest` | Purple gradient | 🌙 | Body scan, visualization, calming |
| Daily Check-in | `daily_checkin` | Green gradient | 📅 | Mood tracking, reflection, self-care |
| Crisis Support | `crisis_support` | Orange gradient | 🛡️ | Safety planning, de-escalation |

### Session List Pane

```
┌─────────────────────────────────────────────┐
│  Guided Sessions                            │
│                                             │
│  Choose a session to begin a guided         │
│  therapeutic exercise with Solace.          │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │  🌬️  Anxiety Relief                     ││
│  │      Anxiety Relief                     ││
│  │                                         ││
│  │  Guided breathing and grounding         ││
│  │  exercises to ease anxious thoughts     ││
│  │  and restore calm.                      ││
│  │                                         ││
│  │  ┌─────────────────────────────────────┐││
│  │  │  ▶ Start Session                   │││
│  │  └─────────────────────────────────────┘││
│  └─────────────────────────────────────────┘│
│                                             │
│  (repeat for all 5 templates)               │
│                                             │
│  ── Previous Sessions ─────────────────────│
│  ┌─────────────────────────────────────────┐│
│  │  🕐 Anxiety Relief                  [🗑]││
│  │     Anxiety Relief                      ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │  🕐 Sleep & Rest                    [🗑]││
│  │     Sleep & Rest                        ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

### Step-by-Step: Starting a Session

```
┌──────────────────────────────────────────────────────────────┐
│ 1. User taps "Start Session" on a template card              │
│                                                              │
│ 2. RoleplayAction sequence fires:                            │
│    ├─ SetupTitleChanged(template.title)                       │
│    ├─ SetupGenreChanged(template.genre)                       │
│    ├─ SetupPremiseChanged(template.description)               │
│    ├─ SetupAiNameChanged(template.aiCharacterName)            │
│    ├─ SetupAiDescChanged(template.aiCharacterDescription)     │
│    ├─ SetupUserNameChanged("You")                             │
│    ├─ SetupUserDescChanged("")                                │
│    └─ CreateSession                                          │
│                                                              │
│ 3. ViewModel creates RoleplaySession in Room DB              │
│    └─ Session includes system prompt from template           │
│                                                              │
│ 4. UI transitions to SessionChatPane                         │
│    └─ AnimatedContent switches from list to chat             │
│                                                              │
│ 5. Session intro card displayed:                             │
│    ┌─────────────────────────────────────────┐               │
│    │ 🧘 Your Anxiety Relief session has      │               │
│    │    begun. Take your time — Solace is    │               │
│    │    here to listen.                      │               │
│    └─────────────────────────────────────────┘               │
│                                                              │
│ 6. User types/shares in input bar                            │
│    └─ Placeholder: "Share what's on your mind..."            │
│                                                              │
│ 7. Messages flow through same GGUF engine as Chat            │
│    └─ System prompt from template guides AI behavior         │
│                                                              │
│ 8. Streaming response displayed with Solace avatar           │
└──────────────────────────────────────────────────────────────┘
```

### Session Chat Pane Layout

```
┌─────────────────────────────────────────────┐
│  ◄  Anxiety Relief                          │
│      with Solace                            │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🧘 Your Anxiety Relief session has      ││
│  │    begun. Take your time — Solace is    ││
│  │    here to listen.                      ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌──────────────────────┐                   │
│  │ I can't stop my      │                   │
│  │ racing thoughts      │                   │
│  └──────────────────────┘                   │
│                                             │
│      ┌──────────────────────────────────┐   │
│      │ 🧘 Solace                        │   │
│      │                                  │   │
│      │ I understand how overwhelming    │   │
│      │ that can feel. Let's try         │   │
│      │ something together...            │   │
│      └──────────────────────────────────┘   │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ Share what's on your mind...    [Stop]  ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

### Previous Sessions

- Listed below the template cards
- Tap to resume a previous session (loads conversation history)
- Delete button (🗑) to remove sessions
- Each card shows session title and genre

---

## 9. Flow 8: Settings

**Entry:** "Settings" bottom nav tab.
**Destination:** App configuration screen.

### Settings Sections

```
┌─────────────────────────────────────────────┐
│  Settings                                   │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ ⚙ Appearance                            ││
│  │   Choose how the app theme is applied   ││
│  │                                         ││
│  │   ┌──────┐ ┌──────┐ ┌──────┐           ││
│  │   │System│ │ Light│ │ Dark │           ││
│  │   └──────┘ └──────┘ └──────┘           ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🎙 Voice                                ││
│  │   Enable or disable text-to-speech      ││
│  │                                         ││
│  │   Voice output              [────●─]    ││
│  │   Read assistant responses aloud        ││
│  │   using text-to-speech.                 ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🧠 Thinking                             ││
│  │   Control visibility of model reasoning ││
│  │                                         ││
│  │   Show thinking             [────●─]    ││
│  │   Display the model's internal          ││
│  │   reasoning before the final answer.    ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🎛 Inference Parameters                 ││
│  │   Fine-tune how the model generates text││
│  │                                         ││
│  │   Temperature                           ││
│  │   Higher = more creative, lower = focus ││
│  │   0.70 ════════●═════════════ 2.00      ││
│  │                                         ││
│  │   Top P                                 ││
│  │   Nucleus sampling threshold            ││
│  │   0.90 ═══════════●══════════ 1.00      ││
│  │                                         ││
│  │   Top K                                 ││
│  │   Limits sampling to K most probable    ││
│  │   40 ════════●═════════════════ 100     ││
│  │                                         ││
│  │   Context Length                        ││
│  │   Max tokens the model considers        ││
│  │   16384 ═══════●═════════════ 131072    ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🔄 Context Compaction                   ││
│  │   Manage automatic conversation         ││
│  │   summarization                         ││
│  │                                         ││
│  │   Automatic context compaction  [●────] ││
│  │   Automatically summarize older         ││
│  │   messages when the context window      ││
│  │   fills up.                             ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ 🗑 Model Storage                        ││
│  │   Manage downloaded model files         ││
│  │                                         ││
│  │   Downloaded model files can be         ││
│  │   several gigabytes. Delete them to     ││
│  │   free up storage space.                ││
│  │                                         ││
│  │   ┌────────────────────────────────────┐││
│  │   │  🗑 Delete Downloaded Model        │││
│  │   └────────────────────────────────────┘││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │ ℹ️ About Solace                         ││
│  │   Your mental health companion          ││
│  │                                         ││
│  │   Version 2.0.2                         ││
│  │                                         ││
│  │   ── Crisis Helplines ──                ││
│  │   If you or someone you know is in      ││
│  │   crisis, please reach out:             ││
│  │                                         ││
│  │   988 Suicide & Crisis Lifeline (US)    ││
│  │   988                      [📞 Call]    ││
│  │                                         ││
│  │   iCall Counselling (India)             ││
│  │   9152987821               [📞 Call]    ││
│  │                                         ││
│  │   Vandrevala Foundation (India)         ││
│  │   18602662345              [📞 Call]    ││
│  │                                         ││
│  │   Your life has value. Help is          ││
│  │   available.                            ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────┬─────┬──────────┬──────────┐       │
│  │Home │Chat │Sessions  │Settings  │       │
│  └─────┴─────┴──────────┴──────────┘       │
└─────────────────────────────────────────────┘
```

### Delete Model Confirmation

```
┌─────────────────────────────────────────────┐
│  Delete Downloaded Model                    │
│                                             │
│  This will permanently remove the           │
│  downloaded model file from your device.    │
│  You can re-download it later from the      │
│  marketplace.                               │
│                                             │
│         [Cancel]  [Delete (red)]            │
└─────────────────────────────────────────────┘
```

### Settings Actions

| Setting | Action | Effect |
|---------|--------|--------|
| Theme | Select System/Light/Dark | Applies immediately via `AppThemeViewModel` |
| Voice output | Toggle switch | Enables/disables TTS after AI responses |
| Show thinking | Toggle switch | Shows/hides `<thinking>` blocks in messages |
| Temperature | Slider 0.0–2.0 | Changes model creativity |
| Top P | Slider 0.0–1.0 | Changes nucleus sampling |
| Top K | Slider 1–100 | Changes token sampling limit |
| Context Length | Slider 2048–131072 | Changes context window size |
| Context compaction | Toggle switch | Auto-summarizes old messages |
| Delete model | Button → Confirmation | Removes model files from storage |
| Crisis helplines | Tap "Call" | Opens system dialer |

---

## 10. Flow 9: TTS Playback

**Entry:** Automatic after AI completes a response (if TTS enabled).
**Destination:** Audio playback of the AI response.

### Step-by-Step Flow

```
┌──────────────────────────────────────────────────────────────┐
│ 1. AI response generation completes                          │
│    └─ finally block in sendMessage()                         │
│                                                              │
│ 2. TTS check:                                                │
│    └─ if (_uiState.value.ttsEnabled)                         │
│       └─ Get last AI message from conversation               │
│                                                              │
│ 3. Text filtering:                                           │
│    └─ TtsTextFilter.filter(lastAiMessage.content)            │
│       ├─ Strips <thinking>...</thinking> blocks              │
│       ├─ Strips <think>...</think> blocks                       │
│       ├─ Removes markdown formatting                         │
│       ├─ Removes HTML tags                                   │
│       └─ Returns clean spoken text                           │
│                                                              │
│ 4. If filtered text is not blank:                            │
│    └─ _uiState.update { isSpeaking = true }                  │
│                                                              │
│ 5. TTS engine execution:                                     │
│    └─ kittenTtsEngine.speak(filteredText)                    │
│       ├─ KittenTtsEngine uses ONNX runtime                   │
│       ├─ Generates audio from text                           │
│       └─ Plays via AudioTrack                                │
│                                                              │
│ 6. During playback:                                          │
│    └─ Stop button (🔊→🔇) visible in input bar               │
│       └─ ChatAction.StopSpeaking                             │
│          ├─ kittenTtsEngine.stop()                           │
│          └─ isSpeaking = false                               │
│                                                              │
│ 7. Playback completes:                                       │
│    └─ _uiState.update { isSpeaking = false }                 │
└──────────────────────────────────────────────────────────────┘
```

### TTS Text Filter Processing

```
Input:  "<think>The user seems anxious. I should be gentle.</think>
         I understand how you're feeling. **You're not alone.**
         <br>Let's try a breathing exercise together."

Output: "I understand how you're feeling. You're not alone.
         Let's try a breathing exercise together."
```

### UI States During TTS

| State | Input Bar Behavior |
|-------|--------------------|
| TTS playing | Stop speaker button (🔇) appears next to send button |
| TTS idle | Normal input bar, no stop button |
| TTS disabled in settings | No TTS triggered after responses |

---

## 11. Navigation Map

### Complete Route Graph

```
                    ┌──────────────┐
                    │   LAUNCH     │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
          ┌────────│ ModelDownload │────────┐
          │        └──────┬───────┘        │
          │               │ onModelReady    │
          │        ┌──────▼───────┐        │
          │        │     HOME     │        │
          │        └──┬───┬───┬───┘        │
          │           │   │   │            │
          │     ┌─────┘   │   └─────┐      │
          │     │         │         │      │
          │  ┌──▼──┐  ┌───▼───┐    │      │
          │  │CHAT │  │ROLEPLAY│   │      │
          │  └──┬──┘  └───┬───┘    │      │
          │     │         │        │      │
          │     │    ┌────▼────┐   │      │
          │     │    │SESSION  │   │      │
          │     │    │  CHAT   │   │      │
          │     │    └─────────┘   │      │
          │     │                  │      │
          │  ┌──▼──────────────────▼──┐   │
          │  │    SETTINGS            │   │
          │  │  (accessible from any) │   │
          │  └────────────────────────┘   │
          │                               │
          │  Bottom Nav: Home|Chat|Sessions|Settings
          └───────────────────────────────┘
```

### Bottom Navigation Behavior

- Visible on all top-level routes: Home, Chat, Roleplay, Settings
- Hidden on ModelDownload screen
- Uses `saveState = true` and `restoreState = true` for state preservation
- `launchSingleTop = true` prevents duplicate destinations

---

## 12. Error Handling

### Error Display Patterns

| Screen | Error Display | Dismiss Action |
|--------|---------------|----------------|
| Model Download | Inline text + retry button | Retry button |
| Chat | Snackbar at bottom | "Dismiss" text button |
| Guided Sessions | Snackbar at bottom | "Dismiss" text button |
| Settings | Snackbar at bottom | "Dismiss" text button |

### Common Error Scenarios

| Scenario | User Experience |
|----------|-----------------|
| No model downloaded | "Download a model in Marketplace before chatting." |
| Model load failure | Snackbar: "Model load failed: {details}" |
| Generation failure | Snackbar: "Generation failed: {details}" |
| Partial response | Snackbar: "Response was partially returned ({reason})." |
| ASR model missing | Triggers Vosk model download automatically |
| TTS failure | Silent — logged, no user-facing error |
| Image decode failure | Falls back to text-only inference |

### Generation Cancellation

- User taps Stop button during generation
- `ChatAction.StopGeneration` → `stopGeneration()`
- Sets `isGenerating = false`
- Partial streaming text is **not** persisted to Room DB
- Engine cleanup via `ggufEngine.stopCompletion()`

---

## 13. Accessibility & Safety

### Crisis Resources

Crisis helplines appear in three locations:

1. **Home screen** — Dedicated crisis card with tap-to-call buttons
2. **Chat conversation list** — `CrisisResourceBanner` at top
3. **Settings** — "About Solace" section with call buttons

| Service | Number | Region |
|---------|--------|--------|
| 988 Suicide & Crisis Lifeline | `988` | United States |
| iCall Counselling | `9152987821` | India |
| Vandrevala Foundation | `18602662345` | India |

### Therapeutic Safety

- All guided session system prompts include safety instructions
- Crisis Support template explicitly instructs the AI to:
  - Validate feelings without minimizing
  - Gently assess safety
  - Provide crisis resources
  - Encourage professional help
- AI is instructed it is "not a replacement for professional help"
- System prompts use evidence-based techniques (CBT, grounding, PMR)

### Privacy

- All inference runs **locally on-device** (GGUF engine)
- No data sent to external servers for AI responses
- Web search uses DuckDuckGo (no API key, no tracking)
- Conversations stored in local Room database
- Model files cached in external files directory
- User can delete all model data from Settings

### TTS Safety

- `TtsTextFilter` strips thinking/reasoning blocks before speaking
- Prevents internal reasoning from being read aloud
- User can toggle TTS on/off in Settings
- Stop button available during playback
