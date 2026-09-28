package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO untuk `GET /accounts/{account_id}/ai/models/search` milik Cloudflare
 * Workers AI — dipakai [com.kaneki.aicoder.data.remote.ModelCatalogService].
 *
 * BEDA dari [ModelListResponse] (dipakai Groq/OpenRouter/Mistral/
 * Hugging Face): ini bukan endpoint OpenAI-compatible, melainkan REST API
 * native Cloudflare dengan amplop `{success, result: [...]}`, bukan
 * `{data: [...]}`. Endpoint OpenAI-compatible Cloudflare (`/ai/v1/models`)
 * TIDAK dipakai di sini karena hanya balikin id model tanpa `task.name`,
 * sehingga tidak bisa dipakai untuk filter task Text Generation seperti
 * dilakukan [fetchCloudflareModels].
 */
@Serializable
data class CloudflareModelSearchResponse(
    val success: Boolean = false,
    val result: List<CloudflareModelEntry> = emptyList()
)

@Serializable
data class CloudflareModelEntry(
    /** Format "@cf/vendor/model-name", dipakai langsung sebagai model id di request chat. */
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val task: CloudflareModelTask? = null
)

@Serializable
data class CloudflareModelTask(
    val name: String? = null
)
