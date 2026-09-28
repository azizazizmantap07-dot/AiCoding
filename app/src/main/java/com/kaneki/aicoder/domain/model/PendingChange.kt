package com.kaneki.aicoder.domain.model

import com.kaneki.aicoder.domain.diff.DiffComputer

/**
 * Perubahan file yang masih di staging (belum di-apply ke disk).
 * Prinsip blueprint: AI tidak pernah menulis langsung ke file asli —
 * semua write_file/rename_file masuk ke sini dulu, user review di Diff screen.
 *
 * [oldPath] terisi kalau perubahan ini berasal dari tool `rename_file`
 * (lihat [ToolExecutor]) — path lama file sebelum dipindah/diganti nama.
 * Ini menggantikan cara lama "tulis file baru dengan konten sama lalu
 * biarkan file lama nganggur", supaya rename benar-benar rename: file lama
 * dihapus dari disk dan bukan sekadar diduplikasi (lihat [isRenamed]).
 */
data class PendingChange(
    val path: String,
    val originalContent: String,
    val newContent: String,
    val approved: Boolean = true,
    val oldPath: String? = null
) {
    fun computeDiff(): List<DiffLine> =
        DiffComputer.compute(originalContent, newContent)

    val isNewFile: Boolean
        get() = originalContent.isEmpty() && newContent.isNotEmpty() && !isRenamed

    val isDeleted: Boolean
        get() = originalContent.isNotEmpty() && newContent.isEmpty() && !isRenamed

    /** True kalau perubahan ini adalah rename/move (dengan atau tanpa edit konten). */
    val isRenamed: Boolean
        get() = oldPath != null && oldPath != path
}
