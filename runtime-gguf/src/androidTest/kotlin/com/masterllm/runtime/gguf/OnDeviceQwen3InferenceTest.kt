package com.masterllm.runtime.gguf

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterllm.core.domain.model.InferenceParams
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceQwen3InferenceTest {

    @Test
    fun qwen3_0_6b_generatesNonEmptyResponse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("model_path") ?: DEFAULT_MODEL_PATH
        val prompt = args.getString("prompt") ?: DEFAULT_PROMPT
        val decodeTimeoutSeconds = args.getString("decode_timeout_seconds")
            ?.toLongOrNull()
            ?.coerceAtLeast(1L)
            ?: DEFAULT_DECODE_TIMEOUT_SECONDS

        val modelFile = File(modelPath)
        assertTrue("Model file not found: $modelPath", modelFile.exists())
        assertTrue("Model file is empty: $modelPath", modelFile.length() > 0L)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = GgufEngine(context)

        try {
            engine.load(
                modelPath = modelPath,
                params = InferenceParams(
                    minP = 0.05f,
                    temperature = 0.7f,
                    topP = 0.9f,
                    topK = 40,
                    maxTokens = 128,
                    contextSize = 2048,
                    numThreads = 2,
                    useMmap = true,
                    useMlock = false,
                ),
            )
            assertTrue("Engine did not report loaded state", engine.isModelLoaded())

            val response = runBlockingWithTimeout(
                timeoutSeconds = decodeTimeoutSeconds,
                taskName = "decode",
            ) {
                engine.getResponse(prompt).trim()
            }

            assertTrue("Response should not be blank", response.isNotBlank())
            assertTrue("Response too short: '$response'", response.length >= 8)
        } finally {
            engine.close()
        }
    }

    @Test
    fun qwen3_0_6b_benchmarkCompletes() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("model_path") ?: DEFAULT_MODEL_PATH
        val benchTimeoutSeconds = args.getString("bench_timeout_seconds")
            ?.toLongOrNull()
            ?.coerceAtLeast(1L)
            ?: DEFAULT_BENCH_TIMEOUT_SECONDS

        val modelFile = File(modelPath)
        assertTrue("Model file not found: $modelPath", modelFile.exists())
        assertTrue("Model file is empty: $modelPath", modelFile.length() > 0L)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = GgufEngine(context)

        try {
            engine.load(
                modelPath = modelPath,
                params = InferenceParams(
                    minP = 0.05f,
                    temperature = 0.7f,
                    topP = 0.9f,
                    topK = 40,
                    maxTokens = 32,
                    contextSize = 1024,
                    numThreads = 2,
                    useMmap = true,
                    useMlock = false,
                ),
            )
            assertTrue("Engine did not report loaded state", engine.isModelLoaded())

            val bench = runBlockingWithTimeout(
                timeoutSeconds = benchTimeoutSeconds,
                taskName = "benchmark",
            ) {
                engine.benchModel(pp = 16, tg = 8, pl = 0, nr = 1).trim()
            }

            assertTrue("Benchmark output should not be blank", bench.isNotBlank())
            assertTrue(
                "Benchmark output missing numeric metrics: '$bench'",
                bench.any(Char::isDigit),
            )
        } finally {
            engine.close()
        }
    }

    private fun runBlockingWithTimeout(
        timeoutSeconds: Long,
        taskName: String,
        block: () -> String,
    ): String {
        val result = AtomicReference<String?>(null)
        val error = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)

        val worker = Thread(
            {
                try {
                    result.set(block())
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    done.countDown()
                }
            },
            "gguf-$taskName-worker",
        ).apply {
            isDaemon = true
        }

        worker.start()
        val completed = done.await(timeoutSeconds, TimeUnit.SECONDS)
        assertTrue("$taskName timed out after ${timeoutSeconds}s", completed)

        val thrown = error.get()
        if (thrown != null) {
            throw AssertionError("$taskName failed", thrown)
        }

        return result.get().orEmpty()
    }

    private companion object {
        const val DEFAULT_MODEL_PATH = "/data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf"
        const val DEFAULT_PROMPT = "Reply in one short sentence: inference works."
        const val DEFAULT_DECODE_TIMEOUT_SECONDS = 120L
        const val DEFAULT_BENCH_TIMEOUT_SECONDS = 90L
    }
}
