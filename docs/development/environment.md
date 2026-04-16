# Development Environment Setup

This guide walks through setting up a complete Android development environment for Master LLM.

## Prerequisites

### Required Software

| Component | Version | Notes |
|-----------|---------|-------|
| Java JDK | 17.0.18+ | Temurin distribution recommended |
| Android SDK | 36 | Must include platforms 31-36 |
| Gradle | 8.12+ | Included via gradlew |
| Android NDK | 27.2.12479018 | Required for native code |

### Environment Variables

The project includes resolved paths in `.env.resolved`:

```bash
# From .env.resolved
ANDROID_DEV_HOME=/store/shuvam/android_app_dev/.android-dev
JAVA_HOME=/store/shuvam/android_app_dev/.android-dev/jdk-17.0.18+8
ANDROID_SDK_ROOT=/store/shuvam/android_app_dev/.android-dev/android-sdk
ANDROID_HOME=/store/shuvam/android_app_dev/.android-sdk
NDK_HOME=/store/shuvam/android_app_dev/.android-dev/android-sdk/ndk/27.2.12479018
```

## Setup Steps

### 1. Clone Repository with Submodules

```bash
git clone --recurse-submodules https://github.com/your-org/MasterLLM.git
cd MasterLLM
```

### 2. Initialize Git Submodules

```bash
git submodule update --init --recursive
```

This will pull:
- `llama.cpp/` - LLM inference library

### 3. Configure Android SDK

If using custom SDK location, update `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

### 4. Build Configuration

**Gradle Properties** (`.gradle.properties`):

```properties
org.gradle.jvmargs=-Xmx4096m
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

### 5. Create Local Configuration

Create `local.properties` if not exists:

```properties
sdk.dir=/path/to/android-sdk
```

## Build Commands

### Debug Build

```bash
./gradlew assembleDebug
```

### Release Build

```bash
./gradlew assembleRelease
```

### Clean Build

```bash
./gradlew clean assembleDebug
```

### Run Tests

```bash
# Unit tests
./gradlew test

# Android tests (device/emulator)
./gradlew connectedAndroidTest

# All tests
./gradlew testDebugUnitTest testDebugAndroidTest
```

## IDE Setup

### Android Studio

1. Open the `Master_LLM` directory as project
2. Wait for Gradle sync to complete
3. Select a run configuration (e.g., `app` → Debug)
4. Run on device/emulator

### VS Code

1. Install Android development extensions
2. Install Kotlin language support
3. Open project folder

## Device Setup

### Minimum Requirements

- Android 12 (API 31) or higher
- 6GB RAM minimum (8GB recommended)
- 10GB free storage for models

### Emulator Configuration

Recommended AVD configuration:
- **API**: 36
- **Architecture**: x86_64
- **RAM**: 4096MB
- **Heap**: 512MB

## Common Issues

### NDK Not Found

```bash
# Check NDK installation
ls $ANDROID_HOME/ndk/
```

If missing, install via SDK Manager.

### CMake Errors

Ensure CMake 3.18+ is available:
```bash
cmake --version
```

### Memory Issues

If OOM during build, increase JVM heap:
```properties
# .gradle.properties
org.gradle.jvmargs=-Xmx8192m
```

## Next Steps

- Read [Build Instructions](build.md) for detailed build options
- Read [Testing Guide](testing.md) for test execution
- Explore [Module Documentation](../architecture/modules.md)