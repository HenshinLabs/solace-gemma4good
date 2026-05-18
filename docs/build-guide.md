# Solace Android App — Build Guide

This guide walks you through building the Solace Android app from source. Solace is an on-device LLM chat application that runs Gemma models locally via llama.cpp with multimodal (vision) support.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Clone & Submodules](#2-clone--submodules)
3. [Android SDK Setup](#3-android-sdk-setup)
4. [Local Properties](#4-local-properties)
5. [Project Structure](#5-project-structure)
6. [Build Commands](#6-build-commands)
7. [Build Variants](#7-build-variants)
8. [Native Build (C++ / llama.cpp)](#8-native-build-c--llamacpp)
9. [Release Signing](#9-release-signing)
10. [APK Output & Naming](#10-apk-output--naming)
11. [Model Files](#11-model-files)
12. [Testing](#12-testing)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Prerequisites

### Required Software

| Tool | Version | Purpose |
|------|---------|---------|
| **JDK** | 17 | Java compilation (AGP requires 17) |
| **Android Studio** | Hedgehog 2023.1+ or command-line tools | IDE / build tooling |
| **Android SDK Platform** | 36 | `compileSdk` and `targetSdk` |
| **Android Build Tools** | 36.0.0 | APK packaging |
| **NDK** | Latest stable (r26+) | Native C++ compilation for llama.cpp |
| **CMake** | 3.22.1+ | Build system for native code |
| **Git** | 2.30+ | Source control & submodule management |

### Verify Installations

```bash
# Java version
java -version
# Expected: openjdk version "17.x.x"

# Android SDK (via sdkmanager or Android Studio)
sdkmanager --version

# CMake
cmake --version
# Expected: cmake version 3.22.1 or higher

# Git
git --version
```

---

## 2. Clone & Submodules

Solace uses Git submodules for llama.cpp, Ollama client, and OpenCode integration.

```bash
# Clone the repository
git clone https://github.com/your-org/Master_LLM_app.git
cd Master_LLM_app

# Initialize and update all submodules
git submodule update --init --recursive
```

**Expected output:**
```
Submodule 'llama.cpp' (https://github.com/ggml-org/llama.cpp) registered for path 'llama.cpp'
Submodule 'ollama' (https://github.com/ollama/ollama) registered for path 'ollama'
Submodule 'opencode' (https://github.com/anomalyco/opencode) registered for path 'opencode'
Cloning into 'llama.cpp'...
...
```

Verify submodules are populated:
```bash
ls llama.cpp/CMakeLists.txt  # Should exist
ls ollama/                     # Should contain Go source
ls opencode/                   # Should contain source
```

> **Note:** If submodule directories are empty, native builds will fail. Run `git submodule update --init --recursive` again.

---

## 3. Android SDK Setup

Install the required SDK components using `sdkmanager` (comes with Android Studio or command-line tools).

```bash
# Install SDK platform 36 and build tools
sdkmanager "platforms;android-36" "build-tools;36.0.0"

# Install NDK (required for llama.cpp native compilation)
sdkmanager "ndk;27.0.12077973"

# Install CMake
sdkmanager "cmake;3.22.1"
```

Verify installation:
```bash
sdkmanager --list_installed | grep -E "platforms;android-36|build-tools;36|ndk|cmake"
```

### Environment Variables

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK path:

```bash
# Linux/macOS — add to ~/.bashrc or ~/.zshrc
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

# Verify
echo $ANDROID_HOME
```

---

## 4. Local Properties

Create `local.properties` in the project root with your SDK path:

```properties
sdk.dir=/home/your-username/Android/Sdk
```

**Linux example:**
```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

**macOS example:**
```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

> **Note:** `local.properties` is git-ignored. Each developer must create their own.

---

## 5. Project Structure

```
Master_LLM_app/
├── app/                          # Main application module
├── core-data/                    # Data layer (Room, DataStore)
├── core-domain/                  # Domain models & use cases
├── core-network/                 # Retrofit/OkHttp networking
├── core-ollama/                  # Ollama API client
├── core-ui/                      # Shared Compose UI components
├── feature-auth/                 # Authentication
├── feature-chat/                 # Chat interface
├── feature-image-gen/            # Image generation
├── feature-marketplace/          # Model marketplace
├── feature-model-manager/        # Model download/management
├── feature-performance/          # Performance monitoring
├── feature-roleplay/             # Roleplay mode
├── feature-settings/             # App settings
├── runtime-gguf/                 # GGUF model runtime (llama.cpp JNI)
├── runtime-safetensors/          # SafeTensors runtime
├── runtime-imagegen/             # Image generation runtime
├── llama.cpp/                    # Git submodule — llama.cpp
├── ollama/                       # Git submodule — Ollama
├── opencode/                     # Git submodule — OpenCode
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Module includes
└── gradle/libs.versions.toml     # Version catalog
```

### Key Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1.0, Java 17 |
| UI | Jetpack Compose (BOM 2024.12.01) |
| DI | Hilt 2.53.1 |
| Database | Room 2.6.1 |
| Annotation Processing | KSP 2.1.0-1.0.29 |
| Native | C++ 20 via CMake, llama.cpp |

---

## 6. Build Commands

### Debug APK

Builds an unoptimized, debug-signed APK. Use this for development.

```bash
./gradlew assembleDebug
```

**Expected output:**
```
> Task :runtime-gguf:externalNativeBuildDebug
  [armeabi-v7a] Shared library : libllama_android.so
  [arm64-v8a] Shared library : libllama_android.so
  [arm64-v8a] Shared library : libllama_android_v8_2_fp16.so
  [arm64-v8a] Shared library : libllama_android_v8_2_fp16_dotprod.so
  ...

BUILD SUCCESSFUL in 4m 32s
```

**APK location:** `app/build/outputs/apk/debug/Solace-v2.0.5-debug.apk`

### Release APK

Builds an optimized, R8-minified, signed APK.

```bash
./gradlew assembleRelease
```

**APK location:** `app/build/outputs/apk/release/Solace-v2.0.5-release.apk`

> If no release keystore is configured, the release APK is signed with the debug key (see [Release Signing](#9-release-signing)).

### Clean Build

Removes all build artifacts and rebuilds from scratch. Use when you encounter stale build issues.

```bash
./gradlew clean assembleDebug
```

### Install on Device

```bash
# Debug
./gradlew installDebug

# Release
./gradlew installRelease
```

---

## 7. Build Variants

| Variant | Minification | Signing | Use Case |
|---------|-------------|---------|----------|
| **debug** | None | Debug key (auto) | Development & testing |
| **release** | R8 enabled | Release key (or debug fallback) | Distribution / production |

### SDK Version Summary

| Property | Value |
|----------|-------|
| `compileSdk` | 36 |
| `minSdk` | 31 (Android 12) |
| `targetSdk` | 36 |
| `versionCode` | 33 |
| `versionName` | 2.0.5 |

---

## 8. Native Build (C++ / llama.cpp)

The native build compiles llama.cpp into shared libraries for ARM architectures.

### CMake Configuration

The CMakeLists.txt is located at `runtime-gguf/src/main/cpp/CMakeLists.txt`. It:

1. Compiles the llama.cpp submodule with `mtmd` (multimodal) support
2. Detects Vulkan GPU backend availability automatically
3. Builds multiple ARM64-optimized variants with different CPU feature flags

### Native Library Variants (arm64-v8a)

| Library | CPU Flags | Purpose |
|---------|-----------|---------|
| `libllama_android.so` | Universal (baseline) | Fallback for any ARM64 device |
| `libllama_android_v8_2_fp16.so` | `-march=armv8.2-a+fp16` | FP16-capable devices |
| `libllama_android_v8_2_fp16_dotprod.so` | `-march=armv8.2-a+fp16+dotprod` | + dot product instructions |
| `libllama_android_v8_4_fp16_dotprod.so` | `-march=armv8.4-a+fp16+dotprod` | Newer ARM cores |
| `libllama_android_v8_4_fp16_dotprod_sve.so` | `-march=armv8.4-a+fp16+dotprod+sve` | + SVE (Scalable Vector Extension) |
| `libllama_android_v8_4_fp16_dotprod_i8mm.so` | `-march=armv8.4-a+fp16+dotprod+i8mm` | + Int8 matrix multiply |
| `libllama_android_v8_4_fp16_dotprod_i8mm_sve.so` | `-march=armv8.4-a+fp16+dotprod+i8mm+sve` | Full feature set |

At runtime, the app detects CPU features and loads the best matching variant.

### Vulkan GPU Backend

The build system auto-detects Vulkan headers in the NDK. If found, `GGML_VULKAN` is enabled and the native library links against `libvulkan.so`. This allows GPU-accelerated inference on devices with Adreno or Mali GPUs.

If Vulkan headers are not found, you'll see this warning (build still succeeds):
```
-- WARNING: Vulkan-Hpp header not found; building runtime-gguf without Vulkan GPU backend
```

### Additional Native Components

- **GGUF Reader** (`libggufreader.so`): Reads GGUF file headers for model metadata
- **KittenTTS**: Uses ONNX Runtime (Java/Kotlin, not native) — bundled in `assets/kittentts/`

---

## 9. Release Signing

### Option A: Environment Variables (CI/CD)

Set these environment variables before building:

```bash
export MASTER_LLM_RELEASE_STORE_FILE=/path/to/solace-release.jks
export MASTER_LLM_RELEASE_STORE_PASSWORD=your_store_password
export MASTER_LLM_RELEASE_KEY_ALIAS=solace
export MASTER_LLM_RELEASE_KEY_PASSWORD=your_key_password

./gradlew assembleRelease
```

### Option B: Gradle Properties

Add to `~/.gradle/gradle.properties` (user-level, not committed):

```properties
MASTER_LLM_RELEASE_STORE_FILE=/path/to/solace-release.jks
MASTER_LLM_RELEASE_STORE_PASSWORD=your_store_password
MASTER_LLM_RELEASE_KEY_ALIAS=solace
MASTER_LLM_RELEASE_KEY_PASSWORD=your_key_password
```

### Generating a Keystore

If you don't have a release keystore, generate one:

```bash
keytool -genkey -v \
  -keystore solace-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias solace
```

Follow the interactive prompts to set passwords and certificate details.

### Fallback Behavior

If no release signing config is provided, the build automatically falls back to the debug signing key. This means release builds will always succeed — they just won't be signed with a production key.

See `keystore.properties.template` for a reference template.

---

## 10. APK Output & Naming

APKs are automatically renamed following this pattern:

```
Solace-v{versionName}-{buildType}.apk
```

**Examples:**
- `Solace-v2.0.5-debug.apk`
- `Solace-v2.0.5-release.apk`

**Output directories:**
- Debug: `app/build/outputs/apk/debug/`
- Release: `app/build/outputs/apk/release/`

---

## 11. Model Files

Solace requires large model files that are **not** included in the repository. They are downloaded at runtime on first use.

### Main LLM Model

| Property | Value |
|----------|-------|
| Model | Gemma 4 E2B Q4_K_M (GGUF) |
| Size | ~3.1 GB |
| Source | HuggingFace |
| URL | `https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf` |
| SHA256 | `9378bc471710229ef165709b62e34bfb62231420ddaf6d729e727305b5b8672d` |

### Multimodal Projector

| Property | Value |
|----------|-------|
| Model | mmproj BF16 |
| Size | ~941 MB |
| Source | Downloaded after main model completes |

### Vosk Speech Model

| Property | Value |
|----------|-------|
| Model | Vosk small English |
| Size | ~40 MB |
| Source | Downloaded on first voice feature use |

### KittenTTS (Bundled)

| Property | Value |
|----------|-------|
| Model | KittenTTS ONNX |
| Size | ~23 MB |
| Location | `app/src/main/assets/kittentts/` |
| Files | `model.onnx` + `voices.npz` |
| Source | Committed to repository |

> **Note:** The KittenTTS assets are verified at build time for release builds via a custom Gradle task (`verifyTurnipReleaseAssets`). Missing assets will fail the release build.

---

## 12. Testing

### Unit Tests

```bash
./gradlew testDebugUnitTest
```

Tests use JUnit 5, MockK, Turbine, and kotlinx-coroutines-test.

### Specific Module Tests

```bash
./gradlew :core-domain:testDebugUnitTest
./gradlew :runtime-gguf:testDebugUnitTest
```

### Lint

```bash
./gradlew lintDebug
```

Output: `app/build/reports/lint-results-debug.html`

---

## 13. Troubleshooting

### Build fails with "NDK not configured"

**Error:** `NDK is not installed` or `No version of NDK matched the requested`

**Fix:**
```bash
sdkmanager "ndk;27.0.12077973"
```

Or set the NDK path in `local.properties`:
```properties
ndk.dir=/home/your-username/Android/Sdk/ndk/27.0.12077973
```

### Submodule directories are empty

**Error:** `CMake Error at CMakeLists.txt:43 ... llama.cpp/CMakeLists.txt not found`

**Fix:**
```bash
git submodule update --init --recursive
```

### "sdk.dir is not set"

**Error:** `SDK location not found. Define location with sdk.dir in the local.properties file`

**Fix:** Create `local.properties` in the project root (see [Local Properties](#4-local-properties)).

### CMake version too old

**Error:** `CMake 3.22.1 or higher is required`

**Fix:**
```bash
sdkmanager "cmake;3.22.1"
```

Or install system-wide:
```bash
# Ubuntu/Debian
sudo apt install cmake

# macOS
brew install cmake
```

### Out of memory during native build

**Error:** `clang++: error: unable to execute command: Killed` or build freezes

**Fix:** Reduce parallel compilation:
```bash
./gradlew assembleDebug -Dorg.gradle.workers.max=2
```

Or increase available memory in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=512m
```

### "Vulkan-Hpp header not found" warning

This is a **warning, not an error**. The build will succeed without Vulkan GPU acceleration. The app will fall back to CPU-only inference.

To enable Vulkan, ensure the NDK is installed with Vulkan headers:
```bash
sdkmanager "ndk;27.0.12077973"
```

### Debug APK won't install on device

**Error:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

**Fix:** Uninstall the existing app first:
```bash
adb uninstall com.masterllm.app
```

### Gradle daemon OOM

**Error:** `java.lang.OutOfMemoryError: Java heap space`

**Fix:** Edit `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m
```

### R8 minification breaks release builds

**Error:** `Missing class ...` warnings during release build

**Fix:** Add keep rules to `app/proguard-rules.pro`. The existing rules cover Hilt, Retrofit, Gson, Room, JNI methods, and coroutines. If new reflection-heavy libraries are added, they may need additional rules.

---

## Quick Start (TL;DR)

```bash
# 1. Clone
git clone https://github.com/your-org/Master_LLM_app.git
cd Master_LLM_app

# 2. Submodules
git submodule update --init --recursive

# 3. SDK
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;27.0.12077973" "cmake;3.22.1"

# 4. Local properties
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 5. Build
./gradlew assembleDebug

# 6. Install
adb install app/build/outputs/apk/debug/Solace-v2.0.5-debug.apk
```
