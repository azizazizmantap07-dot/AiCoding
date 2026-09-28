package com.kaneki.aicoder.data.remote

/**
 * Penyedia AI yang didukung untuk chat + tool-calling. Ditambahkan supaya
 * user tidak terkunci di satu free-tier Gemini yang RPM-nya ketat (mis.
 * gemini-3.1-pro-preview ~5 RPM) — Groq, OpenRouter, Mistral,
 * Cloudflare Workers AI & Hugging Face masing-masing punya trade-off
 * free-tier berbeda dan cocok untuk tool-calling loop yang memicu banyak
 * request berurutan ("hardcore" / edit project besar).
 *
 * Setiap provider bisa beda format request/response & auth, makanya
 * abstraksi client ada di [AiApiClient] — file ini hanya metadata untuk UI
 * (pemilih provider, link "dapatkan API key gratis"). Daftar model TIDAK
 * di sini lagi — itu selalu di-fetch live oleh [ModelCatalogService].
 *
 * Catatan auth khusus [CLOUDFLARE]: satu-satunya provider di sini yang butuh
 * DUA kredensial (Account ID + API Token), bukan cuma satu API key seperti
 * provider lain. Supaya tidak perlu nambah field baru di layar pengaturan
 * (yang didesain generik satu-textfield-per-provider), field "API Key" untuk
 * Cloudflare diisi dengan format gabungan `ACCOUNT_ID:API_TOKEN` — lihat
 * parsing-nya di [AiApiClientFactory], [ApiKeyRepository], dan
 * [ModelCatalogService].
 */
enum class AiProvider(
    val displayName: String,
    val shortDescription: String,
    /** Halaman resmi untuk membuat API key gratis milik provider ini. */
    val getKeyUrl: String,
    /** Catatan singkat soal limit free tier, ditampilkan di UI biar user paham trade-off. */
    val freeTierNote: String
) {
    GEMINI(
        displayName = "Google Gemini",
        shortDescription = "Model resmi Google, kuat untuk reasoning & coding",
        getKeyUrl = "https://aistudio.google.com/apikey",
        freeTierNote = "Free tier: RPM ketat, terutama model *-pro (~5 RPM). " +
            "Flash-Lite jauh lebih longgar."
    ),
    GROQ(
        displayName = "Groq",
        shortDescription = "Model open-source (Llama, GPT-OSS) di hardware LPU super cepat",
        getKeyUrl = "https://console.groq.com/keys",
        freeTierNote = "Free tier: 30 request/menit di semua model chat, tanpa kartu kredit. " +
            "Cocok untuk tool-calling banyak langkah."
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        shortDescription = "Satu API key untuk banyak model gratis dari berbagai penyedia",
        getKeyUrl = "https://openrouter.ai/keys",
        freeTierNote = "Model dengan akhiran \":free\": ~20 request/menit, 50-1000 request/hari " +
            "tergantung riwayat akun."
    ),
    MISTRAL(
        displayName = "Mistral AI",
        shortDescription = "Model open-weight Eropa (Mistral, Ministral, Codestral)",
        getKeyUrl = "https://console.mistral.ai/api-keys",
        freeTierNote = "Free tier \"Experiment\": ~1 request/detik, kuota bulanan besar. " +
            "Perlu daftar & verifikasi nomor telepon di console.mistral.ai."
    ),
    CLOUDFLARE(
        displayName = "Cloudflare Workers AI",
        shortDescription = "Model open-source di edge network Cloudflare (@cf/...)",
        getKeyUrl = "https://dash.cloudflare.com/profile/api-tokens",
        freeTierNote = "Free tier: 10.000 \"neuron\" gratis/hari lintas semua model. " +
            "Isi API Key dengan format ACCOUNT_ID:API_TOKEN (Account ID dari dashboard, " +
            "token dengan izin \"Workers AI\")."
    ),
    HUGGINGFACE(
        displayName = "Hugging Face",
        shortDescription = "Router Inference Providers HF ke banyak model (Llama, DeepSeek, dst)",
        getKeyUrl = "https://huggingface.co/settings/tokens",
        freeTierNote = "Token gratis punya kuota bulanan kecil (credit-based) yang mengarah " +
            "ke berbagai provider inferensi pihak ketiga. Buat token dengan izin " +
            "\"Make calls to Inference Providers\"."
    );

    companion object {
        val DEFAULT = GEMINI
    }
}

/**
 * Fallback tipis untuk model default SEBELUM daftar live selesai di-fetch
 * (lihat [ModelCatalogService]). Tidak lagi menyimpan daftar preset lengkap
 * — itu sekarang selalu diambil langsung dari API tiap provider supaya
 * tidak basi ketika model gratis berubah (terutama OpenRouter `:free`).
 */
object ProviderModels {

    /** Dipakai HANYA sebagai nilai awal AppPrefs sebelum user pernah memilih model apapun. */
    fun defaultModelFor(provider: AiProvider): String = when (provider) {
        AiProvider.GEMINI -> "gemini-3.5-flash"
        AiProvider.GROQ -> "llama-3.3-70b-versatile"
        AiProvider.OPENROUTER -> "" // tidak ada default aman tanpa fetch live (lihat alasan di kelas ini)
        AiProvider.MISTRAL -> "mistral-small-latest"
        AiProvider.CLOUDFLARE -> "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
        AiProvider.HUGGINGFACE -> "" // katalog router HF berubah-ubah, tidak ada default aman
    }
}
