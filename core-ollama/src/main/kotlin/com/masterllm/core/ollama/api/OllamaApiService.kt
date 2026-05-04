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
}
