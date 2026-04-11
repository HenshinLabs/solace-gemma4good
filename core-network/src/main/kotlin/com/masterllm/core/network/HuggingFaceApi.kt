package com.masterllm.core.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Hugging Face Hub API service.
 * Full endpoints will be added in Phase 3.
 */
interface HuggingFaceApi {

    @GET("api/models")
    suspend fun searchModels(
        @Query("search") query: String,
        @Query("limit") limit: Int = 20,
    ): List<Any> // Will be replaced with proper response models
}
