package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO untuk `GET /v1beta/models?key=...` — dipakai [com.kaneki.aicoder.data.remote.ModelCatalogService]
 * supaya daftar model Gemini tidak hardcode dan otomatis ikut model baru/pensiun
 * dari Google. Beda dari [InteractionRequest]/[InteractionResponse] yang khusus
 * endpoint generate content.
 */
@Serializable
data class GeminiModelListResponse(
    val models: List<GeminiModelEntry> = emptyList(),
    @SerialName("nextPageToken")
    val nextPageToken: String? = null
)

@Serializable
data class GeminiModelEntry(
    /** Format "models/gemini-xxx" — perlu di-strip prefix "models/" sebelum dipakai di request. */
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    @SerialName("inputTokenLimit")
    val inputTokenLimit: Int? = null,
    @SerialName("supportedGenerationMethods")
    val supportedGenerationMethods: List<String> = emptyList()
)
