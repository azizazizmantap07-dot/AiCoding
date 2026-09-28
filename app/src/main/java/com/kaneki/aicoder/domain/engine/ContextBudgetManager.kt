package com.kaneki.aicoder.domain.engine

import com.kaneki.aicoder.domain.model.ChatTurn

/**
 * Estimasi & batas token history (Bagian 4.1 blueprint).
 *
 * [maxContextTokens] TIDAK lagi satu angka tetap untuk semua model — context
 * window beda jauh antar provider/model (Gemini Flash ~1M, tapi model gratis
 * Groq/OpenRouter bisa cuma 8K-128K). [maxContextTokens] bernilai NULL kalau
 * limit asli model tidak diketahui (mis. provider tidak mengembalikan info
 * context length, atau user isi model ID custom manual) — TIDAK lagi diam-diam
 * jatuh ke angka statis (900_000) seolah itu data nyata. UI (lihat ChatScreen)
 * wajib menampilkan status "belum ada info" secara eksplisit saat null, bukan
 * menampilkan angka yang terkesan aktual padahal cuma tebakan.
 * Pemanggil (lihat ChatViewModel) sebisa mungkin membuat instance ini dengan
 * limit ASLI model yang sedang aktif, dari [com.kaneki.aicoder.data.remote.LiveModel.contextLimit].
 *
 * Cadangan (dipakai internal untuk trigger compaction — TIDAK ditampilkan ke
 * user sebagai limit "aktual" saat [maxContextTokens] null):
 * - 20% margin safety ([budgetThreshold])
 * - overhead kasar system prompt + tool declarations (~[systemOverheadTokens])
 * - kalau limit model belum diketahui, dipakai [INTERNAL_SAFETY_FALLBACK_TOKENS]
 *   HANYA sebagai ambang batas internal supaya history tidak tumbuh tanpa
 *   batas sebelum limit asli didapat — nilai ini tidak pernah dikirim ke UI
 *   lewat [BudgetSnapshot.maxTokens] (yang tetap null), jadi tidak ada angka
 *   palsu yang terlihat "aktual" di layar.
 *
 * Estimasi 1 token ≈ 4 karakter — cukup untuk trigger compaction saat belum
 * ada angka pasti dari API (lihat [usageSummary] soal `apiTotalTokens`).
 */
class ContextBudgetManager(
    maxContextTokens: Int? = null,
    private val systemOverheadTokens: Int = 8_000
) {
    /**
     * Context window aktif — null berarti BELUM DIKETAHUI (provider/model tidak
     * mengembalikan context length). Diganti live lewat [updateMaxTokens] saat
     * user ganti model atau saat API mengembalikan info context yang valid.
     */
    var maxContextTokens: Int? = maxContextTokens?.takeIf { it > 0 }
        private set

    /** True kalau limit context window model aktif belum pernah diketahui dari API. */
    val isMaxTokensKnown: Boolean
        get() = maxContextTokens != null

    /**
     * Ganti limit context window, dipanggil saat user memilih/berganti model
     * (lihat ChatViewModel.setSelectedProviderAndModel). [newLimit] null atau
     * <= 0 membuat limit menjadi "belum diketahui" (bukan lagi jatuh ke
     * limit lama/fallback tetap) supaya indikator token jujur menampilkan
     * bahwa info context model ini memang tidak tersedia.
     */
    fun updateMaxTokens(newLimit: Int?) {
        maxContextTokens = newLimit?.takeIf { it > 0 }
    }

    /** Ambang batas internal (80% dari limit asli, atau fallback aman kalau limit belum diketahui). */
    private val internalThreshold: Int
        get() = ((maxContextTokens ?: INTERNAL_SAFETY_FALLBACK_TOKENS) * 0.8).toInt()

    /** Ambang batas untuk ditampilkan ke UI — null kalau limit asli belum diketahui. */
    val budgetThreshold: Int?
        get() = maxContextTokens?.let { (it * 0.8).toInt() }

    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(0)

    fun estimateHistoryTokens(history: List<ChatTurn>): Int =
        history.sumOf { estimateTokens(it.serializedContent()) } + systemOverheadTokens

    /** Pakai [internalThreshold] (bukan angka UI) supaya compaction tetap jalan walau limit asli belum diketahui. */
    fun isOverBudget(history: List<ChatTurn>): Boolean =
        estimateHistoryTokens(history) > internalThreshold

    fun usageSummary(history: List<ChatTurn>, apiTotalTokens: Int? = null): BudgetSnapshot {
        val estimated = estimateHistoryTokens(history)
        val used = apiTotalTokens ?: estimated
        return BudgetSnapshot(
            estimatedTokens = estimated,
            apiTotalTokens = apiTotalTokens,
            maxTokens = maxContextTokens,
            thresholdTokens = budgetThreshold,
            overBudget = used > internalThreshold
        )
    }

    data class BudgetSnapshot(
        val estimatedTokens: Int,
        val apiTotalTokens: Int?,
        /** Null = limit context model ini belum diketahui dari API (bukan 0/negatif, bukan fallback statis). */
        val maxTokens: Int?,
        val thresholdTokens: Int?,
        val overBudget: Boolean
    ) {
        val displayUsed: Int get() = apiTotalTokens ?: estimatedTokens

        /**
         * Null kalau [maxTokens] tidak diketahui — UI HARUS menampilkan status
         * "belum ada info" untuk kasus ini, bukan menghitung persentase dari
         * limit yang sebenarnya tidak diketahui (itu akan tampak aktual padahal
         * bukan).
         */
        val percent: Int?
            get() = maxTokens?.let {
                if (it <= 0) null
                else ((displayUsed.toDouble() / it) * 100).toInt().coerceIn(0, 999)
            }
    }

    companion object {
        /**
         * Ambang batas INTERNAL saja (trigger compaction saat limit model belum
         * diketahui) — TIDAK PERNAH ditampilkan ke user sebagai "limit maksimal"
         * karena itu akan terkesan sebagai data aktual padahal tebakan.
         */
        const val INTERNAL_SAFETY_FALLBACK_TOKENS = 128_000
    }
}
