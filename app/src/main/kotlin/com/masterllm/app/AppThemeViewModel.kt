package com.masterllm.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppThemeUiState(
    val theme: String = "system",
)

@HiltViewModel
class AppThemeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppThemeUiState())
    val uiState: StateFlow<AppThemeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.getTheme().collect { selectedTheme ->
                _uiState.update { current ->
                    current.copy(theme = selectedTheme)
                }
            }
        }
    }
}
