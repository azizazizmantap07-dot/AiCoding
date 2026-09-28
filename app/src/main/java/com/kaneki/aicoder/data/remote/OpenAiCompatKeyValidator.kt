package com.kaneki.aicoder.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validasi API key provider bergaya OpenAI (Groq, OpenRouter, Mistral, HF)
 * lewat GET `/models` + header Authorization Bearer — stack Ktor sama seperti client chat.
 */
class OpenAiCompatKeyValidator(private val baseUrl: String) {

    suspend fun validate(apiKey: String): ApiKeyValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ApiKeyValidationResult.Invalid("API key tidak boleh kosong")
        }

        val client = GeminiKeyValidator.newValidationClient()
        try {
            val response: HttpResponse = client.get("$baseUrl/models") {
                header("Authorization", "Bearer $apiKey")
            }
            val code = response.status.value
            when {
                response.status.isSuccess() -> ApiKeyValidationResult.Valid
                code == 400 || code == 401 || code == 403 ->
                    ApiKeyValidationResult.Invalid("API key tidak valid (HTTP $code)")
                else ->
                    ApiKeyValidationResult.NetworkError("Server merespons dengan kode tidak terduga: $code")
            }
        } catch (e: Exception) {
            ApiKeyValidationResult.NetworkError(e.message ?: "Gagal terhubung ke server")
        } finally {
            client.close()
        }
    }
}
