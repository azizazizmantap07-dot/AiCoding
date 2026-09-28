package com.kaneki.aicoder.data.remote

import com.kaneki.aicoder.data.remote.dto.FunctionTool
import com.kaneki.aicoder.data.remote.dto.GeminiErrorResponse
import com.kaneki.aicoder.data.remote.dto.InteractionRequest
import com.kaneki.aicoder.data.remote.dto.InteractionResponse
import com.kaneki.aicoder.data.remote.dto.parseArguments
import com.kaneki.aicoder.domain.model.ChatTurn
import com.kaneki.aicoder.domain.model.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface GenerateWithToolsResult {
    data class Success(
        val text: String,
        val toolCalls: List<ToolCall>,
        val interactionId: String?,
        val totalTokenCount: Int?
    ) : GenerateWithToolsResult

    data class ApiError(val httpCode: Int, val message: String) : GenerateWithToolsResult
    data class NetworkError(val reason: String) : GenerateWithToolsResult
    data class EmptyResponse(val finishReason: String?) : GenerateWithToolsResult
}

class GeminiApiClient : AiApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
    }

    /**
     * @param onRateLimitWait dipanggil tiap detik saat menunggu 429 (untuk UI).
     */
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
            errorMessage = {
                (it as? GenerateWithToolsResult.ApiError)?.message.orEmpty()
            },
            onWaiting = onRateLimitWait
        ) {
            executeOnce(
                apiKey = apiKey,
                model = model,
                history = history,
                tools = tools,
                systemPrompt = systemPrompt,
                previousInteractionId = previousInteractionId,
                stream = false,
                onTextChunk = {},
                onToolHint = {}
            )
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

        // Free tier: jangan coba stream lalu fallback (itu = 2 request).
        // Langsung non-stream + retry 429. Streaming opsional lewat path khusus nanti.
        RateLimitRetry.withRetry(
            isRateLimited = { it is GenerateWithToolsResult.ApiError && it.httpCode == 429 },
            errorMessage = {
                (it as? GenerateWithToolsResult.ApiError)?.message.orEmpty()
            },
            onWaiting = onRateLimitWait
        ) {
            executeOnce(
                apiKey = apiKey,
                model = model,
                history = history,
                tools = tools,
                systemPrompt = systemPrompt,
                previousInteractionId = previousInteractionId,
                stream = false,
                onTextChunk = onTextChunk,
                onToolHint = onToolHint
            )
        }
    }

    private suspend fun executeOnce(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        tools: List<FunctionTool>,
        systemPrompt: String?,
        previousInteractionId: String?,
        stream: Boolean,
        onTextChunk: (String) -> Unit,
        onToolHint: (String) -> Unit
    ): GenerateWithToolsResult {
        val requestBody = InteractionRequest(
            model = model,
            input = buildInputElement(history, previousInteractionId),
            systemInstruction = if (previousInteractionId == null) systemPrompt else null,
            tools = tools,
            previousInteractionId = previousInteractionId,
            stream = if (stream) true else null
        )
        return try {
            val response = httpClient.preparePost {
                url("$BASE_URL/interactions")
                contentType(ContentType.Application.Json)
                header(HEADER_API_KEY, apiKey)
                setBody(requestBody)
            }.execute()

            if (!response.status.isSuccess()) {
                return GenerateWithToolsResult.ApiError(
                    response.status.value,
                    RateLimitRetry.formatUserFacingError(response.status.value, parseErrorMessage(response), "Gemini")
                )
            }

            if (stream) {
                parseStreamingBody(response, onTextChunk, onToolHint)
            } else {
                val body = response.body<InteractionResponse>()
                val text = extractModelText(body).orEmpty()
                val toolCalls = extractToolCalls(body)
                if (text.isEmpty() && toolCalls.isEmpty()) {
                    GenerateWithToolsResult.EmptyResponse(body.status)
                } else {
                    if (text.isNotBlank()) onTextChunk(text)
                    GenerateWithToolsResult.Success(
                        text, toolCalls, body.id, body.usage?.totalTokens
                    )
                }
            }
        } catch (e: Exception) {
            GenerateWithToolsResult.NetworkError(e.message ?: "Gagal terhubung")
        }
    }

    private suspend fun parseStreamingBody(
        response: HttpResponse,
        onTextChunk: (String) -> Unit,
        onToolHint: (String) -> Unit
    ): GenerateWithToolsResult {
        val parser = StreamingParser(json)
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            // readLine (bukan readUTF8Line yang deprecated). Lenient = CR/LF/CRLF, sama seperti perilaku lama;
            // baris terakhir tanpa newline tetap terbaca (penting untuk stream SSE).
            val line = channel.readLine(LineEnding.Lenient) ?: break
            for (ev in parser.feedLine(line)) {
                when (ev) {
                    is StreamingParser.StreamEvent.TextDelta -> onTextChunk(ev.chunk)
                    is StreamingParser.StreamEvent.ToolActivity -> onToolHint(ev.name)
                    is StreamingParser.StreamEvent.Completed -> {}
                }
            }
        }
        val snap = parser.finish()
        return if (snap.text.isEmpty() && snap.toolCalls.isEmpty()) {
            GenerateWithToolsResult.EmptyResponse("empty_stream")
        } else {
            GenerateWithToolsResult.Success(
                snap.text, snap.toolCalls, snap.interactionId, snap.totalTokenCount
            )
        }
    }

    override fun close() {
        httpClient.close()
    }

    private fun buildInputElement(
        history: List<ChatTurn>,
        previousInteractionId: String?
    ): JsonElement {
        val last = history.lastOrNull()

        if (previousInteractionId != null && last is ChatTurn.ToolResults) {
            return buildJsonArray {
                last.results.forEach { result ->
                    add(
                        buildJsonObject {
                            put("type", "function_result")
                            put("name", result.name)
                            put("call_id", result.callId)
                            put("result", result.content)
                        }
                    )
                }
            }
        }

        if (previousInteractionId != null && last is ChatTurn.User) {
            return buildUserInput(last)
        }

        if (last is ChatTurn.User) {
            return buildUserInput(last)
        }

        val text = history.joinToString("\n\n") { turn ->
            when (turn) {
                is ChatTurn.User -> "User: ${turn.text}"
                is ChatTurn.Assistant -> "Model: ${turn.text}"
                is ChatTurn.ToolResults ->
                    "Tool results:\n" + turn.results.joinToString("\n") {
                        "- ${it.name}: ${it.content.take(400)}"
                    }
            }
        }
        return JsonPrimitive(text)
    }

    /**
     * Teks saja → string primitif (kompatibel request lama).
     * Ada gambar → array content multimodal (text + image base64).
     */
    private fun buildUserInput(user: ChatTurn.User): JsonElement {
        if (user.images.isEmpty()) {
            return JsonPrimitive(user.text)
        }
        return buildJsonArray {
            if (user.text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", user.text)
                    }
                )
            }
            user.images.forEach { img ->
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("mime_type", img.mimeType)
                        put("data", img.base64)
                    }
                )
            }
            // Jika hanya gambar tanpa teks, tetap sertakan petunjuk singkat
            if (user.text.isBlank()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "Tolong analisis gambar ini.")
                    }
                )
            }
        }
    }


    private suspend fun parseErrorMessage(response: HttpResponse): String {
        val raw = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return "Gemini HTTP ${response.status.value}"
        val parsed = runCatching { json.decodeFromString<GeminiErrorResponse>(raw) }.getOrNull()
        val msg = parsed?.error?.message
        return when {
            !msg.isNullOrBlank() -> msg
            raw.length <= 400 -> raw
            else -> raw.take(400) + "…"
        }
    }

    private fun extractModelText(body: InteractionResponse): String? {
        return body.steps
            .lastOrNull { it.type == "model_output" }
            ?.content
            ?.filter { it.type == "text" }
            ?.joinToString("") { it.text.orEmpty() }
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractToolCalls(body: InteractionResponse): List<ToolCall> {
        return body.steps
            .filter { it.type == "function_call" }
            .mapNotNull { step ->
                val name = step.name ?: return@mapNotNull null
                val id = step.id ?: "call_${name}_${System.currentTimeMillis()}"
                ToolCall(id = id, name = name, arguments = step.parseArguments())
            }
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val HEADER_API_KEY = "x-goog-api-key"
    }
}
