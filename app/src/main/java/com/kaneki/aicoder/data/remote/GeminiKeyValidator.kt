package com.kaneki.aicoder.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ApiKeyValidationResult {
    data object Valid : ApiKeyValidationResult
    data class Invalid(val reason: String) : ApiKeyValidationResult
    data class NetworkError(val reason: String) : ApiKeyValidationResult
}

/**
 * Validasi API key Gemini lewat GET ke endpoint /v1/models.
 * Pakai Ktor (sama seperti [GeminiApiClient]) supaya stack HTTP seragam.
 */
class GeminiKeyValidator {

    suspend fun validate(apiKey: String): ApiKeyValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ApiKeyValidationResult.Invalid("API key tidak boleh kosong")
        }

        val client = newValidationClient()
        try {
            val encodedKey = java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
            val response: HttpResponse = client.get("$BASE_URL/models?key=$encodedKey")
            val code = response.status.value
            when {
                response.status.isSuccess() -> ApiKeyValidationResult.Valid
                code == 400 || code == 401 || code == 403 ->
                    ApiKeyValidationResult.Invalid("API key tidak valid (HTTP $code)")
                else ->
                    ApiKeyValidationResult.NetworkError("Gemini merespons dengan kode tidak terduga: $code")
            }
        } catch (e: Exception) {
            ApiKeyValidationResult.NetworkError(e.message ?: "Gagal terhubung ke server Gemini")
        } finally {
            client.close()
        }
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1"

        internal fun newValidationClient(): HttpClient = HttpClient(Android) {
            // Jangan expectSuccess — kita baca status sendiri
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }
}
