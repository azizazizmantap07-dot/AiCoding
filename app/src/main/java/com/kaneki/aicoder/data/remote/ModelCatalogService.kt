package com.kaneki.aicoder.data.remote

import com.kaneki.aicoder.data.remote.dto.CloudflareModelSearchResponse
import com.kaneki.aicoder.data.remote.dto.GeminiModelListResponse
import com.kaneki.aicoder.data.remote.dto.ModelListResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Satu entri model siap-pakai untuk ditampilkan di [ModelPickerSheet], hasil fetch live. */
data class LiveModel(
    val id: String,
    val label: String,
    val isFree: Boolean,
    val description: String? = null,
    /**
     * Context window (input token limit) model ini, langsung dari API provider
     * (Gemini: `inputTokenLimit`; Groq/OpenRouter: `context_length`).
     * Null kalau provider tidak mengembalikan info ini — dipakai
     * [com.kaneki.aicoder.domain.engine.ContextBudgetManager] supaya indikator
     * token memakai limit ASLI model yang dipilih, bukan angka statis.
     */
    val contextLimit: Int? = null,
    /**
     * True jika model diperkirakan mendukung input gambar (vision/multimodal).
     * Dihitung heuristik dari id/deskripsi karena tidak semua provider
     * mengekspos flag resmi di endpoint /models.
     */
    val supportsVision: Boolean = false,
    /** True jika model text-to-image (Flux, Imagen, SDXL, dll.). */
    val supportsImageGen: Boolean = false,
    /** Kategori relevan untuk AI Coder (chat / coding / vision / gambar). */
    val purposes: Set<ModelPurpose> = emptySet()
)

sealed interface ModelCatalogResult {
    data class Success(val models: List<LiveModel>) : ModelCatalogResult
    data class Error(val message: String) : ModelCatalogResult
}

/**
 * Fetch daftar model LANGSUNG dari API tiap provider saat dibutuhkan (bukan preset
 * hardcode), lalu filter mana yang gratis. Ini dibuat karena model gratis — terutama
 * `:free` di OpenRouter — sering berubah/di-deprecate, jadi hardcode ID gampang basi.
 *
 * Filter "gratis" per provider (beda skema per provider, tidak bisa disamaratakan):
 * - Gemini: semua model dengan [GeminiModelEntry.supportedGenerationMethods] mengandung
 *   "generateContent" ditampilkan — API key Gemini free-tier sendiri yang membatasi
 *   lewat RPM, bukan lewat model tertentu yang dikunci di balik pembayaran.
 * - Groq: seluruhnya gratis di tier developer (dibatasi RPM/RPD, bukan model tertentu),
 *   jadi seluruh `/models` response ditampilkan.
 * - OpenRouter: HANYA yang datanya menunjukkan biaya nol (id berakhiran ":free"
 *   ATAU pricing.prompt == "0" dan pricing.completion == "0") — provider ini
 *   mencampur model gratis & berbayar dalam katalog yang sama.
 * - Mistral & Hugging Face: seluruh katalog ditampilkan (allFree=true) — akses diatur
 *   lewat kuota/tier akun, bukan model per-model yang dikunci di balik pembayaran.
 * - Cloudflare Workers AI: seluruh model dengan task "Text Generation" ditampilkan
 *   (semuanya masuk kuota 10.000 neuron/hari gratis, tidak ada pemisahan gratis/berbayar
 *   di level model) — pakai endpoint native `/ai/models/search`, BUKAN endpoint
 *   OpenAI-compatible (lihat [fetchCloudflareModels]).
 *
 * Hasil di-cache in-memory sederhana per (provider, apiKey) selama proses app hidup,
 * supaya buka-tutup ModelPickerSheet berulang tidak fetch ulang tiap kali — panggil
 * [invalidate] kalau user menekan tombol refresh manual.
 */
object ModelCatalogService {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    private val cache = mutableMapOf<String, List<LiveModel>>()

    private fun cacheKey(provider: AiProvider, apiKey: String) = "${provider.name}:${apiKey.take(12)}"

    suspend fun fetchFreeModels(
        provider: AiProvider,
        apiKey: String,
        forceRefresh: Boolean = false
    ): ModelCatalogResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ModelCatalogResult.Error("API key belum diisi")

