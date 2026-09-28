package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTO untuk endpoint `/chat/completions` bergaya OpenAI, dipakai oleh Groq
 * (`api.groq.com/openai/v1`) dan OpenRouter (`openrouter.ai/api/v1`) — dua
 * provider ini sengaja meniru skema request/response OpenAI supaya kompatibel
 * dengan tooling yang sudah ada, jadi satu DTO set ini cukup untuk keduanya.
 *
 * Beda dari [InteractionRequest]/[InteractionResponse] milik Gemini:
 * - History dikirim sebagai array `messages` (role-based), bukan satu `input`.
 * - Tool declaration dibungkus `{"type":"function","function":{...}}`, bukan
 *   flat seperti [FunctionTool] milik Gemini.
 * - Tool call ada di `message.tool_calls[]`, argumen SELALU string JSON
 *   (bukan object), harus di-parse manual.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<OpenAiTool>? = null,
    val stream: Boolean? = null
)

/**
 * [content] memakai [JsonElement] agar mendukung:
 * - string teks biasa (JsonPrimitive)
 * - array multimodal vision: [{type:text,...},{type:image_url,...}]
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunctionDef
)

@Serializable
data class OpenAiFunctionDef(
    val name: String,
    val description: String,
    val parameters: ToolParametersSchema
)

@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCallBody
)

@Serializable
data class OpenAiFunctionCallBody(
    val name: String,
    /** Selalu string JSON mentah pada API bergaya OpenAI, bukan object. */
    val arguments: String
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatCompletionChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
    /** Groq/OpenRouter kadang balikin error di body 200 untuk beberapa kasus proxy. */
    val error: OpenAiErrorDetail? = null
)

@Serializable
data class ChatCompletionChoice(
    val index: Int? = null,
    val message: ChatMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiErrorDetail? = null
)

@Serializable
data class OpenAiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null
)

// ---------------------------------------------------------------------
// DTO untuk GET /models (dipakai ModelCatalogService untuk fetch daftar
// model secara dinamis, bukan hardcode preset). Groq balikin
// bentuk minimal (id, owned_by); OpenRouter tambah field pricing yang
// dipakai untuk deteksi otomatis model gratis. Mistral & Hugging Face
// juga balikin bentuk ini (amplop OpenAI standar `{"data": [...]}`)
// meski field pricing-nya tidak dipakai (allFree=true untuk keduanya).
// ---------------------------------------------------------------------

@Serializable
data class ModelListResponse(
    val data: List<ModelListEntry> = emptyList()
)

@Serializable
data class ModelListEntry(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null,
    /** Hanya diisi OpenRouter. Nilai "0" pada prompt & completion = model gratis. */
    val pricing: OpenRouterPricing? = null,
    /** Hanya diisi OpenRouter. Dipakai sebagai fallback label yang lebih rapi dari id mentah. */
    val name: String? = null,
    @SerialName("context_length")
    val contextLength: Int? = null
)

@Serializable
data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null
)
