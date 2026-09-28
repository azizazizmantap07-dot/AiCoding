package com.kaneki.aicoder.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kaneki.aicoder.data.remote.AiProvider

/**
 * Wrapper EncryptedSharedPreferences (Jetpack Security), sesuai Bagian 5.2 blueprint.
 * API key TIDAK PERNAH boleh disimpan di SharedPreferences biasa/plain text, bahkan
 * untuk versi awal/testing — ini larangan eksplisit di aturan kerja, bukan sekadar
 * "praktik terbaik" opsional.
 *
 * Sejak dukungan multi-provider ditambahkan, key disimpan PER-PROVIDER (bukan satu
 * key global) — supaya ganti provider di pemilih model tidak menghapus/menimpa key
 * provider lain yang sudah pernah diisi.
 */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(provider: AiProvider, key: String) =
        prefs.edit().putString(prefKeyFor(provider), key).apply()

    /**
     * Migrasi satu kali: kalau key lama (pra-multi-provider) masih ada dan
     * key Gemini per-provider belum pernah diisi, pindahkan isinya supaya
     * user existing tidak perlu memasukkan API key Gemini lagi dari nol.
     */
    fun migrateLegacyGeminiKeyIfNeeded() {
        val legacy = prefs.getString(LEGACY_KEY_GEMINI_API_KEY, null)
        if (!legacy.isNullOrBlank() && !hasApiKey(AiProvider.GEMINI)) {
            saveApiKey(AiProvider.GEMINI, legacy)
        }
        if (legacy != null) {
            prefs.edit().remove(LEGACY_KEY_GEMINI_API_KEY).apply()
        }
    }

    fun getApiKey(provider: AiProvider): String? = prefs.getString(prefKeyFor(provider), null)

    fun hasApiKey(provider: AiProvider): Boolean = !getApiKey(provider).isNullOrBlank()

    fun clearApiKey(provider: AiProvider) = prefs.edit().remove(prefKeyFor(provider)).apply()

    /** True jika ADA minimal satu provider dengan key tersimpan (dipakai gate awal app). */
    fun hasAnyApiKey(): Boolean = AiProvider.entries.any { hasApiKey(it) }

    private fun prefKeyFor(provider: AiProvider): String = "api_key_${provider.name.lowercase()}"

    companion object {
        // Dipertahankan agar migrasi otomatis (lihat AppPrefs) tahu key lama Gemini
        // sebelum multi-provider ada, supaya user existing tidak kehilangan key
        // yang sudah divalidasi & disimpan.
        const val LEGACY_KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}

