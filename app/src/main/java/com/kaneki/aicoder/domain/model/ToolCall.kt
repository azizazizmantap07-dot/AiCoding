package com.kaneki.aicoder.domain.model

/**
 * Satu function call dari model (Interactions API step type = "function_call").
 * [id] = call_id yang harus dikembalikan di function_result.
 * [arguments] sudah di-parse dari JSON object response.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

/**
 * Hasil eksekusi satu tool, siap dikirim balik ke model sebagai function_result.
 */
data class ToolExecutionResult(
    val callId: String,
    val name: String,
    val content: String
)
