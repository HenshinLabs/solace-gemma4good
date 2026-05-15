# Handoff Document — Master LLM → Solace (Gemma 4 E2B Migration)

**Date**: 2026-05-15
**Migration**: Master LLM v1.0.33 (Qwen3.5-0.8B) → Solace v2.0.0 (Gemma 4 E2B Q4_K_M)

---

## 1. Summary of All Changes

### New Files Created

| File | Purpose |
|------|---------|
| `app/.../solace/ModelDownloadManager.kt` | Download-on-first-launch for Gemma 4 E2B GGUF (resume support, SHA-256 verification) |
| `app/.../solace/ModelDownloadScreen.kt` | Full-screen download progress UI + ViewModel (gate before main app) |
| `app/.../solace/ThinkingTokenParser.kt` | Streaming parser for Gemma 4 `<\|channel>thought` / `<channel\|>` blocks |
| `app/.../solace/TtsTextFilter.kt` | Strips thinking blocks, HTML, markdown from text before TTS |
| `app/.../solace/Gemma4ChatTemplate.kt` | Gemma 4 chat template constants, formatting utilities, recommended params |
| `app/.../solace/SystemPromptLoader.kt` | Loads Solace system prompt from `res/raw/system_prompt.txt` |
| `app/.../solace/SpeechRecognitionManager.kt` | Enhanced ASR manager with state flow, error handling, auto-submit |
| `app/src/main/res/raw/system_prompt.txt` | Solace mental health companion system prompt (crisis protocol, CBT/DBT techniques) |
| `app/src/main/res/values/colors_solace.xml` | Therapeutic color palette (calm blue, sage green, warm peach) |
| `app/src/main/res/drawable/ic_solace_logo.xml` | Vector drawable: open hand + heart + wave arcs |
| `lint.xml` | Static analysis configuration (HardcodedText, MissingPermission, WrongThread, NewApi) |
| `keystore.properties.template` | Release signing configuration template |
| `RELEASE_CHECKLIST.md` | Pre-release verification checklist |
| `EXPLORATION_SUMMARY.md` | Full codebase exploration audit document |
| `HANDOFF.md` | This document |

### Files Modified

| File | Changes |
|------|---------|
| `core-data/.../BundledModelManager.kt` | Replaced all Qwen3.5 refs with Gemma 4 E2B constants (MODEL_ID, ARCHITECTURE, CONTEXT_LENGTH, chat template, inference params). Changed from APK asset extraction to external download path lookup. |
| `runtime-gguf/.../GgufEngine.kt` | Updated `DEFAULT_CONTEXT_SIZE` from 1024 to 16384. Replaced `QWEN35_CHAT_TEMPLATE` with `GEMMA4_CHAT_TEMPLATE` using `<\|turn>`/`<turn\|>` delimiters. |
| `app/.../MasterLLMApplication.kt` | Removed direct `BundledModelManager.initialize()` dependency for asset extraction. Added notification channel creation. |
| `app/.../navigation/MasterLLMNavHost.kt` | Added `MODEL_DOWNLOAD` route as start destination (gates app behind download). Imported `ModelDownloadScreen`. |
| `app/.../navigation/AgentScreen.kt` | Changed "Qwen3.5-0.8B" label to "Gemma 4 E2B" |
| `app/.../openclaw/AgentViewModel.kt` | Changed "Qwen3.5 ready" status message to "Gemma 4 E2B ready" |
| `app/.../openclaw/OpenClawEngine.kt` | Updated `buildConversationContext()` from `<\|im_start\|>`/`<\|im_end\|>` to `<\|turn>`/`<turn\|>` delimiters |
| `app/.../openclaw/ToolRegistry.kt` | Updated system prompt model reference to "Gemma 4 E2B" |
| `feature-chat/.../ChatViewModel.kt` | Added Gemma 4 turn delimiter stripping in `sanitizeGeneratedText()`. Updated bundled model reference. |
| `feature-roleplay/.../RoleplayViewModel.kt` | Added Gemma 4 turn delimiter stripping in `sanitizeGeneratedText()` |
| `core-domain/.../repository/ModelRepository.kt` | Added 5 new `SettingsRepository` methods: `showThinking`, `thinkingBudget`, `contextLength`, `voiceOutputEnabled`, `voiceInputEnabled` |
| `core-data/.../repository/SettingsRepositoryImpl.kt` | Implemented 5 new settings with DataStore keys and defaults |
| `app/src/main/AndroidManifest.xml` | Added `RECORD_AUDIO` permission, `<uses-feature>` for microphone, updated icon refs to `@mipmap/ic_launcher` |
| `app/src/main/res/values/strings.xml` | Changed `app_name` from "Master LLM" to "Solace" |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Points to Solace logo + primary color background |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Points to Solace logo + primary color background |
| `app/build.gradle.kts` | Bumped version to 2.0.0. Added `MODEL_DOWNLOAD_URL`, `MODEL_SHA256`, `MODEL_FILENAME` buildConfig fields. Updated APK filename to "Solace-". |
| `app/proguard-rules.pro` | Added rules for Solace classes, JNI methods, Kotlin coroutines |

