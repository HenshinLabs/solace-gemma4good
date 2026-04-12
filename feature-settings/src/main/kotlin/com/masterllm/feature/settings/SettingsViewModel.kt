package com.masterllm.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.ImageFrequency
import com.masterllm.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) : ViewModel() {

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
				_uiState.update { it.copy(modelStoragePath = value) }
			}
		}
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
				viewModelScope.launch { settingsRepository.setGpuAccelerationEnabled(action.enabled) }
			}

			is SettingsAction.ModelStoragePathChanged -> {
				_uiState.update { it.copy(modelStoragePath = action.path) }
			}

			SettingsAction.SaveModelStoragePath -> {
				val path = _uiState.value.modelStoragePath.trim()
				if (path.isEmpty()) {
					_uiState.update { it.copy(error = "Model storage path cannot be empty") }
					return
				}
				viewModelScope.launch {
					settingsRepository.setModelStoragePath(path)
				}
			}

			SettingsAction.DismissError -> _uiState.update { it.copy(error = null) }
		}
	}
}
