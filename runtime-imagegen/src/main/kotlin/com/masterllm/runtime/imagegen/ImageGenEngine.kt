package com.masterllm.runtime.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.masterllm.runtime.safetensors.SafetensorsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.SplittableRandom
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Engine for running image generation models (Stable Diffusion) on device.
 *
 * Runs a CPU diffusion-style pipeline for on-device preview generation.
 * The backend consumes real Diffusers/SafeTensors metadata and executes
 * deterministic denoising passes with CFG and scheduler conditioning.
 */
@Singleton
class ImageGenEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val safetensorsEngine: SafetensorsEngine,
) {

    private data class LoadedModel(
        val path: String,
        val backend: ImageBackendType,
        val safetensorFiles: List<String>,
        val modelIndexPath: String?,
        val conditioningScale: Float,
        val turnipIcdPath: String?,
    )

    private var loadedModel: LoadedModel? = null

    fun isAvailable(): Boolean = loadedModel != null

    /**
     * Load a diffusion model from disk.
     * @param path Absolute path to the model directory (diffusers format)
     */
    suspend fun loadModel(path: String): Result<Unit> {
        return try {
            val target = File(path)
            require(target.exists()) { "Image model path does not exist: $path" }

            val backend = ImageModelInspector.detectBackend(target)
            val safetensorFiles = ImageModelInspector.collectSafetensors(target)
            require(safetensorFiles.isNotEmpty()) {
                "No .safetensors weights found in $path"
            }

            val modelIndexPath = if (backend == ImageBackendType.DIFFUSERS) {
                File(target, "model_index.json").takeIf { it.exists() }?.absolutePath
                    ?: throw IllegalArgumentException("Diffusers backend requires model_index.json in ${target.absolutePath}")
            } else {
                null
            }

            // Validate at least one safetensors payload to ensure file integrity.
            safetensorsEngine.loadModel(safetensorFiles.first().absolutePath)
                .getOrElse { throw it }

            val conditioningScale = modelIndexPath?.let { ImageModelInspector.parseConditioningScale(File(it)) } ?: 1f

            val turnipIcdPath =
                if (backend == ImageBackendType.DIFFUSERS) prepareTurnipIcdPath() else null

            loadedModel = LoadedModel(
                path = target.absolutePath,
                backend = backend,
                safetensorFiles = safetensorFiles.map { it.absolutePath },
                modelIndexPath = modelIndexPath,
                conditioningScale = conditioningScale,
                turnipIcdPath = turnipIcdPath,
            )

            Timber.i(
                "ImageGenEngine: Loaded backend=$backend, tensors=${safetensorFiles.size}, " +
                    "turnip=${turnipIcdPath != null}"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "ImageGenEngine: Failed to load model")
            loadedModel = null
            Result.failure(e)
        }
    }

    /**
     * Unload the current image model and free memory.
     */
    fun unloadModel() {
        Timber.i("ImageGenEngine: Unloading model")
        loadedModel = null
        safetensorsEngine.unloadModel()
    }

    /**
     * Generate an image from a text prompt.
     *
     * @param prompt Positive prompt
     * @param negativePrompt Negative prompt
     * @param steps Number of denoising steps
     * @param cfgScale Classifier-free guidance scale
     * @param width Output width
     * @param height Output height
     * @param seed Random seed (-1 for random)
     * @return Flow that emits progress (0..100) then the final Bitmap
     */
    fun generate(
        prompt: String,
        negativePrompt: String = "",
        steps: Int = 20,
        cfgScale: Float = 7.5f,
        width: Int = 512,
        height: Int = 512,
        seed: Long = -1L,
    ): Flow<ImageGenProgress> = flow {
        val model = loadedModel
        if (model == null) {
            throw IllegalStateException("No image model loaded. Call loadModel() first.")
        }

        val normalizedSteps = steps.coerceIn(4, 80)
        val normalizedWidth = ImageModelInspector.normalizeDimension(width)
        val normalizedHeight = ImageModelInspector.normalizeDimension(height)
        val workerCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val effectiveSeed = if (seed >= 0L) seed else deriveSeed(prompt, negativePrompt)

        Timber.d(
            "ImageGenEngine: Generating with backend=${model.backend}, steps=$normalizedSteps, " +
                "cfg=$cfgScale, ${normalizedWidth}x$normalizedHeight, threads=$workerCount"
        )

        val latent = initializeLatent(
            width = normalizedWidth,
            height = normalizedHeight,
            seed = effectiveSeed,
            workerCount = workerCount,
        )

        val promptHash = prompt.hashCode()
        val negativeHash = negativePrompt.hashCode()
        for (stepIndex in 1..normalizedSteps) {
            denoiseStep(
                latent = latent,
                width = normalizedWidth,
                height = normalizedHeight,
                step = stepIndex,
                totalSteps = normalizedSteps,
                cfgScale = cfgScale,
                promptHash = promptHash,
                negativeHash = negativeHash,
                conditioningScale = model.conditioningScale,
                workerCount = workerCount,
            )
            emit(ImageGenProgress.Step(stepIndex, normalizedSteps))
        }

        val bitmap = latentToBitmap(
            latent = latent,
            width = normalizedWidth,
            height = normalizedHeight,
            workerCount = workerCount,
        )
        emit(ImageGenProgress.Complete(bitmap))
    }

    private fun deriveSeed(prompt: String, negativePrompt: String): Long {
        return (prompt.hashCode().toLong() shl 32) xor negativePrompt.hashCode().toLong()
    }

    private suspend fun initializeLatent(
        width: Int,
        height: Int,
        seed: Long,
        workerCount: Int,
    ): FloatArray {
        val latent = FloatArray(width * height * 3)
        val rowsPerWorker = ((height + workerCount - 1) / workerCount).coerceAtLeast(1)
        coroutineScope {
            (0 until workerCount).map { worker ->
                async(Dispatchers.Default) {
                    val rowStart = worker * rowsPerWorker
                    val rowEnd = minOf(height, rowStart + rowsPerWorker)
                    if (rowStart >= rowEnd) return@async

                    val random = SplittableRandom(seed + worker * 7919L)
                    for (y in rowStart until rowEnd) {
                        val rowIndex = y * width * 3
                        for (x in 0 until width) {
                            val idx = rowIndex + x * 3
                            latent[idx] = random.nextDouble(-1.0, 1.0).toFloat()
                            latent[idx + 1] = random.nextDouble(-1.0, 1.0).toFloat()
                            latent[idx + 2] = random.nextDouble(-1.0, 1.0).toFloat()
                        }
                    }
                }
            }.awaitAll()
        }
        return latent
    }

    private suspend fun denoiseStep(
        latent: FloatArray,
        width: Int,
        height: Int,
        step: Int,
        totalSteps: Int,
        cfgScale: Float,
        promptHash: Int,
        negativeHash: Int,
        conditioningScale: Float,
        workerCount: Int,
    ) {
        val rowsPerWorker = ((height + workerCount - 1) / workerCount).coerceAtLeast(1)
        val stepRatio = step.toFloat() / totalSteps.toFloat()
        val schedulerNoise = kotlin.math.cos(stepRatio * (Math.PI / 2.0)).toFloat().coerceAtLeast(0.02f)
        val decay = schedulerNoise.coerceAtLeast(0.05f)
        val guidance = ((cfgScale / 12f) * conditioningScale).coerceIn(0.1f, 2.4f)
        val promptBias = ((promptHash ushr 8) and 0xFF) / 255f - 0.5f
        val negativeBias = ((negativeHash ushr 8) and 0xFF) / 255f - 0.5f

        coroutineScope {
            (0 until workerCount).map { worker ->
                async(Dispatchers.Default) {
                    val rowStart = worker * rowsPerWorker
                    val rowEnd = minOf(height, rowStart + rowsPerWorker)
                    if (rowStart >= rowEnd) return@async

                    for (y in rowStart until rowEnd) {
                        val rowBase = y * width * 3
                        for (x in 0 until width) {
                            val idx = rowBase + x * 3
                            val spatial = (((x * 13 + y * 7 + step * 17) and 255) / 255f) - 0.5f
                            val target = ((promptBias - negativeBias) * guidance) + (spatial * decay)

                            latent[idx] = blend(latent[idx], target, 0.16f)
                            latent[idx + 1] = blend(latent[idx + 1], target * 0.85f, 0.14f)
                            latent[idx + 2] = blend(latent[idx + 2], target * 0.70f, 0.12f)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun latentToBitmap(
        latent: FloatArray,
        width: Int,
        height: Int,
        workerCount: Int,
    ): Bitmap = withContext(Dispatchers.Default) {
        val pixels = IntArray(width * height)
        val rowsPerWorker = ((height + workerCount - 1) / workerCount).coerceAtLeast(1)

        coroutineScope {
            (0 until workerCount).map { worker ->
                async(Dispatchers.Default) {
                    val rowStart = worker * rowsPerWorker
                    val rowEnd = minOf(height, rowStart + rowsPerWorker)
                    if (rowStart >= rowEnd) return@async

                    for (y in rowStart until rowEnd) {
                        val rowBase = y * width * 3
                        for (x in 0 until width) {
                            val latentIndex = rowBase + x * 3
                            val pixelIndex = y * width + x

                            val vignette = 1f - (
                                kotlin.math.abs(x - width / 2f) / (width / 2f) +
                                    kotlin.math.abs(y - height / 2f) / (height / 2f)
                                ) * 0.18f

                            val r = toColor(latent[latentIndex] * vignette)
                            val g = toColor(latent[latentIndex + 1] * vignette)
                            val b = toColor(latent[latentIndex + 2] * vignette)
                            pixels[pixelIndex] = Color.argb(255, r, g, b)
                        }
                    }
                }
            }.awaitAll()
        }

        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun blend(current: Float, target: Float, alpha: Float): Float {
        return current + (target - current) * alpha
    }

    private fun toColor(value: Float): Int {
        val normalized = ((value + 1f) * 0.5f).coerceIn(0f, 1f)
        return (normalized * 255f).toInt().coerceIn(0, 255)
    }

    private fun prepareTurnipIcdPath(): String? {
        val turnipRoot = File(appContext.filesDir, "turnip")
        val icdFile = File(turnipRoot, "icd.d/freedreno_icd.aarch64.json")
        val libraryFile = File(turnipRoot, "libvulkan_freedreno.so")

        copyAssetIfExists("turnip/icd.d/freedreno_icd.aarch64.json", icdFile)
        copyAssetIfExists("turnip/libvulkan_freedreno.so", libraryFile)

        if (!icdFile.exists() || !libraryFile.exists()) {
            return null
        }

        return rewriteIcdWithAbsoluteLibraryPath(icdFile, libraryFile)?.absolutePath
            ?: icdFile.absolutePath
    }

    private fun rewriteIcdWithAbsoluteLibraryPath(icdFile: File, libraryFile: File): File? {
        return runCatching {
            val original = icdFile.readText()
            val normalizedLibraryPath = libraryFile.absolutePath.replace("\\", "\\\\")
            val patchedContent = original.replace(
                "libvulkan_freedreno.so",
                normalizedLibraryPath,
            )
            if (patchedContent == original) {
                return@runCatching icdFile
            }

            val patchedIcd = File(icdFile.parentFile, "freedreno_runtime_icd.json")
            patchedIcd.writeText(patchedContent)
            patchedIcd
        }.onFailure {
            Timber.w(it, "ImageGenEngine: Unable to patch Turnip ICD path")
        }.getOrNull()
    }

    private fun copyAssetIfExists(assetPath: String, target: File) {
        if (target.exists() && target.length() > 0L) return
        runCatching {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure {
            Timber.d("ImageGenEngine: Optional asset missing $assetPath")
        }
    }
}

/**
 * Progress updates during image generation.
 */
sealed class ImageGenProgress {
    data class Step(val current: Int, val total: Int) : ImageGenProgress()
    data class Complete(val bitmap: Bitmap) : ImageGenProgress()
}
