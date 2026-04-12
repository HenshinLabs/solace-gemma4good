package com.masterllm.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.Environment
import com.masterllm.core.domain.model.ImageFrequency
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.runtime.gguf.GgufEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
	val hfUsername: String = "",
	val autoCompactionThreshold: Int = 80,
	val defaultThreadCount: Int = 4,
	val theme: String = "system",
	val defaultImageFrequency: ImageFrequency = ImageFrequency.EVERY_RESPONSE,
	val characterConsistencyEnabled: Boolean = true,
	val gpuAccelerationEnabled: Boolean = false,
	val modelStoragePath: String = "",
	val defaultModelStoragePath: String = "",
	val effectiveModelStoragePath: String = "",
	val usingDefaultStoragePath: Boolean = true,
	val gpuDriverStatus: String = "Detecting GPU driver...",
	val gpuDriverDetails: String = "",
	val gpuValidationChecks: List<String> = emptyList(),
	val error: String? = null,
)

sealed interface SettingsAction {
	data class AutoCompactionChanged(val percent: Int) : SettingsAction
	data class ThreadCountChanged(val count: Int) : SettingsAction
	data class ThemeChanged(val theme: String) : SettingsAction
	data class ImageFrequencyChanged(val frequency: ImageFrequency) : SettingsAction
	data class CharacterConsistencyChanged(val enabled: Boolean) : SettingsAction
	data class GpuAccelerationChanged(val enabled: Boolean) : SettingsAction
	data class ModelStoragePathChanged(val path: String) : SettingsAction
	data object SaveModelStoragePath : SettingsAction
	data object DismissError : SettingsAction
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
	private val settingsRepository: SettingsRepository,
	private val ggufEngine: GgufEngine,
	@ApplicationContext private val appContext: Context,
) : ViewModel() {

	private val defaultStoragePath: String by lazy {
		resolveDefaultStoragePath().absolutePath
	}

	private val _uiState = MutableStateFlow(SettingsUiState())
	val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			settingsRepository.getHfUsername().collect { username ->
				_uiState.update { it.copy(hfUsername = username) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getAutoCompactionThreshold().collect { value ->
				_uiState.update { it.copy(autoCompactionThreshold = value) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getDefaultThreadCount().collect { value ->
				_uiState.update { it.copy(defaultThreadCount = value) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getTheme().collect { value ->
				_uiState.update { it.copy(theme = value) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getDefaultImageFrequency().collect { value ->
				_uiState.update { it.copy(defaultImageFrequency = value) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getCharacterConsistencyEnabled().collect { value ->
				_uiState.update { it.copy(characterConsistencyEnabled = value) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getGpuAccelerationEnabled().collect { value ->
				_uiState.update { it.copy(gpuAccelerationEnabled = value) }
			}
		}
		viewModelScope.launch {
			settingsRepository.getModelStoragePath().collect { value ->
				_uiState.update {
					it.copy(
						modelStoragePath = value,
						defaultModelStoragePath = defaultStoragePath,
						effectiveModelStoragePath = resolveEffectiveStoragePath(value),
						usingDefaultStoragePath = value.isBlank(),
					)
				}
			}
		}

		_uiState.update {
			it.copy(
				defaultModelStoragePath = defaultStoragePath,
				effectiveModelStoragePath = defaultStoragePath,
				usingDefaultStoragePath = true,
			)
		}
		refreshGpuDriverStatus()
	}

	fun onAction(action: SettingsAction) {
		when (action) {
			is SettingsAction.AutoCompactionChanged -> {
				val clamped = action.percent.coerceIn(50, 95)
				_uiState.update { it.copy(autoCompactionThreshold = clamped) }
				viewModelScope.launch { settingsRepository.setAutoCompactionThreshold(clamped) }
			}

			is SettingsAction.ThreadCountChanged -> {
				val clamped = action.count.coerceIn(1, 16)
				_uiState.update { it.copy(defaultThreadCount = clamped) }
				viewModelScope.launch { settingsRepository.setDefaultThreadCount(clamped) }
			}

			is SettingsAction.ThemeChanged -> {
				_uiState.update { it.copy(theme = action.theme) }
				viewModelScope.launch { settingsRepository.setTheme(action.theme) }
			}

			is SettingsAction.ImageFrequencyChanged -> {
				_uiState.update { it.copy(defaultImageFrequency = action.frequency) }
				viewModelScope.launch { settingsRepository.setDefaultImageFrequency(action.frequency) }
			}

			is SettingsAction.CharacterConsistencyChanged -> {
				_uiState.update { it.copy(characterConsistencyEnabled = action.enabled) }
				viewModelScope.launch { settingsRepository.setCharacterConsistencyEnabled(action.enabled) }
			}

			is SettingsAction.GpuAccelerationChanged -> {
				_uiState.update { it.copy(gpuAccelerationEnabled = action.enabled) }
				viewModelScope.launch {
					settingsRepository.setGpuAccelerationEnabled(action.enabled)
					refreshGpuDriverStatus()
				}
			}

			is SettingsAction.ModelStoragePathChanged -> {
				_uiState.update {
					it.copy(
						modelStoragePath = action.path,
						effectiveModelStoragePath = resolveEffectiveStoragePath(action.path),
						usingDefaultStoragePath = action.path.isBlank(),
					)
				}
			}

			SettingsAction.SaveModelStoragePath -> {
				val path = _uiState.value.modelStoragePath.trim()
				viewModelScope.launch {
					val targetDir = if (path.isBlank()) {
						File(defaultStoragePath)
					} else {
						File(path)
					}

					if (!targetDir.exists() && !targetDir.mkdirs()) {
						_uiState.update {
							it.copy(error = "Unable to create storage folder: ${targetDir.absolutePath}")
						}
						return@launch
					}

					settingsRepository.setModelStoragePath(path)
					_uiState.update {
						it.copy(
							error = null,
							effectiveModelStoragePath = targetDir.absolutePath,
							usingDefaultStoragePath = path.isBlank(),
						)
					}
				}
			}

			SettingsAction.DismissError -> _uiState.update { it.copy(error = null) }
		}
	}

	private fun refreshGpuDriverStatus() {
		val driverReport = ggufEngine.getDriverReport()
		val loadedInfo = ggufEngine.getLoadedModelInfo()

		val backendLabel = when {
			loadedInfo == null -> "Idle"
			loadedInfo.nativeBackend && loadedInfo.gpuLayers > 0 -> "GPU"
			loadedInfo.nativeBackend -> "CPU native"
			else -> "CPU fallback"
		}

		val headline = when {
			driverReport.adrenoDetected &&
				driverReport.vulkanSupported &&
				driverReport.turnipAssetsBundled &&
				driverReport.nativeBackendAvailable ->
				"GPU acceleration ready (Adreno + Turnip + native backend)"
			driverReport.adrenoDetected && driverReport.vulkanSupported ->
				"Adreno detected, using system Vulkan/native path"
			driverReport.qualcommDetected ->
				"Qualcomm device detected, GPU fallback checks active"
			driverReport.vulkanSupported ->
				"Vulkan runtime available (non-Adreno profile)"
			else ->
				"CPU-safe mode (Vulkan or GPU path not confirmed)"
		}

		val checks = listOf(
			"Adreno signal: ${if (driverReport.adrenoDetected) "detected" else "not detected"}",
			"Qualcomm signal: ${if (driverReport.qualcommDetected) "detected" else "not detected"}",
			"Vulkan runtime: ${if (driverReport.vulkanSupported) "available" else "not reported"}",
			"Turnip assets: ${if (driverReport.turnipAssetsBundled) "bundled" else "missing"}",
			"Native backend: ${if (driverReport.nativeBackendAvailable) "loaded" else "missing"}",
			"Turnip ICD path: ${driverReport.turnipIcdPath ?: "not prepared"}",
		)

		val details = buildString {
			append("Native backend: ")
			append(if (driverReport.nativeBackendAvailable) "available" else "missing")
			append(" | Active mode: ")
			append(backendLabel)
			append("\nSoC: ")
			append(driverReport.socManufacturer ?: "unknown")
			append(' ')
			append(driverReport.socModel ?: "")
			append(" | Hardware: ")
			append(driverReport.deviceHardware)
			append("\nBuild: ")
			append(driverReport.buildDisplay)
			append(" | Android: ")
			append(driverReport.androidRelease)
		}

		_uiState.update {
			it.copy(
				gpuDriverStatus = headline,
				gpuDriverDetails = details,
				gpuValidationChecks = checks,
			)
		}
	}

	private fun resolveDefaultStoragePath(): File {
		val externalDownloads = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
		val root = File(externalDownloads ?: appContext.filesDir, "MasterLLM/models/hf")
		if (!root.exists()) {
			root.mkdirs()
		}
		return root
	}

	private fun resolveEffectiveStoragePath(path: String): String {
		val trimmed = path.trim()
		if (trimmed.isBlank()) {
			return defaultStoragePath
		}
		return File(trimmed).absolutePath
	}
}
