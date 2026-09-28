package com.kaneki.aicoder.data.remote

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validasi kredensial Cloudflare Workers AI. Raw key format `ACCOUNT_ID:API_TOKEN`
 * (lihat [CloudflareCredential]).
 *
 * Endpoint OpenAI-compatible (`.../ai/v1`) tidak punya GET /models — dipakai
 * native Workers AI:
 *
 *   GET /accounts/{id}/ai/models/search?per_page=1
 *
 * Membuktikan Account ID + token + permission Workers AI sekaligus.
 */
class CloudflareKeyValidator {

    suspend fun validate(rawKey: String): ApiKeyValidationResult = withContext(Dispatchers.IO) {
        if (rawKey.isBlank()) {
            return@withContext ApiKeyValidationResult.Invalid("API key tidak boleh kosong")
        }

        val parsed = CloudflareCredential.parse(rawKey)
            ?: return@withContext ApiKeyValidationResult.Invalid(CloudflareCredential.FORMAT_ERROR)

        if (!CloudflareCredential.looksLikeAccountId(parsed.accountId)) {
            return@withContext ApiKeyValidationResult.Invalid(
                "Account ID harus 32 karakter hex (huruf a-f dan angka), " +
                    "sedangkan yang terbaca ${parsed.accountId.length} karakter. " +
                    "Salin Account ID dari dashboard Cloudflare, bukan nama akun atau email."
            )
        }

        val client = GeminiKeyValidator.newValidationClient()
        try {
            val url =
                "https://api.cloudflare.com/client/v4/accounts/${parsed.accountId}" +
                    "/ai/models/search?per_page=1"
            val response: HttpResponse = client.get(url) {
                header("Authorization", "Bearer ${parsed.apiToken}")
                header("Accept", "application/json")
            }
            val code = response.status.value
            val body = runCatching { response.bodyAsText() }.getOrDefault("")

            when {
                response.status.isSuccess() -> ApiKeyValidationResult.Valid
                code == 401 ->
                    ApiKeyValidationResult.Invalid(
                        "API Token ditolak Cloudflare (HTTP 401). Pastikan token disalin utuh " +
                            "dan belum dihapus/kedaluwarsa. ${cfErrorSummary(body)}"
                    )
                code == 403 ->
                    ApiKeyValidationResult.Invalid(
                        "Token valid tapi tidak punya izin (HTTP 403). Edit token di dashboard " +
                            "Cloudflare, tambahkan permission \"Workers AI - Read\" dan " +
                            "\"Workers AI - Edit\", lalu pastikan Account Resources mencakup akun ini. " +
                            cfErrorSummary(body)
                    )
                code == 400 || code == 404 ->
                    ApiKeyValidationResult.Invalid(
                        "Account ID tidak cocok dengan token (HTTP $code). Periksa kembali " +
                            "Account ID-nya. ${cfErrorSummary(body)}"
                    )
                else ->
                    ApiKeyValidationResult.NetworkError(
                        "Cloudflare merespons dengan kode tidak terduga: $code. ${cfErrorSummary(body)}"
                    )
            }
        } catch (e: Exception) {
            ApiKeyValidationResult.NetworkError(e.message ?: "Gagal terhubung ke server Cloudflare")
        } finally {
            client.close()
        }
    }

    private fun cfErrorSummary(body: String): String {
        if (body.isBlank()) return ""
        val messages = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(body).map { it.groupValues[1] }.toList()
        return if (messages.isEmpty()) "" else "Detail: ${messages.first()}"
    }
}
