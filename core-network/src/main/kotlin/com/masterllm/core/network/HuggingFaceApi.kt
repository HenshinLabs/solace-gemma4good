package com.masterllm.core.network

import com.masterllm.core.network.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Hugging Face Hub API service.
 */
interface HuggingFaceApi {

    /** Search models by query.
     *  @param full When true, returns full model info including siblings and config.
     *  @param config When true, returns model config card data.
     */
    @GET("api/models")
    suspend fun searchModels(
        @Query("search") query: String,
        @Query("filter") filter: String? = null,
        @Query("sort") sort: String = "downloads",
        @Query("direction") direction: String = "-1",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("full") full: Boolean = true,
        @Query("config") config: Boolean = true,
    ): List<HfModelResponse>

    /** Get model info by repo ID (e.g., "TheBloke/Llama-2-7B-GGUF").
     *  @param full When true, returns full model info including siblings and config.
     */
    @GET("api/models/{repoId}")
    suspend fun getModelInfo(
        @Path("repoId", encoded = true) repoId: String,
        @Query("full") full: Boolean = true,
        @Query("config") config: Boolean = true,
    ): HfModelResponse

    /** Preferred token validation endpoint. */
    @GET("api/whoami-v2")
    suspend fun whoamiV2(
        @Header("Authorization") auth: String,
    ): HfWhoamiResponse

    /** Validate token & get user profile. */
    @GET("api/whoami")
    suspend fun whoami(
        @Header("Authorization") auth: String,
    ): HfWhoamiResponse

    /** Download a file from a repo – streamed. */
    @Streaming
    @GET("{repoId}/resolve/main/{fileName}")
    suspend fun downloadFile(
        @Path("repoId", encoded = true) repoId: String,
        @Path("fileName", encoded = true) fileName: String,
        @Header("Authorization") auth: String? = null,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>
}
