package com.kaneki.aicoder.data.remote

/**
 * Cadangan metadata model lintas provider:
 * - [contextLimitFor]: limit konteks jika API tidak mengembalikan context_length
 * - [isChatCapable]: saring model embedding/TTS/moderation agar tidak dipilih
 *   lalu gagal di endpoint chat/completions (error mirip "No such model").
 *
 * Nilai curated bersifat best-effort; API live tetap diprioritaskan.
 */
object ModelDefaults {

    fun contextLimitFor(provider: AiProvider, modelId: String): Int? {
        val id = modelId.trim()
        if (id.isEmpty()) return null
        // Exact match
        providerLimits(provider)[id]?.let { return it }
        // Prefix / contains match untuk varian (preview, dated, :free)
        providerLimits(provider).entries
            .firstOrNull { (key, _) -> id == key || id.startsWith("$key-") || id.startsWith("$key:") || id.contains(key) }
            ?.value
            ?.let { return it }
        // Heuristik generik berdasarkan nama
        return heuristicLimit(id)
    }

    fun isChatCapable(modelId: String): Boolean {
        val id = modelId.lowercase()
        // Image-gen diizinkan lewat katalog (akan ditandai IMAGE_GEN + ImageGenClient)
        if (ModelRelevance.isImageGen(id)) return true
        val blocked = listOf(
            "embed", "embedding", "whisper", "tts", "audio", "moderation",
            "rerank", "classify", "vision-only", "image-to-",
            "veo", "aqa", "code-gecko", "text-embedding",
            "bge-", "e5-", "clip", "llava-next-video"
        )
        if ("llava" in id || "vision" in id || "pixtral" in id) {
            return true
        }
        return blocked.none { it in id }
    }

    fun enrich(provider: AiProvider, model: LiveModel): LiveModel {
        val limit = model.contextLimit?.takeIf { it > 0 }
            ?: contextLimitFor(provider, model.id)
        return if (limit != null && model.contextLimit != limit) {
            model.copy(
                contextLimit = limit,
                description = model.description
                    ?: "${limit / 1000}K context"
            )
        } else if (model.contextLimit == null && limit != null) {
            model.copy(contextLimit = limit, description = model.description ?: "${limit / 1000}K context")
        } else {
            model
        }
    }

    private fun providerLimits(provider: AiProvider): Map<String, Int> = when (provider) {
        AiProvider.GEMINI -> GEMINI
        AiProvider.GROQ -> GROQ
        AiProvider.OPENROUTER -> OPENROUTER
        AiProvider.MISTRAL -> MISTRAL
        AiProvider.CLOUDFLARE -> CLOUDFLARE
        AiProvider.HUGGINGFACE -> HUGGINGFACE
    }

    private fun heuristicLimit(id: String): Int? {
        val lower = id.lowercase()
        return when {
            "1m" in lower || "1000000" in lower -> 1_000_000
            "gemini" in lower && "flash" in lower -> 1_000_000
            "gemini" in lower && "pro" in lower -> 1_000_000
            "gemini" in lower -> 128_000
            "llama-3.3" in lower || "llama3.3" in lower -> 128_000
            "llama-3.1" in lower || "llama3.1" in lower -> 128_000
            "llama-3.2" in lower -> 128_000
            "llama-4" in lower -> 128_000
            "gpt-oss" in lower -> 128_000
            "qwen3" in lower || "qwen2.5" in lower -> 32_000
            "deepseek" in lower -> 64_000
            "mistral" in lower || "ministral" in lower || "codestral" in lower -> 32_000
            "gemma" in lower -> 32_000
            "phi-4" in lower || "phi-3" in lower -> 16_000
            else -> null
        }
    }

    private val GEMINI = mapOf(
        "gemini-2.5-flash" to 1_048_576,
        "gemini-2.5-pro" to 1_048_576,
        "gemini-2.0-flash" to 1_048_576,
        "gemini-2.0-flash-lite" to 1_048_576,
        "gemini-1.5-flash" to 1_048_576,
        "gemini-1.5-pro" to 2_097_152,
        "gemini-flash-latest" to 1_048_576,
        "gemini-pro-latest" to 1_048_576,
        "gemini-3.5-flash" to 1_048_576,
        "gemini-3-flash" to 1_048_576,
        "gemini-3-pro" to 1_048_576
    )

    private val GROQ = mapOf(
        "llama-3.3-70b-versatile" to 128_000,
        "llama-3.1-8b-instant" to 128_000,
        "llama-3.1-70b-versatile" to 128_000,
        "llama3-70b-8192" to 8_192,
        "llama3-8b-8192" to 8_192,
        "gemma2-9b-it" to 8_192,
        "mixtral-8x7b-32768" to 32_768,
        "qwen/qwen3-32b" to 32_768,
        "moonshotai/kimi-k2-instruct" to 131_072,
        "openai/gpt-oss-120b" to 128_000,
        "openai/gpt-oss-20b" to 128_000,
        "meta-llama/llama-4-scout-17b-16e-instruct" to 131_072,
        "meta-llama/llama-4-maverick-17b-128e-instruct" to 131_072
    )

    private val OPENROUTER = mapOf(
        "meta-llama/llama-3.3-70b-instruct:free" to 128_000,
        "meta-llama/llama-3.1-8b-instruct:free" to 128_000,
        "google/gemma-3-27b-it:free" to 32_000,
        "google/gemma-3-12b-it:free" to 32_000,
        "qwen/qwen3-8b:free" to 32_000,
        "qwen/qwen3-14b:free" to 32_000,
        "deepseek/deepseek-r1:free" to 64_000,
        "deepseek/deepseek-chat-v3-0324:free" to 64_000,
        "mistralai/mistral-small-3.1-24b-instruct:free" to 32_000,
        "nvidia/llama-3.1-nemotron-ultra-253b-v1:free" to 128_000
    )

    private val MISTRAL = mapOf(
        "mistral-small-latest" to 32_768,
        "mistral-medium-latest" to 128_000,
        "mistral-large-latest" to 128_000,
        "ministral-8b-latest" to 128_000,
        "ministral-3b-latest" to 128_000,
        "codestral-latest" to 32_768,
        "open-mistral-nemo" to 128_000,
        "pixtral-12b-2409" to 128_000,
        "pixtral-large-latest" to 128_000
    )

    private val CLOUDFLARE = CloudflareModelId.CURATED

    private val HUGGINGFACE = mapOf(
        "meta-llama/Llama-3.3-70B-Instruct" to 128_000,
        "meta-llama/Llama-3.1-8B-Instruct" to 128_000,
        "Qwen/Qwen2.5-72B-Instruct" to 32_768,
        "Qwen/Qwen2.5-Coder-32B-Instruct" to 32_768,
        "deepseek-ai/DeepSeek-R1" to 64_000,
        "mistralai/Mistral-7B-Instruct-v0.3" to 32_768,
        "mistralai/Mistral-Small-24B-Instruct-2501" to 32_768,
        "google/gemma-3-27b-it" to 32_000
    )
}
