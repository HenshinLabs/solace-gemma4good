package com.masterllm.core.network.model

import com.google.gson.annotations.SerializedName

/**
 * HF Hub model search / info response.
 */
data class HfModelResponse(
    @SerializedName("modelId") val modelId: String? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("author") val author: String? = null,
    @SerializedName("sha") val sha: String? = null,
    @SerializedName("downloads") val downloads: Int = 0,
    @SerializedName("likes") val likes: Int = 0,
    @SerializedName("tags") val tags: List<String>? = null,
    @SerializedName("pipeline_tag") val pipelineTag: String? = null,
    @SerializedName("siblings") val siblings: List<HfSiblingResponse>? = null,
    @SerializedName("lastModified") val lastModified: String? = null,
    @SerializedName("private") val isPrivate: Boolean = false,
    @SerializedName("description") val description: String? = null,
    @SerializedName("cardData") val cardData: Map<String, Any>? = null,
)

/**
 * A sibling file within an HF repo.
 */
data class HfSiblingResponse(
    @SerializedName("rfilename") val rfilename: String = "",
    @SerializedName("size") val size: Long? = null,
)

/**
 * whoami response for token validation.
 */
data class HfWhoamiResponse(
    @SerializedName("name") val name: String? = null,
    @SerializedName("fullname") val fullname: String? = null,
    @SerializedName("avatarUrl") val avatarUrl: String? = null,
    @SerializedName("type") val type: String? = null,
)
