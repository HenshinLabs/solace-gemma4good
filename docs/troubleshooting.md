# Solace Android — Troubleshooting Guide

Known issues, root causes, applied fixes, and remaining work as of **v2.0.5c**.

---

## Table of Contents

1. [Web Search Not Working](#1-web-search-not-working)
2. [Image Data Not Passed to Model (Multimodal Vision)](#2-image-data-not-passed-to-model-multimodal-vision)
3. [ASR (Voice) — "Download Chain Failed"](#3-asr-voice--download-chain-failed)
4. [TTS Not Working](#4-tts-not-working)
5. [Session UI Colors White](#5-session-ui-colors-white)
6. [Roleplay Sessions Requiring Separate Model Download](#6-roleplay-sessions-requiring-separate-model-download)
7. [Build Failures](#7-build-failures)
8. [Flickering Download Screen](#8-flickering-download-screen)
9. [APK Too Large for Asset Bundling](#9-apk-too-large-for-asset-bundling)
10. [Remaining / Open Issues](#10-remaining--open-issues)

---

## 1. Web Search Not Working

**Status:** Fixed in v2.0.5

### Symptom

The model outputs text but never performs web searches. Prompts like "search for X" or "look up Y" produce hallucinated results instead of live data.

### Root Cause

`OpenClawEngine` and `ToolRegistry` lived in the `app/` module. `ChatViewModel` (in `feature-chat/`) could not access them due to Gradle module boundary restrictions. The tool-calling pipeline was therefore unreachable from the chat flow.

### Fix Applied

- Moved `OpenClawEngine` and `ToolRegistry` into the `runtime-gguf` module, which `feature-chat/` already depends on.
- `ToolRegistry.web_search(query)` performs DuckDuckGo HTML scraping (no API key required).
- The model triggers tool calls by emitting XML-like tags in its output (e.g., `<tool>web_search</tool><query>...</query>`). `OpenClawEngine` parses these tags and dispatches to `ToolRegistry`.

### How It Works Now

```
User prompt
  → OpenClawEngine (parses model output for tool tags)
    → ToolRegistry.web_search(query)
      → DuckDuckGo HTML scrape
    → Result injected back into context
  → Model generates final answer with search data
```

### Verification

1. Ask the model: "What is the current weather in Tokyo?"
2. Confirm the logcat output shows `ToolRegistry: web_search` being called.
3. The model's response should contain real, non-hallucinated data.

---

## 2. Image Data Not Passed to Model (Multimodal Vision)

**Status:** Partially fixed — native pipeline wired, mmproj download added

### Symptom

Attaching an image to a chat produces no image-aware response. The model ignores the image entirely and responds as if only text was provided.

### Root Cause

- No `mmproj` (multimodal projection) file was being downloaded alongside the text model.
- `loadMmproj()` in `ChatViewModel` was never called — the function existed but had no call site.
- Without the mmproj file, the native GGUF runtime has no vision encoder weights and silently skips image tokens.

### Fix Applied

- **ModelDownloadManager:** Added `ensureMmprojReady()` which checks for and downloads the mmproj file when a vision-capable model is selected.
- **ChatViewModel:** `findMmprojFile()` searches the model directory for any file matching the `mmproj*.gguf` pattern. `loadMmproj()` is now called immediately after the text model finishes loading.
- **mmproj source:** [huggingface.co/bjivanovich/Gemma4-E2B-Vision-GGUF](https://huggingface.co/bjivanovich/Gemma4-E2B-Vision-GGUF)

### Remaining Work

- **End-to-end testing required.** The download and load paths are wired but have not been validated with an actual image prompt on a physical device.
- The mmproj BF16 file is ~941 MB. This is a significant download and may fail on slow or metered connections. Consider providing a quantized (Q4/Q8) variant.

### Verification

1. Select a vision-capable model (e.g., Gemma4-E2B).
2. Wait for mmproj download to complete (check logcat for `ensureMmprojReady`).
3. Attach an image and ask "What is in this image?"
4. Expected: model describes image contents.

---

## 3. ASR (Voice) — "Download Chain Failed"

**Status:** Fixed in v2.0.5c

### Symptom

Tapping the microphone button shows "Download chain failed" or the download stalls indefinitely. Voice input never becomes available.

### Root Causes

1. **Wrong Vosk download URL.** The original GitHub mirror returned 404.
2. **Network reachability.** `alphacephei.com` is unreachable from some restricted networks (corporate firewalls, certain ISPs).

### Fix Applied

- Corrected URL to:
  ```
  https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
  ```
  (41 MB, confirmed working)
- Added resilience:
  - **3 retries per URL** with exponential backoff (1s, 2s, 4s).
  - **Timeouts:** 60s connect, 5 min read (previously 10s/30s).

### Flow

```
Tap mic button
  → Download vosk-model-small-en-us-0.15.zip (41 MB)
  → Extract to app internal storage
  → Initialize Vosk recognizer
  → Begin listening
```

### Workarounds for Restricted Networks

- Pre-download the zip on a desktop and sideload it to `/data/data/com.solace/files/vosk/` via `adb push`.
- Alternatively, host the zip on an internal server and update the URL in `AsrManager.kt`.

### Verification

1. Tap the mic button on a fresh install.
2. Observe download progress in logcat (`AsrManager: download`).
3. After extraction, speak a sentence and confirm transcription appears.

---

## 4. TTS Not Working

**Status:** Fixed in v2.0.5c

### Symptom

The app crashes on launch or when generating a response, with a Hilt/Dagger injection error referencing `KittenTtsEngine`. Alternatively, TTS audio never plays even though generation completes.

### Root Causes

1. **Hilt crash.** `KittenTtsEngine` had `@Singleton` and `@Inject` annotations, but was also instantiated manually inside `ChatViewModel`. Hilt tried to create a second instance via constructor injection and failed because dependencies were not provided in the module graph.
2. **Blocking coroutine.** TTS playback (`ttsEngine.speak()`) was called directly inside the generation coroutine, blocking further token output until playback finished.

### Fix Applied

- **Removed Hilt annotations** from `KittenTtsEngine` (`@Singleton`, `@Inject`). It is now a plain class instantiated manually where needed.
- **Moved TTS to a separate coroutine:**
  ```kotlin
  viewModelScope.launch {
      ttsEngine.speak(responseText)
  }
  ```
  This runs independently of the generation coroutine, so token streaming continues uninterrupted.
- **Added error handling** around TTS initialization and playback to prevent silent crashes.

### Assets

Bundled in `assets/kittentts/`:
- `kitten_tts_nano_v0_8.onnx` — the TTS model
- `voices.npz` — voice embeddings (NPZ format)

### Verification

1. Generate a response in a chat session.
2. Confirm TTS audio plays after generation completes.
3. Confirm the UI remains responsive during playback (tokens should still stream).

---

## 5. Session UI Colors White

**Status:** Fixed in v2.0.5

### Symptom

Session list items or chat cards appear with a white background, breaking the dark theme / gradient design.

### Root Cause

`ElevatedCard` (Material 3) uses a default `containerColor` that overrides the parent's gradient background. The card's opaque white fill covered the intended background.

### Fix Applied

```kotlin
ElevatedCard(
    containerColor = Color.Transparent,
    // ...
)
```

Setting `containerColor = Color.Transparent` allows the parent's gradient to show through.

### Verification

1. Navigate to the session list.
2. Confirm cards have a transparent background with the gradient visible behind them.

---

## 6. Roleplay Sessions Requiring Separate Model Download

**Status:** Fixed in v2.0.3

### Symptom

Starting a roleplay session prompts the user to download a model, even though the same model is already downloaded and working in the regular chat.

### Root Cause

`RoleplayViewModel` did not check `BundledModelManager` for an already-downloaded model. It always triggered a fresh download flow.

### Fix Applied

Added `getBundledModel()` to `RoleplayViewModel`, mirroring the logic already present in `ChatViewModel`:

```kotlin
val model = bundledModelManager.getBundledModel(modelId)
if (model != null && model.isDownloaded) {
    // Use existing model, skip download
}
```

### Verification

1. Download a model via the regular chat flow.
2. Start a roleplay session with the same model.
3. Confirm no download prompt appears.

---

## 7. Build Failures

### 7a. CMake `mtmd` Compilation — `LLAMA_INSTALL_VERSION` Not Defined

**Error:**
```
CMake Error at CMakeLists.txt:XX (message):
  LLAMA_INSTALL_VERSION is not defined
```

**Fix:**
Add to the top-level `CMakeLists.txt` (or the relevant mtmd subdirectory):
```cmake
set(LLAMA_INSTALL_VERSION "0.0.0" CACHE STRING "" FORCE)
```

---

### 7b. KSP/Hilt Resolution — `NonExistentClass`

**Error:**
```
[Dagger/MissingBinding] com.solace.runtime.NonExistentClass cannot be provided without an @Inject constructor or an @Provides-annotated method.
```

**Root Cause:** Classes like `OpenClawEngine` and `ToolRegistry` had `@Singleton`/`@Inject` annotations but were created manually (not via Hilt). KSP generated bindings that referenced a non-existent class.

**Fix:** Remove `@Singleton` and `@Inject` from any class that is instantiated manually (i.e., not managed by Hilt's dependency graph).

---

### 7c. Suspend Function in Flow

**Error:**
```
Suspension functions can only be called within coroutine body
```

**Root Cause:** A `suspend` function was called inside a `Flow { emit(...) }` builder that is not itself a suspend context (e.g., inside `map` or `transform` without `suspend`).

**Fix:** Remove the `suspend` modifier from the inner function, or restructure so the call happens in a proper coroutine scope:
```kotlin
// Before (broken)
flow.map { emit(suspendFunction()) }

// After (fixed)
flow.map { plainFunction() }
```

---

### 7d. Module Boundary Errors — Unresolved Reference

**Error:**
```
Unresolved reference: OpenClawEngine
```

**Root Cause:** `app/` module classes referenced from `feature-chat/` module without a dependency path.

**Fix:** Move shared classes to a module both depend on (e.g., `core-data/` or `runtime-gguf/`).

---

## 8. Flickering Download Screen

**Status:** Fixed in v2.0.1

### Symptom

The model download screen flickers heavily. The entire download UI block re-renders every ~350ms, causing visual instability and poor UX.

### Root Cause

`AnimatedContent` was wrapping the entire download status block. Every progress update (emitted every 350ms) triggered a full content transition animation, not just the progress bar.

### Fix Applied

Replaced `AnimatedContent` with a phase-based `when` block. The progress bar now uses `animateFloatAsState` independently:

```kotlin
// Before (flickering)
AnimatedContent(targetState = downloadPhase) { phase ->
    // entire block re-animates on every progress tick
}

// After (smooth)
when (downloadPhase) {
    DownloadPhase.Downloading -> {
        val progress by animateFloatAsState(targetValue = downloadProgress)
        LinearProgressIndicator(progress = progress)
    }
    // ...
}
```

### Verification

1. Start a model download.
2. Confirm only the progress bar animates; surrounding UI remains stable.

---

## 9. APK Too Large for Asset Bundling

**Status:** Workaround — direct download

### Symptom

Build fails with an error related to asset processing, or the APK exceeds practical distribution limits (>2 GB).

### Root Cause

AGP (Android Gradle Plugin) cannot process asset files larger than 2 GB. The GGUF model files (especially with mmproj) easily exceed this limit.

### Attempted Solutions

| Approach | Result |
|---|---|
| Split model into chunks, reassemble at runtime | Fragile; chunk management complexity |
| `jniLibs` directory bundling | Same 2 GB AGP limit applies |

### Current Workaround

Models are downloaded at runtime from a remote server. The APK ships without model files. This is the recommended approach for all GGUF models.

### Future Consideration

- Use Android App Bundle (AAB) with Play Asset Delivery for models <512 MB.
- For larger models, keep the runtime download flow.

---

## 10. Remaining / Open Issues

| # | Issue | Severity | Notes |
|---|---|---|---|
| 1 | Multimodal vision needs end-to-end testing with actual mmproj file | High | Download and load paths are wired but untested on device. The ~941 MB mmproj file is a barrier. |
| 2 | Vosk model download may fail on restricted networks | Medium | Corporate firewalls or ISPs may block `alphacephei.com`. Workaround: `adb push` the zip manually. |
| 3 | KittenTTS quality depends on `voices.npz` parsing | Medium | NPZ format parsing correctness is assumed, not validated. Audio artifacts may occur if parsing is off. |
| 4 | Context compaction not yet implemented | Medium | Long conversations will eventually exceed the model's context window. No truncation or summarization logic exists in `ChatViewModel` yet. |

---

## Quick Reference: Version History

| Version | Key Fixes |
|---|---|
| v2.0.1 | Flickering download screen |
| v2.0.3 | Roleplay model download reuse |
| v2.0.5 | Web search, session UI colors, CMake/KSP build fixes, module boundary fixes |
| v2.0.5c | ASR download chain, TTS Hilt crash, TTS blocking coroutine |

---

## Reporting New Issues

When filing a new issue, include:

1. **Device model and Android version**
2. **Solace version** (visible in Settings → About)
3. **Steps to reproduce** (minimal reproduction preferred)
4. **Logcat output** (filter by `com.solace` tag)
5. **Model being used** (name, size, quantization level)

---

*Last updated: v2.0.5c*
