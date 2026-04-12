package com.masterllm.feature.auth

import com.google.common.truth.Truth.assertThat
import com.masterllm.core.domain.model.ImageFrequency
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.core.network.HuggingFaceApi
import com.masterllm.core.network.model.HfModelResponse
import com.masterllm.core.network.model.HfWhoamiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun login_normalizesBearerTokenBeforeValidationAndSave() = runTest(dispatcher) {
        val settingsRepository = InMemorySettingsRepository()
        val api = FakeHuggingFaceApi()
        val viewModel = AuthViewModel(settingsRepository, api)

        viewModel.onAction(AuthAction.TokenChanged("Bearer hf_token_123"))
        viewModel.onAction(AuthAction.Login)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isAuthenticated).isTrue()
        assertThat(state.username).isEqualTo("tester")
        assertThat(settingsRepository.hfToken.value).isEqualTo("hf_token_123")
        assertThat(api.lastAuthHeader).isEqualTo("Bearer hf_token_123")
    }

    @Test
    fun login_fallsBackToWhoamiWhenWhoamiV2Fails() = runTest(dispatcher) {
        val settingsRepository = InMemorySettingsRepository()
        val api = FakeHuggingFaceApi(failWhoamiV2 = true)
        val viewModel = AuthViewModel(settingsRepository, api)

        viewModel.onAction(AuthAction.TokenChanged("hf_token_456"))
        viewModel.onAction(AuthAction.Login)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isAuthenticated).isTrue()
        assertThat(settingsRepository.hfToken.value).isEqualTo("hf_token_456")
        assertThat(api.whoamiCalled).isTrue()
    }

    private class InMemorySettingsRepository : SettingsRepository {
        val hfToken = MutableStateFlow("")
        private val hfUsername = MutableStateFlow("")
        private val threshold = MutableStateFlow(80)
        private val threadCount = MutableStateFlow(4)
        private val theme = MutableStateFlow("system")
        private val imageFrequency = MutableStateFlow(ImageFrequency.EVERY_RESPONSE)
        private val consistency = MutableStateFlow(true)
        private val gpu = MutableStateFlow(false)
        private val modelPath = MutableStateFlow("")

        override fun getHfToken(): Flow<String> = hfToken
        override suspend fun setHfToken(token: String) {
            hfToken.value = token
        }

        override fun getHfUsername(): Flow<String> = hfUsername
        override suspend fun setHfUsername(username: String) {
            hfUsername.value = username
        }

        override fun getAutoCompactionThreshold(): Flow<Int> = threshold
        override suspend fun setAutoCompactionThreshold(percent: Int) {
            threshold.value = percent
        }

        override fun getDefaultThreadCount(): Flow<Int> = threadCount
        override suspend fun setDefaultThreadCount(count: Int) {
            threadCount.value = count
        }

        override fun getTheme(): Flow<String> = theme
        override suspend fun setTheme(theme: String) {
            this.theme.value = theme
        }

        override fun getDefaultImageFrequency(): Flow<ImageFrequency> = imageFrequency
        override suspend fun setDefaultImageFrequency(freq: ImageFrequency) {
            imageFrequency.value = freq
        }

        override fun getCharacterConsistencyEnabled(): Flow<Boolean> = consistency
        override suspend fun setCharacterConsistencyEnabled(enabled: Boolean) {
            consistency.value = enabled
        }

        override fun getGpuAccelerationEnabled(): Flow<Boolean> = gpu
        override suspend fun setGpuAccelerationEnabled(enabled: Boolean) {
            gpu.value = enabled
        }

        override fun getModelStoragePath(): Flow<String> = modelPath
        override suspend fun setModelStoragePath(path: String) {
            modelPath.value = path
        }
    }

    private class FakeHuggingFaceApi(
        private val failWhoamiV2: Boolean = false,
    ) : HuggingFaceApi {

        var lastAuthHeader: String? = null
        var whoamiCalled: Boolean = false

        override suspend fun searchModels(
            query: String,
            filter: String?,
            sort: String,
            direction: String,
            limit: Int,
            offset: Int,
        ): List<HfModelResponse> = emptyList()

        override suspend fun getModelInfo(repoId: String): HfModelResponse = HfModelResponse(id = repoId)

        override suspend fun whoamiV2(auth: String): HfWhoamiResponse {
            lastAuthHeader = auth
            if (failWhoamiV2) {
                throw IllegalStateException("whoami-v2 unavailable")
            }
            return HfWhoamiResponse(name = "tester")
        }

        override suspend fun whoami(auth: String): HfWhoamiResponse {
            whoamiCalled = true
            lastAuthHeader = auth
            return HfWhoamiResponse(name = "tester")
        }

        override suspend fun downloadFile(
            repoId: String,
            fileName: String,
            auth: String?,
        ): Response<ResponseBody> = Response.success(
            "ok".toResponseBody("text/plain".toMediaType())
        )
    }
}
