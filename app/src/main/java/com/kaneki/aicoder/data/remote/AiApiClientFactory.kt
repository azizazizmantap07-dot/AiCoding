package com.kaneki.aicoder.data.remote

/**
 * Bikin [AiApiClient] yang tepat untuk [AiProvider] terpilih. Satu titik
 * masuk ini dipakai [com.kaneki.aicoder.ui.chat.ChatViewModel] supaya
 * pindah provider tidak perlu ubah apapun di [ToolCallingLoop].
 */
object AiApiClientFactory {

    fun create(provider: AiProvider): AiApiClient = when (provider) {
        AiProvider.GEMINI -> GeminiApiClient()
        AiProvider.GROQ -> OpenAiCompatApiClient(
            baseUrl = "https://api.groq.com/openai/v1"
        )
        AiProvider.OPENROUTER -> OpenAiCompatApiClient(
            baseUrl = "https://openrouter.ai/api/v1",
            // OpenRouter minta header ini untuk atribusi (tidak wajib untuk auth,
            // tapi direkomendasikan dokumentasi resminya).
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/azizazizmantap07-dot/casus",
                "X-Title" to "AI Coder"
            )
        )
        AiProvider.MISTRAL -> OpenAiCompatApiClient(
            baseUrl = "https://api.mistral.ai/v1"
        )
        AiProvider.HUGGINGFACE -> OpenAiCompatApiClient(
            baseUrl = "https://router.huggingface.co/v1"
        )
        AiProvider.CLOUDFLARE -> OpenAiCompatApiClient(
            // Base URL Cloudflare butuh Account ID yang disisipkan user di
            // dalam field API key (format ACCOUNT_ID:API_TOKEN) — lihat
            // [CloudflareCredential]. Di-resolve per-request, bukan sekali di
            // sini, karena AiApiClient dibuat oleh factory ini sebelum apiKey
            // tersimpan diketahui.
            resolveBaseUrl = { rawApiKey ->
                val parsed = CloudflareCredential.parse(rawApiKey)
                if (parsed == null) {
                    Result.failure(IllegalArgumentException(CloudflareCredential.FORMAT_ERROR))
                } else {
                    Result.success(
                        CloudflareCredential.baseUrlFor(parsed.accountId) to parsed.apiToken
                    )
                }
            }
        )
    }
}
