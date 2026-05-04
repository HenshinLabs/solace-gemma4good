package com.masterllm.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.ollama.api.OllamaApiService
import com.masterllm.core.ollama.model.OllamaLibraryModel
import com.masterllm.core.ollama.model.OllamaLibraryModelDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OllamaExplorerUiState(
    val searchQuery: String = "",
    val models: List<OllamaLibraryModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedModel: OllamaLibraryModelDetail? = null,
    val isLoadingDetail: Boolean = false,
    val detailError: String? = null,
    val downloadingModel: String? = null,
    val downloadTag: String? = null,
    val downloadProgress: Float? = null,
    val downloadStatus: String? = null,
    val downloadComplete: Boolean = false,
)

sealed interface OllamaExplorerAction {
    data class SearchQueryChanged(val query: String) : OllamaExplorerAction
    data class ModelSelected(val modelName: String) : OllamaExplorerAction
    data object DismissDetail : OllamaExplorerAction
    data class DownloadModel(val modelName: String, val tag: String) : OllamaExplorerAction
    data object DismissError : OllamaExplorerAction
}

@OptIn(FlowPreview::class)
@HiltViewModel
class OllamaModelExplorerViewModel @Inject constructor(
    private val ollamaApiService: OllamaApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OllamaExplorerUiState())
    val uiState: StateFlow<OllamaExplorerUiState> = _uiState.asStateFlow()

    init {
        loadLibrary("")

        viewModelScope.launch {
            _uiState
                .map { it.searchQuery }
                .debounce(300)
                .distinctUntilChanged()
                .drop(1)
                .collect { query ->
                    loadLibrary(query)
                }
        }
    }

    fun onAction(action: OllamaExplorerAction) {
        when (action) {
            is OllamaExplorerAction.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = action.query) }
            }
            is OllamaExplorerAction.ModelSelected -> {
                loadModelDetail(action.modelName)
            }
            OllamaExplorerAction.DismissDetail -> {
                _uiState.update { it.copy(selectedModel = null, detailError = null) }
            }
            is OllamaExplorerAction.DownloadModel -> {
                downloadModel(action.modelName, action.tag)
            }
            OllamaExplorerAction.DismissError -> {
                _uiState.update { it.copy(error = null) }
            }
        }
    }

    private fun loadLibrary(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = ollamaApiService.searchLibrary(query)
            result.onSuccess { models ->
                _uiState.update { it.copy(models = models, isLoading = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to search models",
                )}
            }
        }
    }

    private fun loadModelDetail(modelName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDetail = true, detailError = null) }
            val result = ollamaApiService.getLibraryModelDetail(modelName)
            result.onSuccess { detail ->
                _uiState.update { it.copy(
                    selectedModel = detail,
                    isLoadingDetail = false,
                )}
            }.onFailure { e ->
                _uiState.update { it.copy(
                    isLoadingDetail = false,
                    detailError = e.message ?: "Failed to load model details",
                )}
            }
        }
    }

    private fun downloadModel(modelName: String, tag: String) {
        viewModelScope.launch {
            val modelRef = "$modelName:$tag"
            _uiState.update { it.copy(
                downloadingModel = modelRef,
                downloadTag = tag,
                downloadProgress = 0f,
                downloadStatus = "Starting download...",
                downloadComplete = false,
            )}

            ollamaApiService.pullModelStream(modelRef)
                .catch { e ->
                    _uiState.update { it.copy(
                        error = "Download failed: ${e.message}",
                        downloadingModel = null,
                        downloadTag = null,
                        downloadProgress = null,
                        downloadStatus = null,
                    )}
                }
                .collect { update ->
                    val status = update.status ?: ""
                    if (status.contains("success", ignoreCase = true)) {
                        _uiState.update { it.copy(
                            downloadingModel = null,
                            downloadProgress = 1f,
                            downloadStatus = "Download complete!",
                            downloadComplete = true,
                        )}
                    } else {
                        val total = update.total ?: 0L
                        val completed = update.completed ?: 0L
                        val progress = if (total > 0) {
                            (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else null
                        _uiState.update { it.copy(
                            downloadProgress = progress,
                            downloadStatus = status,
                        )}
                    }
                }
        }
    }
}
