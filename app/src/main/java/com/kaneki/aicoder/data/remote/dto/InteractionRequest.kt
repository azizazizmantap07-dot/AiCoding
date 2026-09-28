package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Body POST /v1beta/interactions.
 * Catatan: Interactions API **tidak** mendukung explicit context cache
 * (`cached_content`) — jangan kirim field itu (akan 400).
 */
@Serializable
data class InteractionRequest(
    val model: String,
    val input: JsonElement,
    @SerialName("system_instruction")
    val systemInstruction: String? = null,
    val tools: List<FunctionTool>? = null,
    @SerialName("previous_interaction_id")
    val previousInteractionId: String? = null,
    val stream: Boolean? = null
)
