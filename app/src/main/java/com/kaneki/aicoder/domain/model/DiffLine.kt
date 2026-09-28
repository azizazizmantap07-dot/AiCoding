package com.kaneki.aicoder.domain.model

/**
 * Satu baris dalam tampilan diff (Bagian 6.4 blueprint).
 * - EQUAL: tidak berubah (abu-abu redup)
 * - INSERT: baris baru (hijau)
 * - DELETE: baris dihapus (merah)
 */
enum class DiffLineType {
    EQUAL,
    INSERT,
    DELETE
}

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    /** Nomor baris di sisi original (null untuk INSERT murni). */
    val oldLineNumber: Int? = null,
    /** Nomor baris di sisi new (null untuk DELETE murni). */
    val newLineNumber: Int? = null
)
