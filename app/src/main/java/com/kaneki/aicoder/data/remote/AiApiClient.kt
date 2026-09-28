package com.kaneki.aicoder.data.remote

import com.kaneki.aicoder.data.remote.dto.FunctionTool
import com.kaneki.aicoder.domain.model.ChatTurn

/**
 * Kontrak seragam untuk semua provider (Gemini, Groq, OpenRouter, dst).
 * [ToolCallingLoop] hanya bergantung ke interface ini, sehingga menambah
 * provider baru tidak perlu mengubah logic loop / retry / self-throttle.
 *
 * [GenerateWithToolsResult] (sudah ada, awalnya khusus Gemini) dipakai apa
 * adanya sebagai return type generik karena bentuknya sudah cukup umum
 * (Success/ApiError/NetworkError/EmptyResponse) untuk merepresentasikan
 * hasil dari provider manapun.
 */
interface AiApiClient {

    suspend fun generateWithTools(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        tools: List<FunctionTool>,
        systemPrompt: String?,
        previousInteractionId: String? = null,
        onRateLimitWait: (secondsLeft: Int, attempt: Int) -> Unit = { _, _ -> }
    ): GenerateWithToolsResult

    suspend fun generateWithToolsStreaming(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        tools: List<FunctionTool>,
        systemPrompt: String?,
        previousInteractionId: String? = null,
        onTextChunk: (String) -> Unit = {},
        onToolHint: (String) -> Unit = {},
        onRateLimitWait: (secondsLeft: Int, attempt: Int) -> Unit = { _, _ -> }
    ): GenerateWithToolsResult

    fun close()
}
