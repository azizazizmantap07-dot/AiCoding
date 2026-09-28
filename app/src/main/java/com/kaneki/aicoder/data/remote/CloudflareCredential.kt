package com.kaneki.aicoder.data.remote

/**
 * Cloudflare Workers AI adalah satu-satunya provider di app ini yang butuh DUA
 * kredensial (Account ID + API Token) untuk membentuk base URL-nya sendiri:
 * `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1`.
 *
 * Supaya tidak perlu menambah field baru di [com.kaneki.aicoder.ui.settings.ApiKeySetupScreen]
 * (yang didesain generik: satu text field API key per provider, dipakai apa
 * adanya oleh [SecurePrefs] & [AiApiClientFactory]), user mengisi field
 * "API Key" untuk Cloudflare dengan format gabungan `ACCOUNT_ID:API_TOKEN`.
 * Parsing format ini dipusatkan di sini supaya [AiApiClientFactory],
 * [ApiKeyRepository], dan [ModelCatalogService] konsisten.
 */
object CloudflareCredential {

    data class Parsed(val accountId: String, val apiToken: String)

    /**
     * Bersihkan input hasil paste: buang SEMUA whitespace (spasi, newline, tab,
     * NBSP, zero-width) dan tanda kutip/bracket pembungkus yang sering ikut
     * tersalin dari dashboard, terminal, atau chat. Account ID dan API Token
     * Cloudflare tidak pernah mengandung karakter-karakter ini, jadi aman
     * dibuang total (bukan cuma trim ujung).
     */
    fun sanitize(raw: String): String =
        raw.replace(Regex("[\\s\\u00A0\\u200B-\\u200D\\u2060\\uFEFF\"'`<>{}\\[\\]]"), "")

    /** Null kalau format tidak sesuai `ACCOUNT_ID:API_TOKEN` (mis. tidak ada ":"). */
    fun parse(rawKey: String): Parsed? {
        val clean = sanitize(rawKey)
        val idx = clean.indexOf(':')
        if (idx <= 0 || idx == clean.length - 1) return null
        val accountId = clean.substring(0, idx)
        val apiToken = clean.substring(idx + 1)
        if (accountId.isBlank() || apiToken.isBlank()) return null
        return Parsed(accountId, apiToken)
    }

    /** Account ID Cloudflare = 32 karakter hex. */
    fun looksLikeAccountId(accountId: String): Boolean =
        accountId.length == 32 && accountId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun baseUrlFor(accountId: String): String =
        "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1"

    const val FORMAT_ERROR: String =
        "Format API Key Cloudflare salah. Isi dengan ACCOUNT_ID:API_TOKEN " +
            "(Account ID dari dashboard Cloudflare, dipisah titik dua dari API Token)."
}