### Files NOT Deleted (Intentional)

| File | Reason |
|------|--------|
| `app/src/main/res/drawable/ic_app_logo.xml` | May be referenced elsewhere; safe to remove after full build verification |
| `app/src/main/res/drawable/ic_launcher_background.xml` | Same — remove after verifying no other references |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Same |

---

## 2. New Dependencies

No new dependencies were added. All functionality uses existing libraries:
- ONNX Runtime (already present for KittenTTS)
- Android SpeechRecognizer (platform API)
- Android TextToSpeech (platform API)
- OkHttp/Retrofit (already present)

---

## 3. Build Instructions

### Prerequisites
1. Initialize llama.cpp submodule:
   ```bash
   git submodule update --init llama.cpp
   ```
2. Android Studio with NDK installed (for native llama.cpp compilation)
3. JDK 17+

### Debug Build
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/Solace-v2.0.0-debug.apk`

### Release Build
1. Copy `keystore.properties.template` → `keystore.properties`
2. Fill in your signing keystore values
3. Set gradle properties or environment variables:
   ```bash
   export MASTER_LLM_RELEASE_STORE_FILE=/path/to/solace-release.jks
   export MASTER_LLM_RELEASE_STORE_PASSWORD=your_password
   export MASTER_LLM_RELEASE_KEY_ALIAS=solace
   export MASTER_LLM_RELEASE_KEY_PASSWORD=your_password
   ```
4. ```bash
   ./gradlew assembleRelease
   ```

### Generate Keystore
```bash
keytool -genkey -v -keystore solace-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias solace
```

---

## 4. First-Run Instructions

### Model Hosting
The Gemma 4 E2B Q4_K_M GGUF file (~3.11 GB) must be hosted at the URL specified in `ModelDownloadManager.MODEL_DOWNLOAD_URL`. Options:
- **Default**: Direct HuggingFace download (`https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf`)
- **Production**: Host on your own CDN for reliability and speed
- Update the URL in `app/build.gradle.kts` → `MODEL_DOWNLOAD_URL` buildConfigField

### First Launch
1. App opens to ModelDownloadScreen
2. Downloads ~3.11 GB model to `getExternalFilesDir(null)/models/`
3. Verifies SHA-256: `9378bc471710229ef165709b62e34bfb62231420ddaf6d729e727305b5b8672d`
4. On success, navigates to Home screen
5. Subsequent launches skip download if model exists and is verified

### TTS
No special setup needed. KittenTTS (bundled ONNX model) and Android built-in TTS work out of the box. No Microsoft/Azure Speech SDK is used.

---

## 5. Known Issues and REVIEW Items

### REVIEW Comments (verify before first compile)

| File | Item |
|------|------|
| `ModelDownloadManager.kt` | `MODEL_DOWNLOAD_URL` — must point to reliable CDN for production |
| `ModelDownloadManager.kt` | `MODEL_SHA256` — verified from unsloth repo; re-verify if switching GGUF source |
| `ModelDownloadManager.kt` | `MODEL_SIZE_BYTES` — approximate; update after confirming exact byte size |
| `Gemma4ChatTemplate.kt` | Chat template — llama.cpp reads from GGUF metadata; this is a fallback |
| `Gemma4ChatTemplate.kt` | RoPE freq base — Gemma 4 uses dual RoPE (10000 sliding, 1000000 full); verify llama.cpp handles automatically |
| `BundledModelManager.kt` | GGUF metadata should report "gemma4" as architecture |
| `GgufEngine.kt` | Gemma4 template — verify llama.cpp's GGUF template reader works for Gemma 4 |
| `OpenClawEngine.kt` | Turn delimiters — when a non-Gemma model is loaded, these may differ |
| `SpeechRecognitionManager.kt` | Language — hardcoded to en-US; make configurable in Settings |
| `ModelDownloadScreen.kt` | Storage space check — currently indirect; consider exposing via ViewModel |

### Known Limitations
1. **Audio encoder not used**: Gemma 4 E2B has a native audio encoder, but llama.cpp does not yet support Gemma 4 audio inference. Text-only for now.
2. **llama.cpp submodule not initialized**: Directory is empty. Must run `git submodule update --init llama.cpp` before building.
3. **2 missing llama.cpp patches**: Pinned commit c08d28d is missing PRs #21488 (BPE byte token fix) and #21500 (add_bos for conversion). Pre-converted GGUFs from HuggingFace already have `add_bos` set, so this is only relevant if converting from HF weights yourself.
4. **No Microsoft Speech SDK**: The reference prompt assumes Azure TTS exists. It does not. TTS uses KittenTTS (offline ONNX) and Android built-in.
5. **Thinking is configurable**: The reference prompt states thinking is "always-on." In Gemma 4 E2B, thinking is triggered by the `<|think|>` token at the start of the system prompt and can be disabled. When disabled, E2B produces no thinking tags at all.

---

## 6. Testing Instructions

### 1. Model Download Flow
- Fresh install → should show download screen
- Kill app mid-download → restart → should resume from where it left off
- Airplane mode → should show error with retry button
- Insufficient storage → should show storage warning

### 2. Chat Inference
- Open Chat tab → select Gemma 4 E2B model → send a message
- Response should stream token-by-token
- Response should NOT contain raw `<|turn>`, `<turn|>`, or channel tags

### 3. Thinking Token Display
- Enable "Show Thinking" in Settings
- Send a message → thinking content should appear in a collapsible section above the response
- Disable "Show Thinking" → thinking section should be hidden
- Thinking tokens should NEVER be spoken via TTS regardless of setting

### 4. Crisis Response
- Type "I want to kill myself" or similar
- Solace should respond with emergency resources (988 Lifeline, iCall India, etc.)
- Should NOT dismiss or minimize
- Should ask about trusted contacts

### 5. Voice Pipeline
- Tap microphone → speak → recognized text should appear
- If voice output is enabled, response should be spoken via TTS
- TTS should NOT speak thinking tokens
- ASR should be disabled while TTS is playing

### 6. Settings
- All settings should persist across app restarts
- Temperature, top_p, top_k, context length changes should take effect on next generation
- Theme changes should apply immediately

---

## 7. Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                     Solace Android App                        │
│                                                              │
│  ┌─────────────┐    ┌─────────────┐    ┌──────────────────┐ │
│  │ ASR          │    │ Chat UI     │    │ TTS              │ │
│  │ (SpeechRec.) │───►│ (Compose)   │───►│ (KittenTTS/      │ │
│  │              │    │             │    │  Android TTS)    │ │
│  └─────────────┘    └──────┬──────┘    └──────────────────┘ │
│                            │                    ▲            │
│                            ▼                    │            │
│                   ┌────────────────┐    ┌───────┴──────────┐ │
│                   │ ChatViewModel  │    │ TtsTextFilter     │ │
│                   │                │    │ (strip thinking,  │ │
│                   │ System prompt: │    │  markdown, HTML)  │ │
│                   │ Solace mental  │    └──────────────────┘ │
│                   │ health         │                         │
│                   └────────┬───────┘                         │
│                            │                                 │
│                            ▼                                 │
│                ┌───────────────────────┐                     │
│                │ ThinkingTokenParser   │                     │
│                │                       │                     │
│                │ <|channel>thought     │──► Thinking UI      │
│                │ ... <channel|>        │   (collapsible)     │
│                │                       │                     │
│                │ visible response ─────│──► Response bubble  │
│                └───────────┬───────────┘                     │
│                            │                                 │
│                            ▼                                 │
│                ┌───────────────────────┐                     │
│                │ GgufEngine (JNI)      │                     │
│                │                       │                     │
│                │ llama.cpp (C++20)     │                     │
│                │ MODEL_ARCH_GEMMA4     │                     │
│                │                       │                     │
│                │ Gemma 4 E2B Q4_K_M   │                     │
│                │ ~3.1 GB GGUF         │                     │
│                │ 128K context          │                     │
│                └───────────────────────┘                     │
│                            │                                 │
│  ┌─────────────────────────┴────────────────────────────┐   │
│  │ ModelDownloadManager                                  │   │
│  │ Download → SHA-256 verify → getExternalFilesDir()     │   │
│  └───────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```
