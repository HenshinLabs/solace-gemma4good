package com.masterllm.feature.image.gen

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.DownloadState
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.domain.model.ModelFormat
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.runtime.imagegen.ImageGenEngine
import com.masterllm.runtime.imagegen.ImageGenProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImageGenUiState(
	val availableModels: List<LlmModel> = emptyList(),
	val selectedModelId: String? = null,
	val prompt: String = "",
	val negativePrompt: String = "",
	val steps: Int = 20,
	val cfgScale: Float = 7.5f,
	val width: Int = 512,
	val height: Int = 512,
	val isGenerating: Boolean = false,
	val progress: Float = 0f,
	val generatedImage: Bitmap? = null,
	val error: String? = null,
)

sealed interface ImageGenAction {
	data class PromptChanged(val value: String) : ImageGenAction
	data class NegativePromptChanged(val value: String) : ImageGenAction
	data class StepsChanged(val value: Int) : ImageGenAction
	data class CfgScaleChanged(val value: Float) : ImageGenAction
	data class WidthChanged(val value: Int) : ImageGenAction
	data class HeightChanged(val value: Int) : ImageGenAction
	data class SelectModel(val modelId: String) : ImageGenAction
	data object Generate : ImageGenAction
	data object Stop : ImageGenAction
	data object ClearImage : ImageGenAction
	data object DismissError : ImageGenAction
}

@HiltViewModel
class ImageGenViewModel @Inject constructor(
	private val modelRepository: ModelRepository,
	private val imageGenEngine: ImageGenEngine,
) : ViewModel() {

	private val _uiState = MutableStateFlow(ImageGenUiState())
	val uiState: StateFlow<ImageGenUiState> = _uiState.asStateFlow()

	private var generationJob: Job? = null
	private var loadedModelId: String? = null

	init {
		viewModelScope.launch {
			modelRepository.getDownloadedModels().collect { models ->
				val imageModels = models.filter {
					it.downloadState == DownloadState.DOWNLOADED &&
						(it.format == ModelFormat.DIFFUSERS || it.format == ModelFormat.SAFETENSORS)
				}
				_uiState.update { state ->
					val selected = state.selectedModelId?.takeIf { id -> imageModels.any { it.id == id } }
						?: imageModels.firstOrNull()?.id
					state.copy(availableModels = imageModels, selectedModelId = selected)
				}
			}
		}
	}

	fun onAction(action: ImageGenAction) {
		when (action) {
			is ImageGenAction.PromptChanged -> _uiState.update { it.copy(prompt = action.value) }
			is ImageGenAction.NegativePromptChanged -> _uiState.update { it.copy(negativePrompt = action.value) }
			is ImageGenAction.StepsChanged -> _uiState.update { it.copy(steps = action.value.coerceIn(5, 80)) }
			is ImageGenAction.CfgScaleChanged -> _uiState.update { it.copy(cfgScale = action.value.coerceIn(1f, 20f)) }
			is ImageGenAction.WidthChanged -> _uiState.update { it.copy(width = normalizeDimension(action.value)) }
			is ImageGenAction.HeightChanged -> _uiState.update { it.copy(height = normalizeDimension(action.value)) }
			is ImageGenAction.SelectModel -> _uiState.update { it.copy(selectedModelId = action.modelId) }
			ImageGenAction.Generate -> generateImage()
			ImageGenAction.Stop -> stopGeneration()
			ImageGenAction.ClearImage -> _uiState.update { it.copy(generatedImage = null) }
			ImageGenAction.DismissError -> _uiState.update { it.copy(error = null) }
		}
	}

	private fun generateImage() {
		val state = _uiState.value
		if (state.prompt.isBlank()) {
			_uiState.update { it.copy(error = "Prompt cannot be empty") }
			return
		}

		generationJob?.cancel()
		generationJob = viewModelScope.launch {
			_uiState.update { it.copy(isGenerating = true, progress = 0f, error = null) }

			try {
				val model = resolveSelectedModel()
					?: throw IllegalStateException("Download an image model in Marketplace before generating images.")

				ensureImageEngineLoaded(model)

				imageGenEngine.generate(
					prompt = state.prompt,
					negativePrompt = state.negativePrompt,
					steps = state.steps,
					cfgScale = state.cfgScale,
					width = state.width,
					height = state.height,
				).collect { progress ->
					when (progress) {
						is ImageGenProgress.Step -> {
							val normalized = progress.current.toFloat() / progress.total.toFloat()
							_uiState.update { it.copy(progress = normalized.coerceIn(0f, 1f)) }
						}

						is ImageGenProgress.Complete -> {
							_uiState.update {
								it.copy(generatedImage = progress.bitmap, progress = 1f)
							}
						}
					}
				}
			} catch (e: Exception) {
				_uiState.update {
					it.copy(error = "Image generation failed: ${e.message ?: "unknown error"}")
				}
			} finally {
				_uiState.update { it.copy(isGenerating = false) }
			}
		}
	}

	private fun stopGeneration() {
		generationJob?.cancel()
		generationJob = null
		_uiState.update { it.copy(isGenerating = false) }
	}

	private suspend fun ensureImageEngineLoaded(model: LlmModel) {
		if (loadedModelId == model.id && imageGenEngine.isAvailable()) return

		val modelPath = model.localPath ?: "/data/models/${model.fileName}"
		imageGenEngine.loadModel(modelPath).getOrElse { throw it }
		loadedModelId = model.id
	}

	private suspend fun resolveSelectedModel(): LlmModel? {
		val id = _uiState.value.selectedModelId
			?: _uiState.value.availableModels.firstOrNull()?.id
			?: return null
		return modelRepository.getModelById(id) ?: _uiState.value.availableModels.firstOrNull { it.id == id }
	}

	private fun normalizeDimension(value: Int): Int {
		// Stable Diffusion dimensions are typically multiples of 64.
		val clamped = value.coerceIn(256, 1024)
		return (clamped / 64) * 64
	}
}
