package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool declaration Interactions API.
 * [type] **tanpa default** di data class supaya selalu ikut di JSON
 * (encodeDefaults=false akan menghilangkan property yang == default).
 */
@Serializable
data class FunctionTool(
    val type: String,
    val name: String,
    val description: String,
    val parameters: ToolParametersSchema
)

@Serializable
data class ToolParametersSchema(
    val type: String,
    val properties: Map<String, ToolPropertySchema> = emptyMap(),
    val required: List<String> = emptyList()
)

@Serializable
data class ToolPropertySchema(
    val type: String,
    val description: String? = null
)

@Serializable
data class FunctionResultInput(
    val type: String,
    val name: String,
    @SerialName("call_id")
    val callId: String,
    val result: String
)
