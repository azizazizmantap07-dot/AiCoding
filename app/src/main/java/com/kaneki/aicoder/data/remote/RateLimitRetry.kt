package com.kaneki.aicoder.data.remote

import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Free tier Gemini sering 429 (mis. 5 RPM). Parse "retry in Ns" dari pesan error
 * dan tunggu sebelum mencoba lagi.
 */
object RateLimitRetry {

    // Free tier sering cuma 5 RPM (khususnya model *-pro). Percobaan ditambah
    // dan wait dasar dinaikkan supaya app tidak menembak ulang sebelum jendela
    // 1 menit benar-benar reset di sisi Google.
    const val MAX_ATTEMPTS = 8
    private const val DEFAULT_WAIT_MS = 20_000L
    private const val MAX_WAIT_MS = 90_000L

    /** Detik dari pesan seperti "Please retry in 7s" / "retry in 12 seconds". */
    fun parseRetryAfterSeconds(message: String): Long? {
        val patterns = listOf(
            Regex("""retry in\s+(\d+)\s*s""", RegexOption.IGNORE_CASE),
            Regex("""retry after\s+(\d+)\s*s""", RegexOption.IGNORE_CASE),
            Regex("""in\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(message) ?: continue
            return m.groupValues[1].toLongOrNull()
        }
        return null
    }


    /**
     * Pesan ramah untuk HTTP 429 (free tier). Dipakai GeminiApiClient dan
     * OpenAiCompatApiClient supaya wording tidak drift.
     *
     * @param providerHint teks singkat provider (mis. "Gemini", "Groq") — opsional
     */
    fun formatUserFacingError(code: Int, raw: String, providerHint: String? = null): String {
        if (code != 429) return raw
        val sec = parseRetryAfterSeconds(raw)
        return buildString {
            if (!providerHint.isNullOrBlank()) {
                append("Batas free tier $providerHint tercapai (429). ")
            } else {
                append("Batas free tier tercapai (429). ")
            }
            if (sec != null) append("Coba lagi dalam ~${sec}s. ")
            else append("Tunggu sebentar lalu kirim ulang. ")
            append("Aplikasi akan auto-retry otomatis.")
        }
    }

    fun waitMsForAttempt(attempt: Int, errorMessage: String): Long {
        val fromMsg = parseRetryAfterSeconds(errorMessage)?.times(1000)
        if (fromMsg != null) return min(fromMsg + 2000, MAX_WAIT_MS) // +2s buffer, Google kadang meleset
        // exponential: 20s, 40s, 80s, 90s(cap)…
        val exp = DEFAULT_WAIT_MS * (1L shl (attempt - 1).coerceAtMost(3))
        return min(exp, MAX_WAIT_MS)
    }

    suspend fun <T> withRetry(
        isRateLimited: (T) -> Boolean,
        errorMessage: (T) -> String,
        onWaiting: (secondsLeft: Int, attempt: Int) -> Unit = { _, _ -> },
        block: suspend () -> T
    ): T {
        var last: T? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = block()
            last = result
            if (!isRateLimited(result)) return result
            if (attempt == MAX_ATTEMPTS) return result
            val waitMs = waitMsForAttempt(attempt, errorMessage(result))
            val totalSec = ((waitMs + 999) / 1000).toInt()
            var remaining = totalSec
            while (remaining > 0) {
                onWaiting(remaining, attempt)
                delay(1000)
                remaining--
            }
        }
        return last!!
    }
}
