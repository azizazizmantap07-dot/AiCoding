package com.kaneki.aicoder.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Bentuk response error standar Gemini REST API, dipakai
 * [com.kaneki.aicoder.data.remote.GeminiApiClient] untuk mengekstrak pesan
 * error yang manusiawi dari body 4xx/5xx, alih-alih hanya menampilkan kode
 * status HTTP mentah ke user.
 *
 * Dipindah dari GenerateResponse.kt (skema `generateContent` lama, sudah
 * dihapus saat migrasi ke Interactions API) ke file sendiri karena bentuk
 * `{"error": {"code", "message", "status"}}` ini generik milik REST API
 * Google secara umum, dipakai apa adanya oleh kedua skema (baik saat masih
 * `generateContent` maupun sekarang Interactions API) — Google belum
 * mendokumentasikan skema error terpisah untuk Interactions API.
 */
@Serializable
data class GeminiErrorResponse(
    val error: GeminiErrorDetail? = null
)

@Serializable
data class GeminiErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)
