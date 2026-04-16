# Build Instructions

Comprehensive guide for building Master LLM.

## Build Variants

### Debug

Standard debug build with debugging enabled:
```bash
./gradlew assembleDebug
```

### Release

Production build with ProGuard:
```bash
./gradlew assembleRelease
```

Requires signing configuration in `app/build.gradle.kts`.

## Module-Specific Builds

Build specific modules to save time during development:

```bash
# Build only core modules
./gradlew :core-domain:assemble :core-data:assemble :core-network:assemble

# Build specific feature
./gradlew :feature-chat:assembleDebug

# Build runtime
./gradlew :runtime-gguf:assembleDebug
```

## Native Code Build

### GGUF Runtime

The GGUF runtime requires native compilation:

```bash
# Build native libraries for all ABIs
./gradlew :runtime-gguf:externalNativeBuildDebug

# Build for specific ABI only
./gradlew :runtime-gguf:externalNativeBuildDebug -Pnative.abi=arm64-v8a
```

### CMake Options

Key CMake options for llama.cpp:
- `LLAMA_BUILD_LIBRARY=ON` - Build as shared library
- `GGML_NATIVE=ON` - Use native instructions

## Build Configuration

### Kotlin Options

Configure in `build.gradle.kts`:

```kotlin
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xopt-in=kotlin.RequiresOptIn")
    }
}
```

### Compose Options

```kotlin
composeOptions {
    kotlinCompilerExtensionVersion = "1.5.15"
}
```

### ProGuard/R8

Rules located in `app/proguard-rules.pro`.

Key rules:
- Keep native methods
- Keep Hilt classes
- Keep Retrofit interfaces

## Build Cache

### Gradle Cache

```bash
# Clear all caches
./gradlew clean

# Clear specific cache
rm -rf ~/.gradle/caches/transforms-*
```

### Configuration Cache

Configuration cache is enabled by default. To disable:

```properties
# gradle.properties
org.gradle.configuration-cache=false
```

## Troubleshooting

### Build Failures

1. **Clean and rebuild**:
   ```bash
   ./gradlew clean assembleDebug
   ```

2. **Invalidate caches**:
   ```bash
   ./gradlew --stop
   rm -rf .gradle/configuration-cache
   ```

3. **Check Gradle daemon**:
   ```bash
   ./gradlew --status
   ./gradlew --stop
   ```

### Native Build Failures

1. Verify NDK installation:
   ```bash
   echo $ANDROID_NDK_HOME
   ls $ANDROID_NDK_HOME
   ```

2. Check CMake:
   ```bash
   cmake --version
   ```

3. Build native manually:
   ```bash
   cd runtime-gguf/src/main/cpp
   mkdir build && cd build
   cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake ..
   make
   ```

### Dependency Resolution

1. **Update dependencies**:
   ```bash
   ./gradlew dependencyUpdates
   ```

2. **Force refresh**:
   ```bash
   ./gradlew --refresh-dependencies assembleDebug
   ```

## CI/CD

### GitHub Actions

Example workflow (`.github/workflows/build.yml`):

```yaml
name: Build
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - name: Build
        run: ./gradlew assembleDebug
```

### Local CI

```bash
# Run full verification
./gradlew verify

# Run with checks
./gradlew check
```

## Performance Tips

1. **Parallel builds**: Already enabled by default
2. **Incremental**: Use `--incremental` flag
3. **Daemon**: Keep Gradle daemon running
4. **Configuration cache**: Enabled for faster builds