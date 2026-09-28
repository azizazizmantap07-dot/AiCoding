package com.kaneki.aicoder.data.remote

import com.kaneki.aicoder.data.remote.dto.ChatCompletionRequest
import com.kaneki.aicoder.data.remote.dto.ChatCompletionResponse
import com.kaneki.aicoder.data.remote.dto.ChatMessage
import com.kaneki.aicoder.data.remote.dto.FunctionTool
import com.kaneki.aicoder.data.remote.dto.OpenAiErrorResponse
import com.kaneki.aicoder.data.remote.dto.OpenAiFunctionCallBody
import com.kaneki.aicoder.data.remote.dto.OpenAiFunctionDef
import com.kaneki.aicoder.data.remote.dto.OpenAiTool
import com.kaneki.aicoder.data.remote.dto.OpenAiToolCall
import com.kaneki.aicoder.domain.model.ChatTurn
import com.kaneki.aicoder.domain.model.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client generik untuk provider bergaya OpenAI `/chat/completions`
 * (Groq, OpenRouter, Mistral, Hugging Face, Cloudflare Workers AI).
 * Streaming server-sent-events belum diimplementasi di sini (selalu non-stream,
 * sama seperti [GeminiApiClient] yang juga sengaja "stream=false" untuk hemat
 * request di free tier) — [onTextChunk] hanya dipanggil sekali dengan teks
 * penuh setelah response selesai.
 *
 * Tidak menyimpan `previous_interaction_id` seperti Gemini Interactions API;
 * provider ini stateless, jadi seluruh riwayat dikirim ulang tiap request
 * sebagai `messages` (perilaku standar chat/completions).
 *
 * @param baseUrl dipakai apa adanya untuk provider dengan base URL tetap
 *   (Groq/OpenRouter/Mistral/Hugging Face). Abaikan (boleh string
 *   kosong) kalau [resolveBaseUrl] diisi.
 * @param resolveBaseUrl override opsional untuk provider yang base URL-nya
 *   bergantung pada isi API key, KHUSUS Cloudflare Workers AI yang perlu
 *   Account ID dari [CloudflareCredential] untuk membentuk URL
 *   `.../accounts/{account_id}/ai/v1`. Menerima raw apiKey yang disimpan user,
 *   mengembalikan base URL + API token murni yang dipakai sebagai Bearer.
 */