        val key = cacheKey(provider, apiKey)
        if (!forceRefresh) {
            cache[key]?.let { return@withContext ModelCatalogResult.Success(it) }
        }

        try {
            val models = when (provider) {
                AiProvider.GEMINI -> fetchGeminiModels(apiKey)
                AiProvider.GROQ -> fetchOpenAiCompatModels(
                    "https://api.groq.com/openai/v1", apiKey, allFree = true
                )
                AiProvider.OPENROUTER -> fetchOpenAiCompatModels(
                    "https://openrouter.ai/api/v1", apiKey, allFree = false
                )
                AiProvider.MISTRAL -> fetchOpenAiCompatModels(
                    "https://api.mistral.ai/v1", apiKey, allFree = true
                )
                AiProvider.HUGGINGFACE -> fetchOpenAiCompatModels(
                    "https://router.huggingface.co/v1", apiKey, allFree = true
                )
                AiProvider.CLOUDFLARE -> {
                    val parsed = CloudflareCredential.parse(apiKey)
                        ?: throw IllegalArgumentException(CloudflareCredential.FORMAT_ERROR)
                    fetchCloudflareModels(parsed.accountId, parsed.apiToken)
                }
            }
            // 1) Buang embedding/TTS/dll.
            // 2) Isi contextLimit dari defaults jika API kosong.
            // 3) Klasifikasi chat/coding/vision + ranking + batasi jumlah
            //    agar picker hanya menampilkan sampel yang berguna.
            val prepared = ModelRelevance.filterAndRank(
                models = models
                    .filter { ModelDefaults.isChatCapable(it.id) }
                    .map { ModelDefaults.enrich(provider, it) },
                maxPerProvider = 24
            )
            if (prepared.isEmpty()) {
                return@withContext ModelCatalogResult.Error(
                    "Tidak ada model chat/coding/vision untuk ${provider.displayName} saat ini."
                )
            }
            cache[key] = prepared
            ModelCatalogResult.Success(prepared)
        } catch (e: Exception) {
            ModelCatalogResult.Error(e.message ?: "Gagal memuat daftar model")
        }
    }

    fun invalidate(provider: AiProvider, apiKey: String) {
        cache.remove(cacheKey(provider, apiKey))
    }

    private suspend fun fetchGeminiModels(apiKey: String): List<LiveModel> {
        val response = httpClient.get {
            url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} saat memuat model Gemini")
        }
        val body = response.body<GeminiModelListResponse>()
        return body.models
            .filter { "generateContent" in it.supportedGenerationMethods }
            .map { entry ->
                val id = entry.name.removePrefix("models/")
                LiveModel(
                    id = id,
                    label = entry.displayName ?: id,
                    isFree = true, // dibedakan lewat RPM oleh Google, bukan per-model
                    description = entry.description?.take(80),
                    contextLimit = entry.inputTokenLimit,
                    supportsVision = VisionCapability.guess(id, entry.description)
                )
            }
            .filter { ModelDefaults.isChatCapable(it.id) }
            .map { ModelDefaults.enrich(AiProvider.GEMINI, it) }
            .let { list ->
                val extra = listOf(
                    LiveModel(
                        id = "imagen-3.0-generate-002",
                        label = "Imagen 3 (generate gambar)",
                        isFree = true,
                        description = "🖼 Text-to-image Google Imagen 3",
                        supportsImageGen = true,
                        purposes = setOf(ModelPurpose.IMAGE_GEN)
                    ),
                    LiveModel(
                        id = "imagen-4.0-generate-001",
                        label = "Imagen 4 (generate gambar)",
                        isFree = true,
                        description = "🖼 Text-to-image Google Imagen 4",
                        supportsImageGen = true,
                        purposes = setOf(ModelPurpose.IMAGE_GEN)
                    )
                ).filter { e -> list.none { it.id == e.id || it.id.endsWith(e.id) } }
                (list + extra).sortedByDescending { it.id }
            }
    }

    /**
     * Dipakai untuk Groq (allFree=true, semua model ditampilkan) dan
     * OpenRouter (allFree=false, filter ketat berdasarkan pricing/id ":free").
     */
    private suspend fun fetchOpenAiCompatModels(
        baseUrl: String,
        apiKey: String,
        allFree: Boolean
    ): List<LiveModel> {
        val response = httpClient.get {
            url("$baseUrl/models")
            header("Authorization", "Bearer $apiKey")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value} saat memuat daftar model")
        }
        val body = response.body<ModelListResponse>()
        return body.data
            .filter { entry ->
                if (allFree) return@filter true
                val idLooksFree = entry.id.endsWith(":free")
                val pricingIsZero = entry.pricing != null &&
                    (entry.pricing.prompt == "0" || entry.pricing.prompt == "0.0") &&
                    (entry.pricing.completion == "0" || entry.pricing.completion == "0.0")
                idLooksFree || pricingIsZero
            }
            .map { entry ->
                LiveModel(
                    id = entry.id,
                    label = entry.name ?: entry.id,
                    isFree = true,
                    description = entry.contextLength?.let { "${it / 1000}K context" },
                    contextLimit = entry.contextLength,
                    supportsVision = VisionCapability.guess(entry.id, entry.name)
                )
            }
            .sortedBy { it.label }
    }

    /**
     * Cloudflare Workers AI TIDAK dipakai lewat [fetchOpenAiCompatModels] karena
     * endpoint listing model-nya bukan bergaya OpenAI (`/ai/models/search`, amplop
     * `{success, result: [...]}`, lihat [CloudflareModelSearchResponse]) — beda
     * dari [ChatCompletionRequest] di [OpenAiCompatApiClient] yang tetap dipanggil
     * ke endpoint OpenAI-compatible `/ai/v1/chat/completions` untuk chat itu sendiri.
     * Difilter ke task "Text Generation" saja supaya tidak ikut model embedding/TTS/
     * image yang tidak relevan untuk tool-calling loop di app ini.
     */
    /**
     * Cloudflare: prioritas endpoint OpenAI-compatible `/ai/v1/models` karena
     * mengembalikan id siap-pakai (`@cf/...`). Endpoint `/ai/models/search`
     * sering mengembalikan UUID internal yang TIDAK diterima oleh
     * `/ai/v1/chat/completions` (error 5007 "No such model").
     * Hasil digabung dengan daftar curated model text-generation yang masih
     * aktif, lalu UUID murni dibuang.
     */
    private suspend fun fetchCloudflareModels(accountId: String, apiToken: String): List<LiveModel> {
        val fromOpenAiCompat = runCatching {
            fetchOpenAiCompatModels(
                baseUrl = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1",
                apiKey = apiToken,
                allFree = true
            )
        }.getOrDefault(emptyList())

        val fromSearch = runCatching {
            val response = httpClient.get {
                url("https://api.cloudflare.com/client/v4/accounts/$accountId/ai/models/search")
                header("Authorization", "Bearer $apiToken")
                parameter("task", "Text Generation")
                parameter("per_page", 100)
            }
            if (!response.status.isSuccess()) return@runCatching emptyList()
            val body = response.body<CloudflareModelSearchResponse>()
            body.result.mapNotNull { entry ->
                val resolved = CloudflareModelId.resolve(entry.id, entry.name) ?: return@mapNotNull null
                LiveModel(
                    id = resolved,
                    label = entry.name?.takeIf { !CloudflareModelId.isUuid(it) } ?: resolved,
                    isFree = true,
                    description = entry.description?.take(80),
                    contextLimit = CloudflareModelId.contextLimitFor(resolved),
                    supportsVision = VisionCapability.guess(resolved, entry.description)
                )
            }
        }.getOrDefault(emptyList())

        val curated = CloudflareModelId.CURATED.map { (id, limit) ->
            LiveModel(
                id = id,
                label = id.removePrefix("@cf/"),
                isFree = true,
                description = "${limit / 1000}K context",
                contextLimit = limit,
                supportsVision = VisionCapability.guess(id)
            )
        }

        // Gabungkan: prioritaskan id valid @cf/, buang UUID, isi contextLimit
        val byId = linkedMapOf<String, LiveModel>()
        (curated + fromOpenAiCompat + fromSearch).forEach { model ->
            val id = CloudflareModelId.normalize(model.id) ?: return@forEach
            val existing = byId[id]
            val limit = model.contextLimit
                ?: CloudflareModelId.contextLimitFor(id)
                ?: existing?.contextLimit
            byId[id] = (existing ?: model).copy(
                id = id,
                contextLimit = limit,
                supportsVision = model.supportsVision || (existing?.supportsVision == true) ||
                    VisionCapability.guess(id)
            )
        }
        val models = byId.values.sortedBy { it.label }
        if (models.isEmpty()) {
            throw IllegalStateException(
                "Tidak ada model Cloudflare valid (@cf/...). Periksa Account ID dan token Workers AI."
            )
        }
        return models
    }
}

