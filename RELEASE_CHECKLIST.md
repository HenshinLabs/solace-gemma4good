# Release Build Checklist — Solace

## Pre-Build
- [ ] Model SHA-256 hash updated in `ModelDownloadManager.MODEL_SHA256` and verified against HF download
- [ ] `MODEL_DOWNLOAD_URL` in `app/build.gradle.kts` points to production CDN (not dev/staging)
- [ ] `versionCode` incremented in `app/build.gradle.kts`
- [ ] `versionName` updated in `app/build.gradle.kts`
- [ ] Signing keystore placed and `MASTER_LLM_RELEASE_STORE_FILE` etc. set (gradle properties or env vars)
- [ ] All API keys / secrets stored in gradle properties or BuildConfig (not in source code)
- [ ] ProGuard rules tested against a release build (manual test)
- [ ] Crash reporting SDK configured (if applicable)
- [ ] llama.cpp submodule initialized: `git submodule update --init llama.cpp`

## Android Permissions Audit
- [ ] Only `INTERNET`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` declared
- [ ] All dangerous permissions have proper runtime request flows with rationale UI
- [ ] `RECORD_AUDIO` permission declared in manifest (required for ASR)

## Model & Inference
- [ ] Gemma 4 E2B Q4_K_M GGUF download URL is accessible and returns the correct file
- [ ] SHA-256 hash matches the downloaded file
- [ ] Model loads successfully on target device
- [ ] Chat template produces correct `<|turn>`/`<turn|>` format
- [ ] Thinking channel tokens (`<|channel>thought`/`<channel|>`) are properly parsed
- [ ] System prompt (Solace) is loaded from `res/raw/system_prompt.txt`

## Testing
- [ ] Cold start on device (Android 12+) — model download flow works end-to-end
- [ ] Download resume works (kill app mid-download, restart)
- [ ] Voice input (ASR) → inference → TTS full pipeline works
- [ ] Thinking tokens display correctly in collapsible UI and are filtered from TTS
- [ ] Crisis keywords trigger emergency resource display in system prompt response
- [ ] Settings changes (temperature, voice toggle, show thinking, context length) are persisted
- [ ] App survives process death and Activity recreation (rotation, background)
- [ ] Memory: no OutOfMemoryError after 30 minutes of inference on 4GB RAM device
- [ ] Storage: insufficient storage error shown gracefully when < 4GB available

## Post-Build
- [ ] APK / AAB signed with release keystore
- [ ] APK analysed with Android Studio APK Analyzer
- [ ] APK size within acceptable range (base APK < 50 MB; model downloaded separately)
- [ ] Release notes written
- [ ] App name shows as "Solace" (not "Master LLM")
- [ ] Launcher icon shows Solace logo (hand + heart)
