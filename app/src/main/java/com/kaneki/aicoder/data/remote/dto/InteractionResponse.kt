package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Response body Interactions API. Tahap 4 memproses step `function_call`
 * selain `model_output`.
 *
 * [arguments] dimodelkan sebagai JsonElement karena bisa object atau string
 * tergantung versi/response.
 */
@Serializable
data class InteractionResponse(
    val id: String? = null,
    val status: String? = null,
    val steps: List<InteractionStep> = emptyList(),
    val usage: InteractionUsage? = null
)

@Serializable
data class InteractionStep(
    val type: String? = null,
    val status: String? = null,
    val id: String? = null,
    val name: String? = null,
    /** Object args dari function_call; bisa null untuk step lain. */
    val arguments: JsonElement? = null,
    val content: List<InteractionContent> = emptyList()
)

@Serializable
data class InteractionContent(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class InteractionUsage(
    @SerialName("total_input_tokens")
    val totalInputTokens: Int? = null,
    @SerialName("total_output_tokens")
    val totalOutputTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

/**
 * Parse [InteractionStep.arguments] (JsonObject atau JsonPrimitive string) jadi Map.
 */
fun InteractionStep.parseArguments(): Map<String, Any?> {
    val el = arguments ?: return emptyMap()
    return when (el) {
        is JsonObject -> el.mapValues { (_, v) -> jsonElementToAny(v) }
        is JsonPrimitive -> {
            // Kadang arguments dikirim sebagai string JSON
            val raw = el.contentOrNull ?: return emptyMap()
            try {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                if (parsed is JsonObject) {
                    parsed.mapValues { (_, v) -> jsonElementToAny(v) }
                } else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
        else -> emptyMap()
    }
}

private fun jsonElementToAny(el: JsonElement): Any? = when (el) {
    is JsonPrimitive -> {
        when {
            el.isString -> el.content
            el.contentOrNull == "true" -> true
            el.contentOrNull == "false" -> false
            else -> el.contentOrNull?.toLongOrNull()
                ?: el.contentOrNull?.toDoubleOrNull()
                ?: el.content
        }
    }
    is JsonObject -> el.mapValues { (_, v) -> jsonElementToAny(v) }
    else -> el.toString()
}
