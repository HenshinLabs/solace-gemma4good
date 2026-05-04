package com.masterllm.feature.marketplace

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.google.common.truth.Truth.assertThat
import com.masterllm.core.domain.model.DownloadState
import com.masterllm.core.domain.model.HfModelFile
import com.masterllm.core.domain.model.HfModelInfo
import com.masterllm.core.domain.model.ImageFrequency
import com.masterllm.core.domain.model.LlmModel
import com.masterllm.core.domain.model.ModelFormat
import com.masterllm.core.domain.repository.ModelRepository
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.core.network.HuggingFaceApi
import com.masterllm.core.network.model.HfModelResponse
import com.masterllm.core.network.model.HfSiblingResponse
import com.masterllm.core.network.model.HfWhoamiResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class MarketplaceViewModelTest {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun search_populatesResultsFromApi() = runTest(dispatcher) {
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(token = "hf_test")
        val api = FakeHuggingFaceApi().apply {
            searchResponses["GGUF"] = listOf(
                modelResponse(
                    modelId = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
                    siblings = listOf(HfSiblingResponse("tinyllama.Q4_K_M.gguf", 1234L)),
                )
            )
            searchResponses["mistral"] = listOf(
                modelResponse(
                    modelId = "TheBloke/Mistral-7B-Instruct-v0.2-GGUF",
                    siblings = listOf(HfSiblingResponse("mistral.Q4_K_M.gguf", 4567L)),
                )
            )
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        vm.onAction(MarketplaceAction.SearchQueryChanged("mistral"))
        vm.onAction(MarketplaceAction.Search)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.searchResults).isNotEmpty()
        assertThat(state.searchResults.first().modelId).contains("Mistral-7B")
        assertThat(api.searchQueries).contains("mistral")
    }

    @Test
    fun downloadModel_gguf_marksModelDownloadedAndWritesFile() = runTest(dispatcher) {
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(token = "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")
        val api = FakeHuggingFaceApi().apply {
            searchResponses["GGUF"] = emptyList()
            downloadPayloads["tinyllama.Q4_K_M.gguf"] = "GGUF_PAYLOAD".encodeToByteArray()
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        val info = HfModelInfo(
            modelId = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            siblings = listOf(HfModelFile("tinyllama.Q4_K_M.gguf", 12L)),
        )
        val file = HfModelFile("tinyllama.Q4_K_M.gguf", 12L)

        vm.onAction(MarketplaceAction.DownloadModel(info, file))
        advanceUntilIdle()
        waitUntil {
            modelRepository.getModelByRepoAndFile(info.modelId, file.rfilename)
                ?.downloadState == DownloadState.DOWNLOADED
        }
        advanceUntilIdle()

        val stored = modelRepository.getModelByRepoAndFile(info.modelId, file.rfilename)
        assertThat(stored).isNotNull()
        assertThat(stored?.downloadState).isEqualTo(DownloadState.DOWNLOADED)
        assertThat(stored?.format).isEqualTo(ModelFormat.GGUF)
        assertThat(stored?.localPath).isNotNull()
        assertThat(File(stored!!.localPath!!).exists()).isTrue()
        assertThat(api.lastAuthHeader).isEqualTo("Bearer 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")
    }

    @Test
    fun downloadDiffusersBundle_downloadsRequiredFilesAndMarksDownloaded() = runTest(dispatcher) {
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(token = "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")
        val api = FakeHuggingFaceApi().apply {
            searchResponses["GGUF"] = emptyList()
            val siblings = listOf(
                HfSiblingResponse("model_index.json", 10L),
                HfSiblingResponse("scheduler/scheduler_config.json", 12L),
                HfSiblingResponse("tokenizer/tokenizer_config.json", 8L),
                HfSiblingResponse("unet/diffusion_pytorch_model.safetensors", 20L),
                HfSiblingResponse("vae/diffusion_pytorch_model.safetensors", 20L),
            )
            modelInfos["stabilityai/sdxl-turbo"] = modelResponse(
                modelId = "stabilityai/sdxl-turbo",
                siblings = siblings,
            )
            downloadPayloads["model_index.json"] = "{}".encodeToByteArray()
            downloadPayloads["scheduler/scheduler_config.json"] = "{}".encodeToByteArray()
            downloadPayloads["tokenizer/tokenizer_config.json"] = "{}".encodeToByteArray()
            downloadPayloads["unet/diffusion_pytorch_model.safetensors"] = "A".repeat(32).encodeToByteArray()
            downloadPayloads["vae/diffusion_pytorch_model.safetensors"] = "B".repeat(32).encodeToByteArray()
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        val info = HfModelInfo(
            modelId = "stabilityai/sdxl-turbo",
            siblings = listOf(
                HfModelFile("model_index.json", 10L),
                HfModelFile("unet/diffusion_pytorch_model.safetensors", 20L),
            ),
        )
        val diffModelId = "diff_${info.modelId.hashCode()}"

        vm.onAction(MarketplaceAction.DownloadDiffusersBundle(info))
        advanceUntilIdle()
        waitUntil {
            modelRepository.getModelById(diffModelId)?.downloadState == DownloadState.DOWNLOADED
        }
        advanceUntilIdle()
        val stored = modelRepository.getModelById(diffModelId)
        assertThat(stored).isNotNull()
        assertThat(stored?.format).isEqualTo(ModelFormat.DIFFUSERS)
        assertThat(stored?.downloadState).isEqualTo(DownloadState.DOWNLOADED)

        val modelDir = File(stored!!.localPath!!)
        assertThat(modelDir.exists()).isTrue()
        assertThat(File(modelDir, "model_index.json").exists()).isTrue()
        assertThat(File(modelDir, "unet/diffusion_pytorch_model.safetensors").exists()).isTrue()
    }

    @Test
    fun downloadModel_usesConfiguredStoragePathWhenProvided() = runTest(dispatcher) {
        val customRoot = createTempDir(prefix = "custom-storage-")
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(
            token = "hf_custom_storage_token",
            modelStoragePath = customRoot.absolutePath,
        )
        val api = FakeHuggingFaceApi().apply {
            searchResponses["GGUF"] = emptyList()
            downloadPayloads["tinyllama.Q5_K_M.gguf"] = "GGUF_PAYLOAD".encodeToByteArray()
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        val info = HfModelInfo(
            modelId = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            siblings = listOf(HfModelFile("tinyllama.Q5_K_M.gguf", 12L)),
        )
        val file = HfModelFile("tinyllama.Q5_K_M.gguf", 12L)

        vm.onAction(MarketplaceAction.DownloadModel(info, file))
        waitUntil {
            modelRepository.getModelByRepoAndFile(info.modelId, file.rfilename)
                ?.downloadState == DownloadState.DOWNLOADED
        }

        val stored = modelRepository.getModelByRepoAndFile(info.modelId, file.rfilename)
        assertThat(stored?.localPath).startsWith(customRoot.absolutePath)
        assertThat(File(stored!!.localPath!!).exists()).isTrue()
    }

    @Test
    fun pagination_loadsNextAndPreviousPages() = runTest(dispatcher) {
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(token = "hf_test")
        val api = FakeHuggingFaceApi().apply {
            searchResponses["GGUF"] = emptyList()
            pagedSearchResponses["llama" to 0] = List(20) { index ->
                modelResponse(
                    modelId = "repo/llama-page1-$index",
                    siblings = listOf(HfSiblingResponse("page1-$index.Q4_K_M.gguf", 100L + index)),
                )
            }
            pagedSearchResponses["llama" to 20] = listOf(
                modelResponse(
                    modelId = "repo/llama-page2",
                    siblings = listOf(HfSiblingResponse("page2.Q4_K_M.gguf", 222L)),
                )
            )
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        vm.onAction(MarketplaceAction.SearchQueryChanged("llama"))
        vm.onAction(MarketplaceAction.Search)
        advanceUntilIdle()

        assertThat(vm.uiState.value.currentPage).isEqualTo(0)
        assertThat(vm.uiState.value.hasNextPage).isTrue()
        assertThat(vm.uiState.value.searchResults).isNotEmpty()

        vm.onAction(MarketplaceAction.NextPage)
        advanceUntilIdle()

        assertThat(vm.uiState.value.currentPage).isEqualTo(1)
        assertThat(vm.uiState.value.searchResults.first().modelId).isEqualTo("repo/llama-page2")

        vm.onAction(MarketplaceAction.PreviousPage)
        advanceUntilIdle()

        assertThat(vm.uiState.value.currentPage).isEqualTo(0)
    }

    @Test
    fun formatFilter_gguf_worksWhenSearchResultsLackSiblings() = runTest(dispatcher) {
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(token = "hf_test")
        val api = FakeHuggingFaceApi().apply {
            searchResponses["Qwen gguf"] = listOf(
                HfModelResponse(
                    modelId = "bartowski/Qwen2.5-0.5B-Instruct-GGUF",
                    id = "bartowski/Qwen2.5-0.5B-Instruct-GGUF",
                    author = "bartowski",
                    siblings = null,
                    tags = listOf("gguf", "text-generation"),
                )
            )
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        vm.onAction(MarketplaceAction.SearchQueryChanged("Qwen"))
        vm.onAction(MarketplaceAction.Search)
        advanceUntilIdle()

        vm.onAction(MarketplaceAction.FormatFilterChanged(ModelFormatFilter.GGUF))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.searchResults).isNotEmpty()
        assertThat(state.searchResults.first().modelId).contains("Qwen2.5-0.5B")
    }

    @Test
    fun filteredResults_noneOnCurrentScan_resetsTotalsAndPagination() = runTest(dispatcher) {
        val modelRepository = InMemoryModelRepository()
        val settingsRepository = InMemorySettingsRepository(token = "hf_test")
        val api = FakeHuggingFaceApi().apply {
            searchResponses["Qwen gguf"] = List(20) { index ->
                modelResponse(
                    modelId = "repo/qwen-page-$index",
                    siblings = listOf(HfSiblingResponse("weights-$index.safetensors", 100L + index)),
                )
            }
        }

        val vm = createViewModel(modelRepository, settingsRepository, api)
        advanceUntilIdle()

        vm.onAction(MarketplaceAction.SearchQueryChanged("Qwen"))
        vm.onAction(MarketplaceAction.Search)
        advanceUntilIdle()

        vm.onAction(MarketplaceAction.FormatFilterChanged(ModelFormatFilter.GGUF))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.searchResults).isEmpty()
        assertThat(state.totalResults).isEqualTo(0)
        assertThat(state.lastSearchResultsCount).isEqualTo(0)
        assertThat(state.hasNextPage).isFalse()
        assertThat(state.currentPage).isEqualTo(0)
    }

    private fun createViewModel(
        modelRepository: InMemoryModelRepository,
        settingsRepository: InMemorySettingsRepository,
        api: FakeHuggingFaceApi,
    ): MarketplaceViewModel {
        val filesDir = createTempDir(prefix = "marketplace-test-")
        val externalDownloadsDir = File(filesDir, "external/downloads").apply { mkdirs() }
        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()
        val activityManager = mockk<ActivityManager>()

        every { context.filesDir } returns filesDir
        every { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns externalDownloadsDir
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(any()) } returns false
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.totalMem = 8L * 1024L * 1024L * 1024L
            info.availMem = 4L * 1024L * 1024L * 1024L
            Unit
        }

        return MarketplaceViewModel(
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
            huggingFaceApi = api,
            appContext = context,
        )
    }

    private suspend fun TestScope.waitUntil(
        timeoutMs: Long = 5_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
        }
    }

    private fun modelResponse(
        modelId: String,
        siblings: List<HfSiblingResponse>,
    ): HfModelResponse = HfModelResponse(
        modelId = modelId,
        id = modelId,
        author = modelId.substringBefore('/'),
        siblings = siblings,
    )

    private class InMemoryModelRepository : ModelRepository {
        private val models = ConcurrentHashMap<String, LlmModel>()
        private val downloaded = MutableStateFlow<List<LlmModel>>(emptyList())

        override fun getDownloadedModels(): Flow<List<LlmModel>> = downloaded

        override suspend fun getModelById(id: String): LlmModel? = models[id]

        override suspend fun deleteModel(id: String) {
            models.remove(id)
            emitDownloaded()
        }

        override suspend fun saveModel(model: LlmModel) {
            models[model.id] = model
            emitDownloaded()
        }

        override suspend fun updateDownloadState(id: String, state: DownloadState, localPath: String?) {
            val current = models[id] ?: return
            models[id] = current.copy(downloadState = state, localPath = localPath)
            emitDownloaded()
        }

        override suspend fun getModelByRepoAndFile(repoId: String, fileName: String): LlmModel? {
            return models.values.firstOrNull { it.repoId == repoId && it.fileName == fileName }
        }

        private fun emitDownloaded() {
            downloaded.value = models.values.filter { it.downloadState == DownloadState.DOWNLOADED }
        }
    }

    private class InMemorySettingsRepository(
        token: String,
        modelStoragePath: String = "",
    ) : SettingsRepository {
        private val hfToken = MutableStateFlow(token)
        private val hfUsername = MutableStateFlow("")
        private val threshold = MutableStateFlow(80)
        private val threadCount = MutableStateFlow(4)
        private val theme = MutableStateFlow("system")
        private val imageFrequency = MutableStateFlow(ImageFrequency.KEY_MOMENTS)
        private val consistency = MutableStateFlow(true)
        private val gpu = MutableStateFlow(true)
        private val modelPath = MutableStateFlow(modelStoragePath)

        override fun getHfToken(): Flow<String> = hfToken
        override suspend fun setHfToken(token: String) { hfToken.value = token }

        override fun getHfUsername(): Flow<String> = hfUsername
        override suspend fun setHfUsername(username: String) { hfUsername.value = username }

        override fun getAutoCompactionThreshold(): Flow<Int> = threshold
        override suspend fun setAutoCompactionThreshold(percent: Int) { threshold.value = percent }

        override fun getDefaultThreadCount(): Flow<Int> = threadCount
        override suspend fun setDefaultThreadCount(count: Int) { threadCount.value = count }

        override fun getTheme(): Flow<String> = theme
        override suspend fun setTheme(theme: String) { this.theme.value = theme }

        override fun getDefaultImageFrequency(): Flow<ImageFrequency> = imageFrequency
        override suspend fun setDefaultImageFrequency(freq: ImageFrequency) { imageFrequency.value = freq }

        override fun getCharacterConsistencyEnabled(): Flow<Boolean> = consistency
        override suspend fun setCharacterConsistencyEnabled(enabled: Boolean) { consistency.value = enabled }

        override fun getGpuAccelerationEnabled(): Flow<Boolean> = gpu
        override suspend fun setGpuAccelerationEnabled(enabled: Boolean) { gpu.value = enabled }

        override fun getModelStoragePath(): Flow<String> = modelPath
        override suspend fun setModelStoragePath(path: String) { modelPath.value = path }

        override fun getOllamaHost(): Flow<String> = MutableStateFlow("http://localhost:11434")
        override suspend fun setOllamaHost(host: String) {}
        override fun getOllamaEnabled(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setOllamaEnabled(enabled: Boolean) {}
        override fun getOllamaKeepAlive(): Flow<String> = MutableStateFlow("300")
        override suspend fun setOllamaKeepAlive(keepAlive: String) {}
        override fun getOllamaSystemPrompt(): Flow<String> = MutableStateFlow("")
        override suspend fun setOllamaSystemPrompt(prompt: String) {}
    }

    private class FakeHuggingFaceApi : HuggingFaceApi {
        val searchResponses = mutableMapOf<String, List<HfModelResponse>>()
        val pagedSearchResponses = mutableMapOf<Pair<String, Int>, List<HfModelResponse>>()
        val modelInfos = mutableMapOf<String, HfModelResponse>()
        val downloadPayloads = mutableMapOf<String, ByteArray>()
        val searchQueries = mutableListOf<String>()
        var lastAuthHeader: String? = null

        override suspend fun searchModels(
            query: String,
            filter: String?,
            sort: String,
            direction: String,
            limit: Int,
            offset: Int,
        ): List<HfModelResponse> {
            searchQueries += query
            pagedSearchResponses[query to offset]?.let { return it }
            return searchResponses[query] ?: emptyList()
        }

        override suspend fun getModelInfo(repoId: String): HfModelResponse {
            return modelInfos[repoId] ?: error("Missing model info for $repoId")
        }

        override suspend fun whoami(auth: String): HfWhoamiResponse = HfWhoamiResponse(name = "tester")

        override suspend fun whoamiV2(auth: String): HfWhoamiResponse = HfWhoamiResponse(name = "tester")

        override suspend fun downloadFile(
            repoId: String,
            fileName: String,
            auth: String?,
            range: String?,
        ): Response<ResponseBody> {
            lastAuthHeader = auth
            val payload = downloadPayloads[fileName]
                ?: return Response.error(
                    404,
                    "missing".toResponseBody("text/plain".toMediaType()),
                )
            return Response.success(payload.toResponseBody("application/octet-stream".toMediaType()))
        }
    }
}
