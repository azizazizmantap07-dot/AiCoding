package com.kaneki.aicoder.data.local

import android.content.Context
import com.kaneki.aicoder.data.remote.AiProvider
import com.kaneki.aicoder.data.remote.ProviderModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences non-rahasia (tema, dll). Bukan untuk API key —
 * key tetap di [SecurePrefs] / EncryptedSharedPreferences.
 */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _selectedProvider = MutableStateFlow(readSelectedProvider())
    val selectedProvider: StateFlow<AiProvider> = _selectedProvider.asStateFlow()

    private val _selectedModel = MutableStateFlow(readSelectedModel(readSelectedProvider()))
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _selectedModelLabel = MutableStateFlow(readSelectedModelLabel(readSelectedProvider()))
    val selectedModelLabel: StateFlow<String> = _selectedModelLabel.asStateFlow()

    private val _selectedModelContextLimit =
        MutableStateFlow(readSelectedModelContextLimit(readSelectedProvider()))
    val selectedModelContextLimit: StateFlow<Int?> = _selectedModelContextLimit.asStateFlow()

    private val _selectedModelSupportsVision =
        MutableStateFlow(readSelectedModelSupportsVision(readSelectedProvider()))
    val selectedModelSupportsVision: StateFlow<Boolean> = _selectedModelSupportsVision.asStateFlow()

    private val _selectedModelSupportsImageGen =
        MutableStateFlow(readSelectedModelSupportsImageGen(readSelectedProvider()))
    val selectedModelSupportsImageGen: StateFlow<Boolean> = _selectedModelSupportsImageGen.asStateFlow()

    fun getThemeMode(): ThemeMode = readThemeMode()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun getSelectedProvider(): AiProvider = readSelectedProvider()

    fun setSelectedProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, provider.name).apply()
        _selectedProvider.value = provider
        // Setiap provider ingat model terakhirnya sendiri (key per-provider di bawah),
        // jadi ganti provider otomatis memuat model yang relevan, bukan model provider lama.
        _selectedModel.value = readSelectedModel(provider)
        _selectedModelLabel.value = readSelectedModelLabel(provider)
        _selectedModelContextLimit.value = readSelectedModelContextLimit(provider)
        _selectedModelSupportsVision.value = readSelectedModelSupportsVision(provider)
        _selectedModelSupportsImageGen.value = readSelectedModelSupportsImageGen(provider)
    }

    fun getSelectedModel(): String = readSelectedModel(getSelectedProvider())

    /** Context window (input token limit) model yang aktif sekarang, kalau diketahui. */
    fun getSelectedModelContextLimit(): Int? = readSelectedModelContextLimit(getSelectedProvider())

    /**
     * [label] adalah nama tampilan yang didapat SAAT fetch live dari
     * [com.kaneki.aicoder.data.remote.ModelCatalogService] (mis. "Llama 3.3 70B"),
     * disimpan terpisah dari [modelId] mentah supaya UI tidak perlu fetch ulang
     * hanya untuk menampilkan nama model aktif di chip ChatScreen.
     *
     * [contextLimit] adalah context window ASLI model ini (dari API provider,
     * lihat [com.kaneki.aicoder.data.remote.LiveModel.contextLimit]), disimpan
     * per-provider supaya indikator token (Bagian ContextBudgetManager) memakai
     * limit yang benar-benar sesuai model yang dipilih, bukan angka statis yang
     * sama untuk semua model/provider. Null kalau tidak diketahui (mis. model
     * custom yang diisi manual) — pemanggil lalu jatuh ke fallback aman.
     */
    fun setSelectedModel(
        modelId: String,
        label: String = modelId,
        contextLimit: Int? = null,
        supportsVision: Boolean = false,
        supportsImageGen: Boolean = false
    ) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        val provider = getSelectedProvider()
        val resolvedImageGen = supportsImageGen ||
            com.kaneki.aicoder.data.remote.ModelRelevance.isImageGen(trimmed)
        val editor = prefs.edit()
            .putString(modelKeyFor(provider), trimmed)
            .putString(modelLabelKeyFor(provider), label.trim().ifEmpty { trimmed })
            .putBoolean(modelVisionKeyFor(provider), supportsVision)
            .putBoolean(modelImageGenKeyFor(provider), resolvedImageGen)
        val resolvedLimit = when {
            contextLimit != null && contextLimit > 0 -> contextLimit
            else -> com.kaneki.aicoder.data.remote.ModelDefaults.contextLimitFor(provider, trimmed)
        }
        if (resolvedLimit != null && resolvedLimit > 0) {
            editor.putInt(modelContextLimitKeyFor(provider), resolvedLimit)
        } else {
            editor.remove(modelContextLimitKeyFor(provider))
        }
        editor.apply()
        _selectedModel.value = trimmed
        _selectedModelLabel.value = label.trim().ifEmpty { trimmed }
        _selectedModelContextLimit.value = resolvedLimit?.takeIf { it > 0 }
        _selectedModelSupportsVision.value = supportsVision
        _selectedModelSupportsImageGen.value = resolvedImageGen
    }

    fun getSelectedModelSupportsVision(): Boolean =
        readSelectedModelSupportsVision(getSelectedProvider())

    fun getSelectedModelSupportsImageGen(): Boolean =
        readSelectedModelSupportsImageGen(getSelectedProvider())

    private fun readThemeMode(): ThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun readSelectedProvider(): AiProvider {
        val raw = prefs.getString(KEY_SELECTED_PROVIDER, AiProvider.DEFAULT.name)
            ?: AiProvider.DEFAULT.name
        return runCatching { AiProvider.valueOf(raw) }.getOrDefault(AiProvider.DEFAULT)
    }

    private fun readSelectedModel(provider: AiProvider): String {
        val raw = prefs.getString(modelKeyFor(provider), ProviderModels.defaultModelFor(provider))
            ?: ProviderModels.defaultModelFor(provider)
        if (provider == AiProvider.CLOUDFLARE) {
            // UUID lama dari models/search tidak valid — jatuhkan ke default @cf/
            val normalized = com.kaneki.aicoder.data.remote.CloudflareModelId.normalize(raw)
            if (normalized != null) return normalized
            return ProviderModels.defaultModelFor(provider)
        }
        return raw
    }

    private fun readSelectedModelLabel(provider: AiProvider): String {
        val fallback = readSelectedModel(provider)
        return prefs.getString(modelLabelKeyFor(provider), fallback) ?: fallback
    }

    private fun readSelectedModelContextLimit(provider: AiProvider): Int? {
        val v = prefs.getInt(modelContextLimitKeyFor(provider), -1)
        if (v > 0) return v
        // Fallback lintas provider: limit curated / heuristik agar indikator
        // token tidak kosong ("Tidak tersedia") saat API tidak mengirim context_length.
        val modelId = readSelectedModel(provider)
        return com.kaneki.aicoder.data.remote.ModelDefaults.contextLimitFor(provider, modelId)
    }

    private fun modelKeyFor(provider: AiProvider): String =
        "selected_model_${provider.name.lowercase()}"

    private fun modelLabelKeyFor(provider: AiProvider): String =
        "selected_model_label_${provider.name.lowercase()}"

    private fun modelContextLimitKeyFor(provider: AiProvider): String =
        "selected_model_context_limit_${provider.name.lowercase()}"

    private fun modelVisionKeyFor(provider: AiProvider): String =
        "selected_model_supports_vision_${provider.name.lowercase()}"

    private fun modelImageGenKeyFor(provider: AiProvider): String =
        "selected_model_supports_image_gen_${provider.name.lowercase()}"

    private fun readSelectedModelSupportsVision(provider: AiProvider): Boolean =
        prefs.getBoolean(modelVisionKeyFor(provider), false)

    private fun readSelectedModelSupportsImageGen(provider: AiProvider): Boolean {
        if (prefs.contains(modelImageGenKeyFor(provider))) {
            return prefs.getBoolean(modelImageGenKeyFor(provider), false)
        }
        // Fallback: deteksi dari id model tersimpan
        return com.kaneki.aicoder.data.remote.ModelRelevance.isImageGen(readSelectedModel(provider))
    }

    companion object {
        private const val PREFS_NAME = "aicoder_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SELECTED_PROVIDER = "selected_provider"

        @Volatile
        private var instance: AppPrefs? = null

        fun getInstance(context: Context): AppPrefs {
            return instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
        }
    }
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

