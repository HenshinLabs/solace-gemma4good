package com.masterllm.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.core.network.HuggingFaceApi
import com.masterllm.core.network.normalizeHfToken
import com.masterllm.core.network.toBearerAuthHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val token: String = "",
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val username: String = "",
    val error: String? = null,
)

sealed interface AuthAction {
    data class TokenChanged(val token: String) : AuthAction
    data object Login : AuthAction
    data object Logout : AuthAction
    data object DismissError : AuthAction
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val huggingFaceApi: HuggingFaceApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.getHfToken(),
                settingsRepository.getHfUsername(),
            ) { token, username ->
                _uiState.update {
                    it.copy(
                        token = token,
                        isAuthenticated = token.isNotEmpty() && username.isNotEmpty(),
                        username = username,
                    )
                }
            }.collect()
        }
    }

    fun onAction(action: AuthAction) {
        when (action) {
            is AuthAction.TokenChanged -> _uiState.update { it.copy(token = action.token, error = null) }
            AuthAction.Login -> login()
            AuthAction.Logout -> logout()
            AuthAction.DismissError -> _uiState.update { it.copy(error = null) }
        }
    }

    private fun login() {
        val normalizedToken = normalizeHfToken(_uiState.value.token)
        if (normalizedToken.isEmpty()) {
            _uiState.update { it.copy(error = "Token cannot be empty") }
            return
        }

        val authHeader = toBearerAuthHeader(normalizedToken)
        if (authHeader == null) {
            _uiState.update { it.copy(error = "Invalid token format") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = runCatching { huggingFaceApi.whoamiV2(authHeader) }
                    .getOrElse { huggingFaceApi.whoami(authHeader) }
                val username = response.name
                    ?.takeIf { it.isNotBlank() }
                    ?: response.fullname?.takeIf { it.isNotBlank() }
                    ?: "hf-user"

                settingsRepository.setHfToken(normalizedToken)
                settingsRepository.setHfUsername(username)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        username = username,
                    )
                }
            } catch (e: Exception) {
                val errorText = "Token validation failed: ${e.message ?: "network error"}"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = errorText,
                    )
                }
            }
        }
    }

    private fun logout() {
        viewModelScope.launch {
            settingsRepository.setHfToken("")
            settingsRepository.setHfUsername("")
            _uiState.update {
                AuthUiState()
            }
        }
    }
}
