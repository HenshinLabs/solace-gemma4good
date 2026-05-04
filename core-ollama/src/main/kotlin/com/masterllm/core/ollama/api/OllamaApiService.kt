package com.masterllm.core.ollama.api

import com.google.gson.Gson
import com.masterllm.core.ollama.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OllamaApiService @Inject constructor() {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var baseUrl: String = "http://localhost:11434"
    private var customHeaders: Map<String, String> = emptyMap()
    private var timeoutMultiplier: Float = 1.0f

    private var httpClient: OkHttpClient? = null

    fun configure(host: String, headers: Map<String, String> = emptyMap(), timeoutMult: Float = 1.0f) {
        baseUrl = host.trimEnd('/')
        customHeaders = headers
        timeoutMultiplier = timeoutMult
        rebuildClient()
    }

    private fun rebuildClient() {
        httpClient = OkHttpClient.Builder()
            .connectTimeout((30 * timeoutMultiplier).toLong(), TimeUnit.SECONDS)
            .readTimeout((10 * 60 * timeoutMultiplier).toLong(), TimeUnit.SECONDS)
            .writeTimeout((30 * timeoutMultiplier).toLong(), TimeUnit.SECONDS)
            .apply {
                if (baseUrl.startsWith("http://")) {
                    // Allow cleartext for localhost Ollama connections
                    hostnameVerifier { _, _ -> true }
                }
            }
            .build()
    }

    private fun getClient(): OkHttpClient {
        if (httpClient == null) rebuildClient()
        return httpClient!!
    }

    suspend fun validateHost(): OllamaValidationResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl + "/").get().build()
            val response = getClient().newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful && body.contains("Ollama")) {
                OllamaValidationResult(isAvailable = true, version = body.trim())
            } else {
                OllamaValidationResult(isAvailable = false, error = "Unexpected response: ${response.code}")
            }
        } catch (e: ConnectException) {
            OllamaValidationResult(isAvailable = false, error = "Cannot connect to Ollama server at $baseUrl")
        } catch (e: SocketTimeoutException) {
            OllamaValidationResult(isAvailable = false, error = "Connection timed out")
        } catch (e: Exception) {
            OllamaValidationResult(isAvailable = false, error = e.message ?: "Unknown error")
        }
    }

    suspend fun listModels(): Result<List<OllamaModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/api/tags").get().build()
            val response = getClient().newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val listResponse = gson.fromJson(body, ListModelsResponse::class.java)
            listResponse.models
        }
    }

    suspend fun showModelInfo(name: String): Result<ShowModelResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val json = gson.toJson(ShowModelRequest(name))
            val body = json.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/api/show")
                .post(body)
                .build()
            val response = getClient().newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            gson.fromJson(responseBody, ShowModelResponse::class.java)
        }
    }

    fun chatStream(request: ChatRequest): Flow<String> = flow {
        val json = gson.toJson(request)
        val reqBody = json.toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(reqBody)
            .addHeader("Accept", "application/x-ndjson")
            .build()

        withContext(Dispatchers.IO) {
            val response = getClient().newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw Exception("Ollama API error: ${response.code} ${response.message}")
            }

            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()!!))
            reader.use { bufReader ->
                var line: String?
                while (bufReader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.isBlank()) continue
                    try {
                        val chunk = gson.fromJson(currentLine, ChatCompletionChunk::class.java)
                        val content = chunk.message?.content ?: ""
                        if (content.isNotEmpty()) {
                            emit(content)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    suspend fun chat(request: ChatRequest): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val nonStreamingRequest = request.copy(stream = false)
            val json = gson.toJson(nonStreamingRequest)
            val reqBody = json.toRequestBody(jsonMediaType)
            val httpRequest = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(reqBody)
                .build()
            val response = getClient().newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
            chatResponse.message?.content ?: ""
        }
    }

    fun pullModelStream(name: String): Flow<PullModelResponse> = flow {
        val json = gson.toJson(PullModelRequest(name = name))
        val reqBody = json.toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/pull")
            .post(reqBody)
            .addHeader("Accept", "application/x-ndjson")
            .build()

        withContext(Dispatchers.IO) {
            val response = getClient().newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw Exception("Pull failed: ${response.code} ${response.message}")
            }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()!!))
            reader.use { bufReader ->
                var line: String?
                while (bufReader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.isBlank()) continue
                    try {
                        val update = gson.fromJson(currentLine, PullModelResponse::class.java)
                        emit(update)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    suspend fun preloadModel(name: String, keepAlive: Any = 300): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = gson.toJson(GenerateRequest(model = name, keep_alive = keepAlive))
            val reqBody = json.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/api/generate")
                .post(reqBody)
                .build()
            getClient().newCall(request).execute().close()
        }
    }

    suspend fun searchLibrary(query: String = ""): Result<List<OllamaLibraryModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            val results = filterCuratedLibrary(trimmed)
            android.util.Log.i("OllamaApiService", "Using curated library (${results.size} models) for query: '${trimmed}'")
            results
        }
    }

    suspend fun getLibraryModelDetail(modelName: String): Result<OllamaLibraryModelDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedName = java.net.URLEncoder.encode(modelName.trim(), "UTF-8")
            val request = Request.Builder()
                .url("https://ollama.com/api/models/$encodedName")
                .get()
                .addHeader("Accept", "application/json")
                .build()
            val response = getClient().newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val detail = gson.fromJson(body, OllamaLibraryModelDetail::class.java)
                detail
            } else {
                getCuratedModelDetail(modelName)
            }
        }.recover {
            getCuratedModelDetail(modelName)
        }
    }

    private fun filterCuratedLibrary(query: String): List<OllamaLibraryModel> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return getCuratedLibrary()
        return getCuratedLibrary().filter {
            it.name.contains(trimmed, ignoreCase = true) ||
            it.description.contains(trimmed, ignoreCase = true) ||
            it.tags.any { tag -> tag.contains(trimmed, ignoreCase = true) }
        }
    }

    private fun getCuratedLibrary(): List<OllamaLibraryModel> = listOf(
        OllamaLibraryModel("llama3.2", "Meta's lightweight Llama 3.2 (1B, 3B) for efficient local inference", "5.2M", listOf("latest", "1b", "3b"), "2024-12"),
        OllamaLibraryModel("llama3.1", "Meta's Llama 3.1 family (8B, 70B, 405B)", "8.3M", listOf("latest", "8b", "70b", "405b"), "2024-11"),
        OllamaLibraryModel("llama3", "Meta's Llama 3 (8B, 70B) with strong reasoning", "12M", listOf("latest", "8b", "70b"), "2024-07"),
        OllamaLibraryModel("mistral", "Mistral 7B with strong reasoning and instruction following", "4.1M", listOf("latest", "7b"), "2024-10"),
        OllamaLibraryModel("mistral-nemo", "Mistral Nemo 12B - multilingual and code", "1.2M", listOf("latest", "12b"), "2024-11"),
        OllamaLibraryModel("mistral-small", "Mistral Small 22B - efficient mid-size model", "850K", listOf("latest", "22b"), "2024-11"),
        OllamaLibraryModel("mixtral", "Mistral's MoE (8x7B, 8x22B)", "2.5M", listOf("latest", "8x7b", "8x22b"), "2024-09"),
        OllamaLibraryModel("codellama", "Meta's Code Llama for code generation", "3.2M", listOf("latest", "7b", "13b", "34b"), "2024-09"),
        OllamaLibraryModel("deepseek-coder", "DeepSeek Coder (1.3B to 33B)", "1.8M", listOf("latest", "1.3b", "6.7b", "33b"), "2024-10"),
        OllamaLibraryModel("deepseek-coder-v2", "DeepSeek Coder V2 16B MoE", "1.1M", listOf("latest", "16b", "236b"), "2024-11"),
        OllamaLibraryModel("deepseek-r1", "DeepSeek R1 reasoning (1.5B to 70B)", "2.4M", listOf("latest", "1.5b", "7b", "8b", "14b", "32b", "70b"), "2025-01"),
        OllamaLibraryModel("gemma2", "Google Gemma 2 (2B, 9B, 27B)", "2.1M", listOf("latest", "2b", "9b", "27b"), "2024-11"),
        OllamaLibraryModel("gemma3", "Google Gemma 3 multimodal (1B to 27B)", "1.5M", listOf("latest", "1b", "4b", "12b", "27b"), "2025-03"),
        OllamaLibraryModel("gemma", "Google Gemma original (2B, 7B)", "2.3M", listOf("latest", "2b", "7b"), "2024-06"),
        OllamaLibraryModel("phi3", "Microsoft Phi-3 (3.8B, 14B) vision variants", "3.8M", listOf("latest", "3.8b", "14b"), "2024-10"),
        OllamaLibraryModel("phi4", "Microsoft Phi-4 14B advanced reasoning", "1.1M", listOf("latest", "14b"), "2025-01"),
        OllamaLibraryModel("qwen2.5", "Alibaba Qwen 2.5 (0.5B to 72B)", "1.9M", listOf("latest", "0.5b", "1.5b", "3b", "7b", "14b", "32b", "72b"), "2024-11"),
        OllamaLibraryModel("qwen2.5-coder", "Qwen 2.5 Coder (0.5B to 32B)", "890K", listOf("latest", "0.5b", "1.5b", "3b", "7b", "14b", "32b"), "2024-11"),
        OllamaLibraryModel("qwen3", "Alibaba Qwen3 thinking models (0.6B to 32B)", "420K", listOf("latest", "0.6b", "1.7b", "4b", "8b", "14b", "32b"), "2025-04"),
        OllamaLibraryModel("qwen3moe", "Alibaba Qwen3 MoE (30B)", "210K", listOf("latest", "30b"), "2025-04"),
        OllamaLibraryModel("command-r", "Cohere Command R (35B) for RAG", "780K", listOf("latest", "35b"), "2024-10"),
        OllamaLibraryModel("command-r-plus", "Cohere Command R+ (104B)", "340K", listOf("latest", "104b"), "2024-11"),
        OllamaLibraryModel("aya-expanse", "Cohere Aya Expanse multilingual (8B, 32B)", "210K", listOf("latest", "8b", "32b"), "2025-02"),
        OllamaLibraryModel("llava", "LLaVA multimodal vision-language (7B-34B)", "2.7M", listOf("latest", "7b", "13b", "34b"), "2024-08"),
        OllamaLibraryModel("llava-llama3", "LLaVA with Llama 3 backbone (8B)", "890K", listOf("latest", "8b"), "2024-09"),
        OllamaLibraryModel("llava-phi3", "LLaVA with Phi-3 backbone (3.8B)", "560K", listOf("latest"), "2024-11"),
        OllamaLibraryModel("bakllava", "BakLLaVA 7B vision-language", "1.2M", listOf("latest", "7b"), "2024-07"),
        OllamaLibraryModel("minicpm-v", "MiniCPM-V multimodal vision (8B)", "520K", listOf("latest", "8b"), "2025-01"),
        OllamaLibraryModel("dolphin-mistral", "Dolphin uncensored Mistral (7B)", "520K", listOf("latest", "7b"), "2024-09"),
        OllamaLibraryModel("dolphin-llama3", "Dolphin uncensored Llama 3 (8B)", "480K", listOf("latest", "8b"), "2024-10"),
        OllamaLibraryModel("dolphin-phi", "Dolphin uncensored Phi (2.7B)", "720K", listOf("latest", "2.7b"), "2024-10"),
        OllamaLibraryModel("wizardlm2", "Microsoft WizardLM 2 (7B, 8x22B)", "310K", listOf("latest", "7b", "8x22b"), "2024-08"),
        OllamaLibraryModel("nomic-embed-text", "Nomic text embedding model for RAG", "980K", listOf("latest"), "2024-09"),
        OllamaLibraryModel("mxbai-embed-large", "Mixedbread embedding (334M params)", "380K", listOf("latest"), "2024-10"),
        OllamaLibraryModel("all-minilm", "MiniLM embedding (33M, lightweight)", "420K", listOf("latest", "33m", "12b"), "2024-08"),
        OllamaLibraryModel("snowflake-arctic-embed", "Snowflake Arctic Embed (109M to 335M)", "280K", listOf("latest", "22m", "33m", "137m", "335m"), "2024-11"),
        OllamaLibraryModel("falcon3", "TII Falcon 3 (1B to 10B)", "310K", listOf("latest", "1b", "3b", "7b", "10b"), "2025-02"),
        OllamaLibraryModel("stable-code", "Stability AI code model (3B)", "340K", listOf("latest", "3b"), "2024-08"),
        OllamaLibraryModel("starcoder2", "BigCode StarCoder2 (3B to 15B)", "230K", listOf("latest", "3b", "7b", "15b"), "2024-09"),
        OllamaLibraryModel("starcoder2-instruct", "StarCoder2 instruction-tuned 15B", "180K", listOf("latest", "15b"), "2024-09"),
        OllamaLibraryModel("granite3.1-dense", "IBM Granite 3.1 dense (2B to 8B)", "160K", listOf("latest", "2b", "8b"), "2025-01"),
        OllamaLibraryModel("granite3.1-moe", "IBM Granite 3.1 MoE (1B to 3B)", "120K", listOf("latest", "1b", "3b"), "2025-01"),
        OllamaLibraryModel("granite-code", "IBM Granite Code (3B to 34B)", "210K", listOf("latest", "3b", "8b", "20b", "34b"), "2024-11"),
        OllamaLibraryModel("openchat", "OpenChat 7B - open source chat", "340K", listOf("latest", "7b"), "2024-07"),
        OllamaLibraryModel("neural-chat", "Intel Neural Chat 7B", "230K", listOf("latest", "7b"), "2024-06"),
        OllamaLibraryModel("yi", "01.AI Yi (6B to 34B)", "510K", listOf("latest", "6b", "9b", "34b"), "2024-09"),
        OllamaLibraryModel("yi-coder", "01.AI Yi Coder (1.5B to 9B)", "210K", listOf("latest", "1.5b", "9b"), "2024-11"),
        OllamaLibraryModel("solar-pro", "Upstage Solar Pro (22B)", "180K", listOf("latest", "22b"), "2024-10"),
        OllamaLibraryModel("tulu3", "AllenAI Tulu 3 (8B, 70B)", "150K", listOf("latest", "8b", "70b"), "2025-01"),
        OllamaLibraryModel("olmo2", "AI2 OLMo 2 (7B to 13B)", "140K", listOf("latest", "7b", "13b"), "2025-02"),
        OllamaLibraryModel("smollm2", "HuggingFace SmolLM2 (135M to 1.7B)", "230K", listOf("latest", "135m", "360m", "1.7b"), "2025-02"),
        OllamaLibraryModel("smollm", "HuggingFace SmolLM original (135M to 1.7B)", "180K", listOf("latest", "135m", "360m", "1.7b"), "2024-09"),
        OllamaLibraryModel("hermes3", "Nous Hermes 3 Llama (8B to 405B)", "280K", listOf("latest", "8b", "70b", "405b"), "2024-11"),
        OllamaLibraryModel("llama3-groq-tool-use", "Llama 3 fine-tuned for tool use (8B, 70B)", "140K", listOf("latest", "8b", "70b"), "2024-09"),
        OllamaLibraryModel("reflection", "Reflection 70B - reflective reasoning model", "310K", listOf("latest", "70b"), "2024-11"),
        OllamaLibraryModel("nemotron-mini", "NVIDIA Nemotron Mini (4B)", "180K", listOf("latest", "4b"), "2024-10"),
        OllamaLibraryModel("nemotron", "NVIDIA Nemotron (51B)", "120K", listOf("latest", "51b"), "2024-10"),
        OllamaLibraryModel("marco-o1", "Alibaba Marco O1 reasoning (7B)", "90K", listOf("latest", "7b"), "2025-01"),
        OllamaLibraryModel("shieldgemma", "Google ShieldGemma safety classifier (2B-27B)", "80K", listOf("latest", "2b", "9b", "27b"), "2024-11"),
        OllamaLibraryModel("reader-lm", "Jina Reader LM (0.5B-0.5B)", "70K", listOf("latest", "0.5b"), "2024-12"),
        OllamaLibraryModel("deepseek-v2", "DeepSeek V2 MoE (16B)", "320K", listOf("latest", "16b", "236b"), "2024-09"),
        OllamaLibraryModel("mathstral", "Mistral Mathstral math (7B)", "90K", listOf("latest", "7b"), "2024-11"),
        OllamaLibraryModel("codestral", "Mistral Codestral (22B)", "170K", listOf("latest", "22b"), "2024-10"),
        OllamaLibraryModel("tinyllama", "TinyLlama 1.1B - lightweight", "1.5M", listOf("latest"), "2024-07"),
        OllamaLibraryModel("orca-mini", "Orca Mini (3B to 13B) fine-tuned", "890K", listOf("latest", "3b", "7b", "13b"), "2024-06"),
        OllamaLibraryModel("zephyr", "Fine-tuned Mistral 7B for helpful chat", "1.4M", listOf("latest", "7b"), "2024-08"),
        OllamaLibraryModel("stablelm2", "Stability AI StableLM2 (1.6B, 12B)", "650K", listOf("latest", "1.6b", "12b"), "2024-09"),
        OllamaLibraryModel("solar", "Upstage Solar 10.7B compact model", "430K", listOf("latest", "10.7b"), "2024-09"),
        OllamaLibraryModel("moondream", "Moondream vision model (0.5B-2B)", "310K", listOf("latest", "0.5b", "2b"), "2024-11"),
        OllamaLibraryModel("granite3-dense", "IBM Granite 3 dense (2B, 8B)", "150K", listOf("latest", "2b", "8b"), "2024-12"),
        OllamaLibraryModel("granite3-moe", "IBM Granite 3 MoE (1B, 3B)", "110K", listOf("latest", "1b", "3b"), "2024-12"),
        OllamaLibraryModel("opencoder", "OpenCoder 1.5B to 8B code model", "95K", listOf("latest", "1.5b", "8b"), "2025-02"),
        OllamaLibraryModel("qwen2", "Alibaba Qwen 2 (0.5B to 72B)", "1.1M", listOf("latest", "0.5b", "1.5b", "7b", "72b"), "2024-09"),
        OllamaLibraryModel("qwen2-math", "Qwen 2 Math (0.5B to 72B)", "320K", listOf("latest", "1.5b", "7b", "72b"), "2024-09"),
        OllamaLibraryModel("nous-hermes2", "Nous Hermes 2 with Yi/Solar backbones", "230K", listOf("latest", "10.7b", "34b"), "2024-09"),
        OllamaLibraryModel("sqlcoder", "Defog SQLCoder (7B, 15B)", "180K", listOf("latest", "7b", "15b"), "2024-08"),
        OllamaLibraryModel("duckdb-nsql", "NSQL for SQL generation (7B)", "120K", listOf("latest", "7b"), "2024-07"),
        OllamaLibraryModel("codegemma", "Google CodeGemma code model (2B, 7B)", "180K", listOf("latest", "2b", "7b"), "2024-08"),
        OllamaLibraryModel("codeqwen", "Qwen CodeQwen (1.8B)", "210K", listOf("latest", "1.8b"), "2024-09"),
        OllamaLibraryModel("internlm2", "Shanghai AI Lab InternLM2 (1.8B-20B)", "150K", listOf("latest", "1.8b", "7b", "20b"), "2024-10"),
        OllamaLibraryModel("dbrx", "Databricks DBRX MoE (132B)", "110K", listOf("latest"), "2024-08"),
        OllamaLibraryModel("llama2", "Meta's Llama 2 (7B to 70B)", "7.1M", listOf("latest", "7b", "13b", "70b"), "2023-12"),
        OllamaLibraryModel("llama2-uncensored", "Uncensored Llama 2 variants", "1.3M", listOf("latest", "7b", "70b"), "2024-01"),
        OllamaLibraryModel("vicuna", "Vicuna (7B, 13B, 33B)", "1.2M", listOf("latest", "7b", "13b", "33b"), "2024-01"),
        OllamaLibraryModel("orca2", "Microsoft Orca 2 (7B, 13B)", "560K", listOf("latest", "7b", "13b"), "2024-03"),
        OllamaLibraryModel("tinydolphin", "TinyDolphin 1.1B - ultra lightweight", "230K", listOf("latest"), "2024-05"),
        OllamaLibraryModel("wizardcoder", "WizardCoder 33B for code generation", "210K", listOf("latest", "33b"), "2024-02"),
    )

    private fun getCuratedModelDetail(modelName: String): OllamaLibraryModelDetail {
        val model = getCuratedLibrary().find { it.name.equals(modelName.trim(), ignoreCase = true) }
            ?: return OllamaLibraryModelDetail(
                name = modelName,
                description = "No details available for this model.",
                tags = listOf("latest"),
                sizes = mapOf(),
                pulls = 0,
                lastUpdated = "",
            )

        val sizes = when (model.name.lowercase()) {
            "llama3.2" -> mapOf("latest" to 2_000_000_000L, "1b" to 1_100_000_000L, "3b" to 2_000_000_000L)
            "llama3.1" -> mapOf("latest" to 4_900_000_000L, "8b" to 4_900_000_000L, "70b" to 40_000_000_000L, "405b" to 230_000_000_000L)
            "mistral" -> mapOf("latest" to 4_100_000_000L, "7b" to 4_100_000_000L)
            "mixtral" -> mapOf("latest" to 27_000_000_000L, "8x7b" to 27_000_000_000L, "8x22b" to 74_000_000_000L)
            "codellama" -> mapOf("latest" to 7_400_000_000L, "7b" to 4_000_000_000L, "13b" to 7_400_000_000L, "34b" to 19_000_000_000L)
            "deepseek-coder" -> mapOf("latest" to 19_000_000_000L, "1.3b" to 1_000_000_000L, "6.7b" to 3_800_000_000L, "33b" to 19_000_000_000L)
            "gemma2" -> mapOf("latest" to 5_400_000_000L, "2b" to 1_600_000_000L, "9b" to 5_400_000_000L, "27b" to 15_500_000_000L)
            "phi3" -> mapOf("latest" to 8_000_000_000L, "3.8b" to 2_300_000_000L, "14b" to 8_000_000_000L)
            "qwen2.5" -> mapOf(
                "latest" to 4_000_000_000L, "0.5b" to 350_000_000L, "1.5b" to 950_000_000L,
                "3b" to 1_800_000_000L, "7b" to 4_000_000_000L, "14b" to 8_000_000_000L,
                "32b" to 18_500_000_000L, "72b" to 41_000_000_000L
            )
            "llava" -> mapOf("latest" to 8_000_000_000L, "7b" to 4_500_000_000L, "13b" to 8_000_000_000L, "34b" to 20_000_000_000L)
            "bakllava" -> mapOf("latest" to 4_700_000_000L, "7b" to 4_700_000_000L)
            "nomic-embed-text" -> mapOf("latest" to 274_000_000L)
            "dolphin-phi" -> mapOf("latest" to 1_600_000_000L, "2.7b" to 1_600_000_000L)
            "tinyllama" -> mapOf("latest" to 670_000_000L)
            "orca-mini" -> mapOf("latest" to 4_000_000_000L, "3b" to 2_000_000_000L, "7b" to 4_000_000_000L, "13b" to 7_400_000_000L)
            "zephyr" -> mapOf("latest" to 4_100_000_000L, "7b" to 4_100_000_000L)
            "stablelm2" -> mapOf("latest" to 6_900_000_000L, "1.6b" to 990_000_000L, "12b" to 6_900_000_000L)
            "command-r" -> mapOf("latest" to 20_000_000_000L, "35b" to 20_000_000_000L)
            "gemma" -> mapOf("latest" to 4_500_000_000L, "2b" to 1_400_000_000L, "7b" to 4_500_000_000L)
            "solar" -> mapOf("latest" to 6_100_000_000L, "10.7b" to 6_100_000_000L)
            else -> model.tags.associateWith { 0L }
        }

        val pulls = when {
            model.pulls.endsWith("M") -> (model.pulls.dropLast(1).toDoubleOrNull()?.times(1_000_000))?.toLong() ?: 0L
            model.pulls.endsWith("B") -> (model.pulls.dropLast(1).toDoubleOrNull()?.times(1_000_000_000))?.toLong() ?: 0L
            model.pulls.endsWith("K") -> (model.pulls.dropLast(1).toDoubleOrNull()?.times(1_000))?.toLong() ?: 0L
            else -> model.pulls.toLongOrNull() ?: 0L
        }

        return OllamaLibraryModelDetail(
            name = model.name,
            description = model.description,
            tags = model.tags,
            sizes = sizes,
            pulls = pulls,
            lastUpdated = model.lastUpdated,
        )
    }
}
