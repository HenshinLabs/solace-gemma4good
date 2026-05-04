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
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder()
                .url("https://ollama.com/api/search?q=$encodedQuery")
                .get()
                .addHeader("Accept", "application/json")
                .build()
            val response = getClient().newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val models = try {
                    gson.fromJson(body, Array<OllamaLibraryModel>::class.java).toList()
                } catch (e: Exception) {
                    null
                }
                if (models.isNullOrEmpty()) {
                    filterCuratedLibrary(query)
                } else {
                    models
                }
            } else {
                filterCuratedLibrary(query)
            }
        }.recover {
            filterCuratedLibrary(query)
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
        OllamaLibraryModel("llama3.2", "Meta's latest lightweight Llama 3.2 models with 1B and 3B variants for efficient local inference", "5.2M", listOf("latest", "1b", "3b"), "2024-12"),
        OllamaLibraryModel("llama3.1", "Meta's flagship Llama 3.1 family with 8B, 70B and 405B parameter models", "8.3M", listOf("latest", "8b", "70b", "405b"), "2024-11"),
        OllamaLibraryModel("mistral", "Mistral AI's 7B model with strong reasoning and instruction following capabilities", "4.1M", listOf("latest", "7b"), "2024-10"),
        OllamaLibraryModel("mixtral", "Mistral AI's mixture-of-experts model, combining 8 expert models", "2.5M", listOf("latest", "8x7b", "8x22b"), "2024-09"),
        OllamaLibraryModel("codellama", "Meta's Code Llama family specialized for code generation and completion", "3.2M", listOf("latest", "7b", "13b", "34b"), "2024-09"),
        OllamaLibraryModel("deepseek-coder", "DeepSeek's coding model with strong code generation capabilities", "1.8M", listOf("latest", "1.3b", "6.7b", "33b"), "2024-10"),
        OllamaLibraryModel("gemma2", "Google's Gemma 2 models offering strong performance across diverse tasks", "2.1M", listOf("latest", "2b", "9b", "27b"), "2024-11"),
        OllamaLibraryModel("phi3", "Microsoft's Phi-3 small language models optimized for efficiency", "3.8M", listOf("latest", "3.8b", "14b"), "2024-10"),
        OllamaLibraryModel("qwen2.5", "Alibaba's Qwen 2.5 models with strong multilingual capabilities", "1.9M", listOf("latest", "0.5b", "1.5b", "3b", "7b", "14b", "32b", "72b"), "2024-11"),
        OllamaLibraryModel("llava", "LLaVA multimodal model combining vision and language understanding", "2.7M", listOf("latest", "7b", "13b", "34b"), "2024-08"),
        OllamaLibraryModel("bakllava", "BakLLaVA multimodal vision-language model for image understanding", "1.2M", listOf("latest", "7b"), "2024-07"),
        OllamaLibraryModel("nomic-embed-text", "Nomic's text embedding model optimized for semantic search and retrieval", "980K", listOf("latest"), "2024-09"),
        OllamaLibraryModel("dolphin-phi", "Dolphin uncensored fine-tuned model based on Phi", "720K", listOf("latest", "2.7b"), "2024-10"),
        OllamaLibraryModel("tinyllama", "TinyLlama compact 1.1B model for resource-constrained environments", "1.5M", listOf("latest"), "2024-07"),
        OllamaLibraryModel("orca-mini", "Small efficient models fine-tuned on Orca reasoning datasets", "890K", listOf("latest", "3b", "7b", "13b"), "2024-06"),
        OllamaLibraryModel("zephyr", "Fine-tuned Mistral 7B optimized for helpful, honest conversations", "1.4M", listOf("latest", "7b"), "2024-08"),
        OllamaLibraryModel("stablelm2", "Stability AI's stable language model for general-purpose tasks", "650K", listOf("latest", "1.6b", "12b"), "2024-09"),
        OllamaLibraryModel("command-r", "Cohere's Command R model optimized for retrieval-augmented generation", "780K", listOf("latest", "35b"), "2024-10"),
        OllamaLibraryModel("gemma", "Google's original Gemma models, compact and efficient", "2.3M", listOf("latest", "2b", "7b"), "2024-06"),
        OllamaLibraryModel("solar", "Upstage's Solar model with compact size and strong performance", "430K", listOf("latest", "10.7b"), "2024-09"),
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
