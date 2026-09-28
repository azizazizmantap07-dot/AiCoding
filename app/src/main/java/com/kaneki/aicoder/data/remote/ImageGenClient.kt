package com.kaneki.aicoder.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Hasil generate gambar dari provider yang mendukung text-to-image.
 */
sealed interface ImageGenResult {
    data class Success(
        /** Base64 tanpa prefix data-URL. */
        val base64: String,
        val mimeType: String = "image/png"
    ) : ImageGenResult

    data class Error(val message: String) : ImageGenResult
}

/**
 * Client khusus text-to-image (bukan chat/completions).
 * - Cloudflare Workers AI: POST /ai/run/{model}
 * - Gemini Imagen: POST .../models/{model}:predict
 */
class ImageGenClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = HttpClient(Android) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    /** Tutup HttpClient supaya koneksi/thread tidak bocor saat ViewModel dibuang. */
    fun close() {
        http.close()
    }

    suspend fun generate(
        provider: AiProvider,
        apiKey: String,
        model: String,
        prompt: String
    ): ImageGenResult = withContext(Dispatchers.IO) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return@withContext ImageGenResult.Error("Prompt gambar kosong")
        when (provider) {
            AiProvider.CLOUDFLARE -> generateCloudflare(apiKey, model, trimmed)
            AiProvider.GEMINI -> generateGeminiImagen(apiKey, model, trimmed)
            else -> ImageGenResult.Error(
                "Generate gambar belum didukung untuk ${provider.displayName}. " +
                    "Pakai provider Cloudflare atau Gemini + model 🖼 Gambar."
            )
        }
    }

    private suspend fun generateCloudflare(
        rawKey: String,
        model: String,
        prompt: String
    ): ImageGenResult {
        val parsed = CloudflareCredential.parse(rawKey)
            ?: return ImageGenResult.Error(CloudflareCredential.FORMAT_ERROR)
        val modelId = CloudflareModelId.normalize(model) ?: model
        if (!modelId.startsWith("@cf/")) {
            return ImageGenResult.Error(
                "Model Cloudflare harus id @cf/... (sekarang: $modelId)"
            )
        }
        val endpoint =
            "https://api.cloudflare.com/client/v4/accounts/${parsed.accountId}/ai/run/$modelId"
        val body = buildJsonObject {
            put("prompt", prompt)
        }
        return try {
            val response = http.post {
                url(endpoint)
                header("Authorization", "Bearer ${parsed.apiToken}")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            // Baca sebagai BYTE, bukan teks: model Stable Diffusion di Workers AI membalas
            // dengan gambar mentah (JPEG/PNG), dan bodyAsText() akan merusak byte-nya.
            val bytes = response.bodyAsBytes()

            if (!response.status.isSuccess()) {
                val errText = bytes.toString(Charsets.UTF_8)
                return ImageGenResult.Error(
                    friendlyCfError(parseCfError(errText), response.status.value)
                        ?: "HTTP ${response.status.value}: ${errText.take(200)}"
                )
            }

            // 1) Respons gambar mentah (SDXL, SDXL-Lightning, DreamShaper, ...)
            sniffImageMime(bytes)?.let { mime ->
                return ImageGenResult.Success(
                    base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                    mimeType = mime
                )
            }

            // 2) Respons JSON berisi base64 (Flux, dll.)
            val text = bytes.toString(Charsets.UTF_8)
            extractCloudflareImage(text)
                ?: ImageGenResult.Error(
                    "Respons Cloudflare tidak berisi gambar (${bytes.size} byte, bukan JPEG/PNG/WebP/JSON gambar)."
                )
        } catch (e: Exception) {
            ImageGenResult.Error(e.message ?: "Gagal generate gambar (Cloudflare)")
        }
    }

    /** Kenali format gambar dari magic bytes; null kalau bukan gambar. */
    private fun sniffImageMime(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int) = b[i].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            else -> null
        }
    }

    /** Terjemahkan kode error Cloudflare yang umum jadi pesan yang bisa ditindaklanjuti. */
    private fun friendlyCfError(raw: String?, httpStatus: Int): String? {
        raw ?: return null
        return when {
            raw.contains("3018") ->
                "$raw\n\nIni error internal di sisi Cloudflare (bukan salah aplikasi/prompt). " +
                    "Coba kirim ulang, atau pindah ke model lain (mis. flux-1-schnell)."
            raw.contains("3016") || raw.contains("3010") ->
                "$raw\n\nPrompt/parameter ditolak model. Coba prompt yang lebih sederhana."
            httpStatus == 429 ->
                "$raw\n\nKena rate limit / kuota harian Workers AI. Tunggu sebentar lalu coba lagi."
            httpStatus == 401 || httpStatus == 403 ->
                "$raw\n\nToken ditolak. Pastikan API token punya izin Workers AI (Read/Edit) untuk Account ID ini."
            else -> raw
        }
    }

    private fun extractCloudflareImage(text: String): ImageGenResult.Success? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        val result = root["result"] ?: return null

        // NB: pakai `as?` — `.jsonObject` / `.jsonArray` melempar IllegalArgumentException
        // kalau tipenya beda, jadi tidak boleh dipanggil sebelum tipe elemen dipastikan.
        fun firstString(el: kotlinx.serialization.json.JsonElement?): String? =
            (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        when (result) {
            is JsonObject -> {
                // { result: { image: "base64..." } }
                firstString(result["image"])?.let { return ImageGenResult.Success(it, "image/png") }
                // { result: { images: ["base64..."] } }
                firstString((result["images"] as? JsonArray)?.firstOrNull())
                    ?.let { return ImageGenResult.Success(it, "image/png") }
            }
            is JsonArray -> {
                // { result: ["base64..."] }
                firstString(result.firstOrNull())?.let { return ImageGenResult.Success(it, "image/png") }
            }
            else -> Unit
        }
        return null
    }

    private fun parseCfError(text: String): String? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        val errors = root["errors"] as? JsonArray ?: return null
        val first = errors.firstOrNull() as? JsonObject ?: return null
        val msg = (first["message"] as? JsonPrimitive)?.contentOrNull
        val code = (first["code"] as? JsonPrimitive)?.contentOrNull
        return listOfNotNull(code?.let { "code $it" }, msg).joinToString(": ").ifBlank { null }
    }

    private suspend fun generateGeminiImagen(
        apiKey: String,
        model: String,
        prompt: String
    ): ImageGenResult {
        // Imagen memakai :predict, bukan generateContent
        val modelId = model.removePrefix("models/").ifBlank { "imagen-3.0-generate-002" }
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$modelId:predict?key=$apiKey"
        val body = buildJsonObject {
            put(
                "instances",
                JsonArray(listOf(buildJsonObject { put("prompt", prompt) }))
            )
            put(
                "parameters",
                buildJsonObject {
                    put("sampleCount", 1)
                }
            )
        }
        return try {
            val response = http.post {
                url(endpoint)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return ImageGenResult.Error(
                    "Gemini Imagen HTTP ${response.status.value}: ${text.take(220)}"
                )
            }
            extractGeminiImage(text)
                ?: ImageGenResult.Error("Respons Imagen tidak berisi gambar: ${text.take(180)}")
        } catch (e: Exception) {
            ImageGenResult.Error(e.message ?: "Gagal generate gambar (Gemini Imagen)")
        }
    }

    private fun extractGeminiImage(text: String): ImageGenResult.Success? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        val predictions = root["predictions"] as? JsonArray ?: return null
        val first = predictions.firstOrNull() as? JsonObject ?: return null
        val b64 = (first["bytesBase64Encoded"] as? JsonPrimitive)?.contentOrNull
            ?: (first["bytes_base64_encoded"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        val mime = (first["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "image/png"
        return ImageGenResult.Success(b64, mime)
    }
}
