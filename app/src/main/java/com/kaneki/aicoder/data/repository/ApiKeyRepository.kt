package com.kaneki.aicoder.data.repository

import com.kaneki.aicoder.data.local.SecurePrefs
import com.kaneki.aicoder.data.remote.AiProvider
import com.kaneki.aicoder.data.remote.ApiKeyValidationResult
import com.kaneki.aicoder.data.remote.CloudflareCredential
import com.kaneki.aicoder.data.remote.CloudflareKeyValidator
import com.kaneki.aicoder.data.remote.GeminiKeyValidator
import com.kaneki.aicoder.data.remote.OpenAiCompatKeyValidator

class ApiKeyRepository(
    private val securePrefs: SecurePrefs,
    private val geminiValidator: GeminiKeyValidator = GeminiKeyValidator(),
    private val cloudflareValidator: CloudflareKeyValidator = CloudflareKeyValidator()
) {

    fun hasStoredKey(provider: AiProvider): Boolean = securePrefs.hasApiKey(provider)

    /**
     * Validasi dulu ke provider terkait, baru simpan kalau valid (Bagian 5.3
     * blueprint asli, sekarang berlaku untuk provider manapun) — larangan
     * eksplisit: "Jangan menghapus atau melewati langkah validasi API key".
     */
    suspend fun validateAndSave(provider: AiProvider, apiKey: String): ApiKeyValidationResult {
        // Cloudflare: bersihkan spasi/newline/zero-width/kutip hasil paste SEBELUM
        // divalidasi dan disimpan, supaya key yang tersimpan sama persis dengan yang
        // tervalidasi (karakter tak terlihat adalah penyebab umum "login gagal padahal
        // key benar"). Provider lain cukup di-trim biasa.
        val trimmedKey = if (provider == AiProvider.CLOUDFLARE) {
            CloudflareCredential.sanitize(apiKey)
        } else {
            apiKey.trim()
        }
        val result = when (provider) {
            AiProvider.GEMINI -> geminiValidator.validate(trimmedKey)
            AiProvider.GROQ -> OpenAiCompatKeyValidator("https://api.groq.com/openai/v1")
                .validate(trimmedKey)
            AiProvider.OPENROUTER -> OpenAiCompatKeyValidator("https://openrouter.ai/api/v1")
                .validate(trimmedKey)
            AiProvider.MISTRAL -> OpenAiCompatKeyValidator("https://api.mistral.ai/v1")
                .validate(trimmedKey)
            AiProvider.HUGGINGFACE -> OpenAiCompatKeyValidator("https://router.huggingface.co/v1")
                .validate(trimmedKey)
            // Cloudflare butuh parsing ACCOUNT_ID:API_TOKEN dulu untuk tahu base
            // URL-nya sendiri, makanya pakai validator terpisah, bukan
            // OpenAiCompatKeyValidator(baseUrl tetap) seperti provider lain di atas.
            AiProvider.CLOUDFLARE -> cloudflareValidator.validate(trimmedKey)
        }
        if (result is ApiKeyValidationResult.Valid) {
            securePrefs.saveApiKey(provider, trimmedKey)
        }
        return result
    }

    fun clearKey(provider: AiProvider) = securePrefs.clearApiKey(provider)
}

