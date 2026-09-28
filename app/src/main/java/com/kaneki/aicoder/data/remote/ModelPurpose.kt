package com.kaneki.aicoder.data.remote

/**
 * Kategori model yang relevan untuk AI Coder.
 */
enum class ModelPurpose(val label: String, val emoji: String) {
    CHAT("Chat", "💬"),
    CODE("Coding", "💻"),
    VISION("Vision", "👁"),
    IMAGE_GEN("Gambar", "🖼");

    companion object {
        val SUPPORTED_IN_APP: Set<ModelPurpose> = setOf(CHAT, CODE, VISION, IMAGE_GEN)
    }
}

/**
 * Klasifikasi + penyaringan model: chat, coding, vision, dan generate gambar.
 */
object ModelRelevance {

    data class Classification(
        val purposes: Set<ModelPurpose>,
        val score: Int
    ) {
        val isUseful: Boolean
            get() = purposes.any { it in ModelPurpose.SUPPORTED_IN_APP }
    }

    fun classify(id: String, description: String? = null): Classification {
        val hay = (id + " " + (description ?: "")).lowercase()

        if (isBlocked(hay)) {
            return Classification(emptySet(), 0)
        }

        val purposes = linkedSetOf<ModelPurpose>()
        var score = 10

        if (isImageGen(hay)) {
            purposes += ModelPurpose.IMAGE_GEN
            score += 35
        }

        if (isVision(hay) && ModelPurpose.IMAGE_GEN !in purposes) {
            purposes += ModelPurpose.VISION
            score += 30
        }

        if (isCode(hay) && ModelPurpose.IMAGE_GEN !in purposes) {
            purposes += ModelPurpose.CODE
            score += 40
        }

        if (isChat(hay) && ModelPurpose.IMAGE_GEN !in purposes) {
            purposes += ModelPurpose.CHAT
            score += 25
        }

        if (purposes.isEmpty() && ModelDefaults.isChatCapable(id)) {
            purposes += ModelPurpose.CHAT
            score = 5
        }

        score += popularityBonus(hay)
        return Classification(purposes, score)
    }

    fun filterAndRank(
        models: List<LiveModel>,
        maxPerProvider: Int = 28
    ): List<LiveModel> {
        return models
            .map { model ->
                val c = classify(model.id, model.description)
                Triple(model, c, c.score)
            }
            .filter { (_, c, _) -> c.isUseful }
            .sortedByDescending { it.third }
            .distinctBy { it.first.id }
            .take(maxPerProvider)
            .map { (model, c, _) ->
                model.copy(
                    purposes = c.purposes,
                    supportsVision = model.supportsVision || ModelPurpose.VISION in c.purposes,
                    supportsImageGen = model.supportsImageGen || ModelPurpose.IMAGE_GEN in c.purposes,
                    description = buildDescription(model, c.purposes)
                )
            }
    }

    private fun buildDescription(model: LiveModel, purposes: Set<ModelPurpose>): String {
        val tags = purposes
            .filter { it in ModelPurpose.SUPPORTED_IN_APP }
            .joinToString(" · ") { "${it.emoji} ${it.label}" }
        val base = model.description?.substringBefore(" · ")?.take(60)
        return listOfNotNull(tags.ifBlank { null }, base).joinToString(" · ")
    }

    private fun isBlocked(hay: String): Boolean {
        // Jangan blok flux/sd/imagen — itu image gen yang didukung
        val blocked = listOf(
            "embed", "embedding", "text-embedding", "bge-", "e5-large", "e5-base",
            "whisper", "tts", "audio", "speech", "moderation", "rerank",
            "classify", "aqa", "code-gecko", "automatic-speech",
            "distance", "similarity", "worker-ai-image" // skip odd ones
        )
        return blocked.any { it in hay }
    }

    fun isImageGen(hay: String): Boolean {
        val keys = listOf(
            "flux", "stable-diffusion", "sdxl", "imagen", "dreamshaper",
            "text-to-image", "image-generation", "image_generation",
            "lightning", "dall-e", "dalle", "playground-v",
            "generate-002", "generate-001", "generate-image"
        )
        return keys.any { it in hay }
    }

    private fun isVision(hay: String): Boolean {
        val keys = listOf(
            "vision", "pixtral", "llava", "vl-", "-vl", "multimodal",
            "gpt-4o", "gpt-4.1", "gemma-3", "llama-4",
            "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "phi-4-multimodal",
            "phi-3.5-vision", "internvl", "moondream"
        )
        if ("gemini" in hay && "embed" !in hay && "imagen" !in hay) return true
        return keys.any { it in hay }
    }

    private fun isCode(hay: String): Boolean {
        val keys = listOf(
            "code", "coder", "codestral", "devstral", "codellama", "deepseek-coder",
            "qwen2.5-coder", "qwen3-coder", "starcoder", "wizardcoder",
            "codegemma", "granite-code", "opencoder", "kimi-k2"
        )
        val strongGeneral = listOf(
            "llama-3.3", "llama-3.1-70", "llama-4", "deepseek-r1", "deepseek-v3",
            "deepseek-chat", "gpt-oss", "mistral-large", "mistral-small",
            "claude", "gemini", "qwen3", "qwq"
        )
        return keys.any { it in hay } || strongGeneral.any { it in hay }
    }

    private fun isChat(hay: String): Boolean {
        val keys = listOf(
            "instruct", "chat", "it", "versatile", "turbo", "flash", "pro",
            "sonnet", "haiku", "opus", "small", "medium", "large", "mini",
            "gemini", "llama", "mistral", "gemma", "qwen", "phi-", "gpt",
            "deepseek", "nemo", "ministral", "scout", "maverick"
        )
        return keys.any { it in hay }
    }

    private fun popularityBonus(hay: String): Int {
        var b = 0
        if ("llama-3.3" in hay || "llama-3.1-70" in hay) b += 15
        if ("gemini-2.5" in hay || "gemini-2.0-flash" in hay || "gemini-flash" in hay) b += 20
        if ("codestral" in hay || "coder" in hay) b += 12
        if ("deepseek" in hay) b += 10
        if (":free" in hay) b += 5
        if ("fp8-fast" in hay || "instant" in hay) b += 8
        if ("flux" in hay || "imagen" in hay) b += 18
        if ("preview" in hay || "exp" in hay) b -= 5
        return b
    }
}
