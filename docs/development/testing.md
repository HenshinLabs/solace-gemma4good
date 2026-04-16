# Testing Guide

Comprehensive testing instructions for Master LLM.

## Testing Stack

| Category | Tool | Version |
|----------|------|---------|
| Unit Testing | JUnit 5 | 5.11.3 |
| Mocking | MockK | 1.13.13 |
| Coroutines Testing | Turbine | 1.2.0 |
| Android Testing | AndroidX Test | 1.6.x |
| UI Testing | Compose UI Test | Part of BOM |
| Assertion | Google Truth | 1.4.4 |

## Test Structure

```
src/
├── main/              # Production code
├── test/              # Unit tests (JVM)
└── androidTest/       # Instrumented tests (Device)
```

## Running Tests

### Unit Tests (JVM)

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run specific module
./gradlew :core-domain:testDebugUnitTest

# Run specific test class
./gradlew :core-domain:testDebugUnitTest --tests "*LlmModelTest*"
```

### Instrumented Tests (Device)

```bash
# Run all instrumented tests
./gradlew connectedAndroidTestDebug

# Run specific module
./gradlew :runtime-gguf:connectedAndroidTestDebug

# Run specific test
./gradlew :runtime-gguf:connectedAndroidTestDebug --tests "*Qwen3InferenceTest*"
```

### All Tests

```bash
./gradlew test
```

## Writing Tests

### Unit Test Example

```kotlin
// core-domain/src/test/kotlin/com/masterllm/core/domain/model/TextRuntimeModelResolverTest.kt

class TextRuntimeModelResolverTest {

    @Test
    fun `should return empty list when no models installed`() {
        // Given
        val resolver = TextRuntimeModelResolver(emptyList())

        // When
        val result = resolver.resolveAvailable()

        // Then
        assertThat(result).isEmpty()
    }

    @Test
    fun `should return valid GGUF files only`() {
        // Given
        val files = listOf(
            createMockFile("model.gguf", valid = true),
            createMockFile("model.bin", valid = false)
        )
        val resolver = TextRuntimeModelResolver(files)

        // When
        val result = resolver.resolveAvailable()

        // Then
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("model.gguf")
    }
}
```

### Coroutines Test Example

```kotlin
// Use Turbine for Flow testing
@Test
fun `should emit tokens as they are generated`() = runTest {
    // Given
    val engine = mockk<GgufEngine>()
    val tokens = listOf("Hello", " world", "!")
    every { engine.generate(any()) } returns flowOf(*tokens.toTypedArray())

    // When
    val result = engine.generate(MockContext())

    // Then
    result.test {
        assertThat(awaitItem()).isEqualTo("Hello")
        assertThat(awaitItem()).isEqualTo(" world")
        assertThat(awaitItem()).isEqualTo("!")
        awaitComplete()
    }
}
```

### MockK Example

```kotlin
@Test
fun `should delegate to repository on model fetch`() {
    // Given
    val repository = mockk<ModelRepository>()
    val expectedModels = listOf(LlmModel("test", "path"))
    coEvery { repository.getModels() } returns expectedModels

    // When
    val useCase = GetModelsUseCase(repository)
    val result = useCase()

    // Then
    coVerify { repository.getModels() }
    assertThat(result).isEqualTo(expectedModels)
}
```

## Test Modules

### testing-shared

Shared test utilities and base classes:

```kotlin
// Base test classes
abstract class BaseUnitTest

// Shared mocks
object TestFixtures {
    fun createLlmModel(name: String = "test") = LlmModel(...)
}
```

### testing-fixtures

Test data factories and builders:

```kotlin
// Factory for test models
object ModelFactory {
    fun ggufModel(size: Long = 1_000_000) = GgufModelFixture.build()
}

// Builder pattern for complex objects
class ConversationBuilder {
    fun withMessages(count: Int): ConversationBuilder
    fun withCharacter(id: String): ConversationBuilder
    fun build(): Conversation
}
```

### testing-robot

Robot pattern implementation for UI tests:

```kotlin
class ChatRobot {
    fun composeMessage(text: String): ChatRobot
    fun sendMessage(): ChatRobot
    fun assertMessageVisible(text: String): ChatRobot
}
```

## CI Integration

### GitHub Actions

```yaml
- name: Run Tests
  run: |
    ./gradlew testDebugUnitTest \
              connectedAndroidTestDebug \
              --stacktrace
```

### Test Reports

Test reports are generated in:
- Unit: `*/build/reports/tests/testDebugUnitTest/`
- Instrumented: `*/build/reports/androidTests/connected/`

View HTML reports:
```bash
# Open unit test report
open app/build/reports/tests/testDebugUnitTest/index.html
```

## Best Practices

1. **Test naming**: Use descriptive names with `should` pattern
2. **Arrange-Act-Assert**: Clear AAA structure
3. **Single assertion**: One assertion per test when possible
4. **Mock external dependencies**: Network, database, native code
5. **Use Turbine**: For Flow testing
6. **Use MockK**: For Kotlin mocking
7. **Test boundaries**: Focus on module interfaces