package com.masterllm.feature.model.manager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagerUiState(
	val models: List<LlmModel> = emptyList(),
	val error: String? = null,
)

sealed interface ModelManagerAction {
	data class DeleteModel(val id: String) : ModelManagerAction
	data object DismissError : ModelManagerAction
}

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
	private val modelRepository: ModelRepository,
) : ViewModel() {

	private val _uiState = MutableStateFlow(ModelManagerUiState())
	val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			modelRepository.getDownloadedModels().collect { models ->
				_uiState.update { it.copy(models = models) }
			}
		}
	}

	fun onAction(action: ModelManagerAction) {
		when (action) {
			is ModelManagerAction.DeleteModel -> deleteModel(action.id)
			ModelManagerAction.DismissError -> _uiState.update { it.copy(error = null) }
		}
	}

	private fun deleteModel(id: String) {
		viewModelScope.launch {
			try {
				modelRepository.deleteModel(id)
			} catch (e: Exception) {
				_uiState.update {
					it.copy(error = "Failed to delete model: ${e.message ?: "unknown error"}")
				}
			}
		}
	}
}
