package com.kaneki.aicoder.domain.diff

import com.kaneki.aicoder.domain.model.PendingChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Terapkan PendingChange yang di-approve ke disk project (setelah user review).
 * File baru: buat parent dir + tulis.
 * Konten kosong + original ada: hapus file (kasus delete total — opsional).
 * [PendingChange.isRenamed]: hapus file di [PendingChange.oldPath] dan tulis
 * konten (baru atau tetap sama) di [PendingChange.path] — rename yang
 * sebenarnya di disk, bukan sekadar file baru yang menduplikasi isi file lama.
 * Selain itu: overwrite.
 */
object ChangeApplier {

    data class ApplyResult(
        val appliedPaths: List<String>,
        val failed: List<Pair<String, String>> // path to error message
    )

    suspend fun apply(
        projectDir: File,
        approved: List<PendingChange>
    ): ApplyResult = withContext(Dispatchers.IO) {
        val applied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        fun withinProjectDir(file: File): Boolean {
            val canonical = file.canonicalFile
            return canonical.path.startsWith(projectDir.canonicalFile.path + File.separator) ||
                canonical.path == projectDir.canonicalFile.path
        }

        for (change in approved) {
            if (!change.approved) continue
            try {
                val file = File(projectDir, change.path)
                // Zip slip guard
                if (!withinProjectDir(file)) {
                    failed.add(change.path to "Path di luar project dir (ditolak)")
                    continue
                }

                if (change.isRenamed) {
                    val oldFile = File(projectDir, change.oldPath!!)
                    if (!withinProjectDir(oldFile)) {
                        failed.add(change.path to "Path lama di luar project dir (ditolak)")
                        continue
                    }
                    if (file.exists()) {
                        failed.add(change.path to "Path tujuan sudah ada, rename dibatalkan")
                        continue
                    }
                    file.parentFile?.mkdirs()
                    file.writeText(change.newContent)
                    if (oldFile.exists() && !oldFile.delete()) {
                        // Konten sudah tertulis ke path baru; file lama gagal
                        // dihapus (mis. permission) — tetap laporkan sukses
                        // untuk path baru, tapi beri tahu soal sisa file lama.
                        applied.add(change.path)
                        failed.add(
                            change.oldPath to "File baru berhasil dibuat, tapi file lama " +
                                "gagal dihapus — hapus manual jika perlu."
                        )
                    } else {
                        applied.add(change.path)
                    }
                } else if (change.newContent.isEmpty() && change.originalContent.isNotEmpty()) {
                    // Interpretasi: hapus file
                    if (file.exists() && !file.delete()) {
                        failed.add(change.path to "Gagal menghapus file")
                    } else {
                        applied.add(change.path)
                    }
                } else {
                    file.parentFile?.mkdirs()
                    file.writeText(change.newContent)
                    applied.add(change.path)
                }
            } catch (e: Exception) {
                failed.add(change.path to (e.message ?: "unknown error"))
            }
        }

        ApplyResult(appliedPaths = applied, failed = failed)
    }
}