class OpenAiCompatApiClient(
    private val baseUrl: String = "",
    /** Header tambahan di luar Authorization, mis. OpenRouter minta HTTP-Referer. */
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val resolveBaseUrl: ((rawApiKey: String) -> Result<Pair<String, String>>)? = null
) : AiApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun generateWithTools(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        tools: List<FunctionTool>,
        systemPrompt: String?,
        previousInteractionId: String?,
        onRateLimitWait: (secondsLeft: Int, attempt: Int) -> Unit
    ): GenerateWithToolsResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GenerateWithToolsResult.ApiError(0, "API key kosong")
        if (history.isEmpty()) return@withContext GenerateWithToolsResult.ApiError(0, "History kosong")

        RateLimitRetry.withRetry(
            isRateLimited = { it is GenerateWithToolsResult.ApiError && it.httpCode == 429 },
            errorMessage = { (it as? GenerateWithToolsResult.ApiError)?.message.orEmpty() },
            onWaiting = onRateLimitWait
        ) {
            executeOnce(apiKey, model, history, tools, systemPrompt, onTextChunk = {})
        }
    }

    override suspend fun generateWithToolsStreaming(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        tools: List<FunctionTool>,
        systemPrompt: String?,
        previousInteractionId: String?,
        onTextChunk: (String) -> Unit,
        onToolHint: (String) -> Unit,
        onRateLimitWait: (secondsLeft: Int, attempt: Int) -> Unit
    ): GenerateWithToolsResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GenerateWithToolsResult.ApiError(0, "API key kosong")
        if (history.isEmpty()) return@withContext GenerateWithToolsResult.ApiError(0, "History kosong")

        RateLimitRetry.withRetry(
            isRateLimited = { it is GenerateWithToolsResult.ApiError && it.httpCode == 429 },
            errorMessage = { (it as? GenerateWithToolsResult.ApiError)?.message.orEmpty() },
            onWaiting = onRateLimitWait
        ) {
            executeOnce(apiKey, model, history, tools, systemPrompt, onTextChunk)
        }
    }

    private suspend fun executeOnce(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        tools: List<FunctionTool>,
        systemPrompt: String?,
        onTextChunk: (String) -> Unit
    ): GenerateWithToolsResult {
        val (effectiveBaseUrl, effectiveApiKey) = if (resolveBaseUrl != null) {
            val resolved = resolveBaseUrl.invoke(apiKey)
            val (resolvedUrl, resolvedKey) = resolved.getOrElse {
                return GenerateWithToolsResult.ApiError(0, it.message ?: "Kredensial tidak valid")
            }
            resolvedUrl to resolvedKey
        } else {
            baseUrl to apiKey
        }

        // Cloudflare Workers AI menolak UUID / id tanpa prefix @cf/ (error 5007).
        // Normalisasi hanya saat resolveBaseUrl dipakai (khusus Cloudflare).
        val effectiveModel = if (resolveBaseUrl != null) {
            CloudflareModelId.normalize(model) ?: model
        } else {
            model
        }

        val messages = buildMessages(history, systemPrompt)
        val requestBody = ChatCompletionRequest(
            model = effectiveModel,
            messages = messages,
            tools = tools.map { toOpenAiTool(it) }.ifEmpty { null }
        )

        return try {
            val response = httpClient.post {
                url("$effectiveBaseUrl/chat/completions")
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $effectiveApiKey")
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                return GenerateWithToolsResult.ApiError(
                    response.status.value,
                    RateLimitRetry.formatUserFacingError(response.status.value, parseErrorMessage(response))
                )
            }

            val body = response.body<ChatCompletionResponse>()
            val choice = body.choices.firstOrNull()
            val message = choice?.message

            if (body.error != null) {
                return GenerateWithToolsResult.ApiError(
                    0,
                    body.error.message ?: "Error tidak diketahui dari provider"
                )
            }

            val text = jsonElementToText(message?.content)
            val toolCalls = extractToolCalls(message?.toolCalls)

            if (text.isBlank() && toolCalls.isEmpty()) {
                GenerateWithToolsResult.EmptyResponse(choice?.finishReason)
            } else {
                if (text.isNotBlank()) onTextChunk(text)
                GenerateWithToolsResult.Success(
                    text = text,
                    toolCalls = toolCalls,
                    interactionId = null, // provider stateless, tidak ada previous_interaction_id
                    totalTokenCount = body.usage?.totalTokens
                )
            }
        } catch (e: Exception) {
            GenerateWithToolsResult.NetworkError(e.message ?: "Gagal terhubung")
        }
    }

    /**
     * Konversi [ChatTurn] history internal (dirancang untuk model stateful
     * Gemini) menjadi array `messages` role-based standar OpenAI.
     */
    private fun buildMessages(history: List<ChatTurn>, systemPrompt: String?): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(ChatMessage(role = "system", content = JsonPrimitive(systemPrompt)))
        }
        // Simpan pemetaan call_id -> name supaya tool result tahu nama function-nya
        // saat menyusun message role="tool" (field `name` wajib di beberapa provider).
        val callIdToName = mutableMapOf<String, String>()

        history.forEach { turn ->
            when (turn) {
                is ChatTurn.User -> messages.add(
                    ChatMessage(role = "user", content = buildUserContent(turn))
                )
                is ChatTurn.Assistant -> {
                    val toolCalls = turn.toolCalls.map { call ->
                        callIdToName[call.id] = call.name
                        OpenAiToolCall(
                            id = call.id,
                            function = OpenAiFunctionCallBody(
                                name = call.name,
                                arguments = argumentsToJsonString(call.arguments)
                            )
                        )
                    }
                    messages.add(
                        ChatMessage(
                            role = "assistant",
                            content = if (turn.text.isBlank()) null else JsonPrimitive(turn.text),
                            toolCalls = toolCalls.ifEmpty { null }
                        )
                    )
                }
                is ChatTurn.ToolResults -> {
                    turn.results.forEach { result ->
                        messages.add(
                            ChatMessage(
                                role = "tool",
                                content = JsonPrimitive(result.content),
                                toolCallId = result.callId,
                                name = callIdToName[result.callId] ?: result.name
                            )
                        )
                    }
                }
            }
        }
        return messages
    }

    private fun argumentsToJsonString(args: Map<String, Any?>): String {
        val obj = buildJsonObject {
            args.forEach { (k, v) ->
                when (v) {
                    null -> put(k, kotlinx.serialization.json.JsonNull)
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is Double -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        return obj.toString()
    }

    private fun toOpenAiTool(tool: FunctionTool): OpenAiTool = OpenAiTool(
        type = "function",
        function = OpenAiFunctionDef(
            name = tool.name,
            description = tool.description,
            parameters = tool.parameters
        )
    )

    private fun extractToolCalls(calls: List<OpenAiToolCall>?): List<ToolCall> {
        if (calls.isNullOrEmpty()) return emptyList()
        return calls.map { call ->
            val argsMap = runCatching {
                val parsed = json.parseToJsonElement(call.function.arguments)
                if (parsed is JsonObject) {
                    parsed.mapValues { (_, v) -> jsonElementToAnySimple(v) }
                } else emptyMap()
            }.getOrDefault(emptyMap())
            ToolCall(id = call.id, name = call.function.name, arguments = argsMap)
        }
    }

    private fun jsonElementToAnySimple(el: kotlinx.serialization.json.JsonElement): Any? =
        when (el) {
            is kotlinx.serialization.json.JsonPrimitive -> el.content
            else -> el.toString()
        }


    private suspend fun parseErrorMessage(response: HttpResponse): String {
        val raw = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return "HTTP ${response.status.value}"
        // Amplop OpenAI-style
        val parsed = runCatching { json.decodeFromString<OpenAiErrorResponse>(raw) }.getOrNull()
        val openAiMsg = parsed?.error?.message
        if (!openAiMsg.isNullOrBlank()) return openAiMsg
        // Amplop Cloudflare: {"success":false,"errors":[{"message":"...","code":5007}]}
        val cfMsg = runCatching {
            val root = json.parseToJsonElement(raw)
            val errors = (root as? JsonObject)?.get("errors") as? JsonArray
            errors?.mapNotNull { el ->
                (el as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }
            }?.filter { it.isNotBlank() }?.joinToString("; ")
        }.getOrNull()
        if (!cfMsg.isNullOrBlank()) {
            return if ("No such model" in cfMsg) {
                "$cfMsg — pilih ulang model dari daftar (harus id @cf/...), atau tekan refresh di pemilih model."
            } else cfMsg
        }
        return when {
            raw.length <= 400 -> raw
            else -> raw.take(400) + "…"
        }
    }


    private fun buildUserContent(user: ChatTurn.User): JsonElement {
        if (user.images.isEmpty()) {
            return JsonPrimitive(user.text)
        }
        return buildJsonArray {
            val prompt = user.text.ifBlank { "Tolong analisis gambar ini." }
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                }
            )
            user.images.forEach { img ->
                val dataUrl = "data:${img.mimeType};base64,${img.base64}"
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject {
                                put("url", dataUrl)
                            }
                        )
                    }
                )
            }
        }
    }

    private fun jsonElementToText(el: JsonElement?): String {
        if (el == null || el is JsonNull) return ""
        return when (el) {
            is JsonPrimitive -> el.content
            is JsonArray -> el.mapNotNull { item ->
                if (item is JsonObject) {
                    val type = (item["type"] as? JsonPrimitive)?.content
                    if (type == "text") (item["text"] as? JsonPrimitive)?.content else null
                } else null
            }.joinToString("")
            else -> el.toString()
        }
    }

    override fun close() {
        httpClient.close()
    }
}