/**
 * Normalisasi ID model Cloudflare Workers AI.
 * Chat completions HANYA menerima id berbentuk `@cf/vendor/model` (atau
 * `@hf/...` untuk beberapa model). UUID dari `/ai/models/search` ditolak API
 * dengan error 5007 "No such model".
 */
object CloudflareModelId {
    private val uuidRegex = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    fun isUuid(value: String): Boolean = uuidRegex.matches(value.trim())

    /** Kembalikan id siap-pakai atau null jika tidak bisa dipakai di chat completions. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (isUuid(s)) return null
        if (s.startsWith("@cf/") || s.startsWith("@hf/")) return s
        // Beberapa listing mengembalikan "cf/meta/..." tanpa @
        if (s.startsWith("cf/") || s.startsWith("hf/")) return "@$s"
        // "meta/llama-..." → coba prefix @cf/
        if (s.contains("/") && !s.contains(" ")) return "@cf/$s"
        return null
    }

    fun resolve(id: String?, name: String?): String? =
        normalize(id) ?: normalize(name)

    fun contextLimitFor(modelId: String): Int? = CURATED[modelId]

    /**
     * Model text-generation yang diketahui masih aktif di Workers AI free tier.
     * Dipakai sebagai seed supaya picker tidak kosong / tidak penuh UUID.
     */
    val CURATED: Map<String, Int> = linkedMapOf(
        "@cf/meta/llama-3.3-70b-instruct-fp8-fast" to 24_000,
        "@cf/meta/llama-3.1-8b-instruct-fp8" to 32_000,
        "@cf/meta/llama-3.1-8b-instruct" to 32_000,
        "@cf/meta/llama-3.2-3b-instruct" to 32_000,
        "@cf/meta/llama-4-scout-17b-16e-instruct" to 131_000,
        "@cf/qwen/qwen2.5-coder-32b-instruct" to 32_000,
        "@cf/qwen/qwq-32b" to 32_000,
        "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b" to 80_000,
        "@cf/mistral/mistral-small-3.1-24b-instruct" to 128_000,
        "@cf/google/gemma-3-12b-it" to 32_000,
        "@cf/openai/gpt-oss-20b" to 128_000,
        "@cf/openai/gpt-oss-120b" to 128_000,
        // Text-to-image (context limit N/A — dipakai ImageGenClient, bukan chat)
        "@cf/black-forest-labs/flux-1-schnell" to 0,
        "@cf/stabilityai/stable-diffusion-xl-base-1.0" to 0,
        "@cf/bytedance/stable-diffusion-xl-lightning" to 0,
        "@cf/lykon/dreamshaper-8-lcm" to 0
    )
}



/**
 * Heuristik deteksi kemampuan vision dari id/deskripsi model.
 * Provider jarang mengekspos flag resmi di endpoint listing, jadi dipakai
 * kata kunci yang umum pada model multimodal.
 */
object VisionCapability {
    private val positiveKeywords = listOf(
        "vision", "vl", "pixtral", "llava", "gemini", "gpt-4o", "gpt-4.1",
        "gpt-5", "claude-3", "claude-4", "sonnet", "haiku", "opus",
        "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "internvl", "phi-3.5-vision",
        "phi-4-multimodal", "llama-3.2-11b", "llama-3.2-90b", "llama-4",
        "gemma-3", "mistral-small", "multimodal", "image"
    )
    private val negativeKeywords = listOf(
        "embedding", "embed", "tts", "whisper", "asr", "rerank",
        "moderation", "code-gecko", "aqa", "imagen", "veo"
    )

    fun guess(id: String, description: String? = null): Boolean {
        val hay = (id + " " + (description ?: "")).lowercase()
        if (negativeKeywords.any { it in hay }) return false
        // Hampir semua model Gemini generateContent modern mendukung gambar
        if (hay.contains("gemini") && "embed" !in hay) return true
        return positiveKeywords.any { it in hay }
    }
}
