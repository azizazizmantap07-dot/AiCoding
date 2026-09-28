package com.kaneki.aicoder.domain.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.ChatDao
import com.kaneki.aicoder.data.local.db.ProjectDao
import com.kaneki.aicoder.domain.zip.RebuildResult
import com.kaneki.aicoder.domain.zip.ZipExporter
import com.kaneki.aicoder.domain.zip.ZipRebuilder
import java.io.File
import java.util.Locale

/** Hasil export cepat sekali panggil dari menu chat (tanpa buka layar Export). */
sealed interface QuickExportResult {
    data class Ready(val intent: Intent) : QuickExportResult
    data class Error(val message: String) : QuickExportResult
}

/**
 * Hasil "Terapkan & Simpan" di Diff Review: file disalin ke Downloads publik
 * (MediaStore / legacy path di [ZipExporter]), bukan hanya share intent dari cache.
 */
sealed interface QuickSaveResult {
    data class Saved(val uri: Uri, val displayName: String, val shareIntent: Intent) : QuickSaveResult
    data class Error(val message: String) : QuickSaveResult
}

/**
 * Use-case export project (single-file atau ZIP) — dipakai [ExportViewModel]
 * dan [com.kaneki.aicoder.ui.diff.DiffViewModel] tanpa ViewModel saling bergantung.
 */
class ProjectExportService(
    private val context: Context,
    private val zipFileStore: ZipFileStore = ZipFileStore(context),
    private val rebuilder: ZipRebuilder = ZipRebuilder(),
    private val exporter: ZipExporter = ZipExporter(context),
    private val projectDao: ProjectDao,
    private val chatDao: ChatDao
) {

    /**
     * Bangun artefak + share intent (tombol export di menu chat).
     * - 1 file (import .txt/.kt/…): export file itu saja, format asli.
     * - Multi-file (ZIP): export seluruh project sebagai ZIP.
     */
    suspend fun buildAndGetShareIntent(projectId: String): QuickExportResult {
        return when (val prepared = prepareExportArtifact(projectId)) {
            is PreparedExport.Ok -> QuickExportResult.Ready(
                exporter.createShareIntent(
                    prepared.file,
                    prepared.displayName,
                    prepared.mimeType
                )
            )
            is PreparedExport.Err -> QuickExportResult.Error(prepared.message)
        }
    }

    /**
     * Bangun artefak + simpan ke Downloads publik (tombol "Terapkan & Simpan" Diff Review).
     * Share intent tetap dikembalikan sebagai opsi tambahan.
     */
    suspend fun buildAndSaveToDownloads(projectId: String): QuickSaveResult {
        return when (val prepared = prepareExportArtifact(projectId)) {
            is PreparedExport.Ok -> {
                val uri = exporter.saveToDownloads(
                    prepared.file,
                    prepared.displayName,
                    prepared.mimeType
                )
                if (uri != null) {
                    QuickSaveResult.Saved(
                        uri = uri,
                        displayName = prepared.displayName,
                        shareIntent = exporter.createShareIntent(
                            prepared.file,
                            prepared.displayName,
                            prepared.mimeType
                        )
                    )
                } else {
                    // Gagal Downloads (umum API 26–28 tanpa izin storage) — file tetap di cache
                    QuickSaveResult.Error(
                        "Gagal menyimpan otomatis ke Downloads. File hasil edit masih bisa " +
                            "disimpan lewat tombol Bagikan."
                    )
                }
            }
            is PreparedExport.Err -> QuickSaveResult.Error(prepared.message)
        }
    }

    /** Siapkan file di cache app siap di-share / di-save (dipakai layar Export). */
    suspend fun prepare(projectId: String): PreparedExport = prepareExportArtifact(projectId)

    fun createShareIntent(file: File, displayName: String, mimeType: String): Intent =
        exporter.createShareIntent(file, displayName, mimeType)

    suspend fun saveToDownloads(file: File, displayName: String, mimeType: String): Uri? =
        exporter.saveToDownloads(file, displayName, mimeType)

    /**
     * Siapkan file yang akan di-export:
     * - project diimpor sebagai 1 file → export file tunggal (format asli, isi terbaru)
     * - project diimpor sebagai ZIP → ZIP seluruh isi project di disk
     *
     * Mode ditentukan [com.kaneki.aicoder.data.local.db.ProjectEntity.fileCount]
     * saat import (bukan jumlah file di disk sekarang), supaya file "nyasar"
     * tidak mengubah single-file menjadi multi-file ZIP.
     */
    private suspend fun prepareExportArtifact(projectId: String): PreparedExport {
        val projectDir = zipFileStore.projectDir(projectId)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return PreparedExport.Err("Project dir tidak ditemukan")
        }
        val files = projectDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .toList()

        val processLabel = resolveProcessLabel(projectId)
        val projectEntity = projectDao.getById(projectId)
        val projectBaseName = sanitizeBaseName(
            projectEntity?.name
                ?.removeSuffix(".zip")
                ?.removeSuffix(".ZIP")
                ?: "project"
        )

        val importedAsSingleFile = projectEntity?.fileCount == 1
        val singleSource = when {
            importedAsSingleFile && files.size == 1 -> files.first()
            importedAsSingleFile && files.size > 1 -> files.maxByOrNull { it.lastModified() }
            else -> null
        }

        return if (singleSource != null) {
            val source = singleSource
            val originalName = source.name
            val base = originalName.substringBeforeLast('.', originalName)
            val ext = originalName.substringAfterLast('.', missingDelimiterValue = "")
            val displayName = if (ext.isNotEmpty()) {
                "${sanitizeBaseName(base)}_$processLabel.$ext"
            } else {
                "${sanitizeBaseName(base)}_$processLabel"
            }
            val cacheFile = File(context.cacheDir, displayName)
            try {
                source.copyTo(cacheFile, overwrite = true)
                PreparedExport.Ok(
                    file = cacheFile,
                    fileCount = 1,
                    sizeBytes = cacheFile.length(),
                    displayName = displayName,
                    mimeType = ZipExporter.mimeForFileName(originalName)
                )
            } catch (e: Exception) {
                PreparedExport.Err(e.message ?: "Gagal menyalin file untuk export")
            }
        } else {
            val displayName = "${projectBaseName}_$processLabel.zip"
            val cacheZip = File(context.cacheDir, displayName)
            when (val result = rebuilder.rebuild(projectDir, cacheZip)) {
                is RebuildResult.Success -> PreparedExport.Ok(
                    file = result.outputFile,
                    fileCount = result.fileCount,
                    sizeBytes = result.sizeBytes,
                    displayName = displayName,
                    mimeType = "application/zip"
                )
                is RebuildResult.Error -> PreparedExport.Err(result.message)
            }
        }
    }

    /** Label proses dari pesan user terakhir (fix, rename, optimize, …). Default: edited. */
    private suspend fun resolveProcessLabel(projectId: String): String {
        val messages = chatDao.getByProject(projectId)
        val lastUser = messages.lastOrNull { it.role == "user" }?.content?.lowercase(Locale.US)
            ?: return "edited"

        val keywords = listOf(
            "rename" to "renamed",
            "ganti nama" to "renamed",
            "optimize" to "optimized",
            "optimasi" to "optimized",
            "optimisasi" to "optimized",
            "refactor" to "refactored",
            "perbaiki" to "fixed",
            "perbaikan" to "fixed",
            "fix" to "fixed",
            "bug" to "fixed",
            "improve" to "improved",
            "perbaiki performa" to "optimized",
            "bersihkan" to "cleaned",
            "clean" to "cleaned",
            "format" to "formatted",
            "translate" to "translated",
            "terjemah" to "translated",
            "convert" to "converted",
            "konversi" to "converted",
            "update" to "updated",
            "perbarui" to "updated",
            "tambah" to "updated",
            "add " to "updated",
            "hapus" to "updated",
            "delete" to "updated",
            "rewrite" to "rewritten",
            "tulis ulang" to "rewritten",
            "generate" to "generated",
            "buat " to "generated"
        )
        for ((needle, label) in keywords) {
            if (lastUser.contains(needle)) return label
        }
        return "edited"
    }

    private fun sanitizeBaseName(raw: String): String {
        val cleaned = raw
            .trim()
            .replace(Regex("""[^\w.\- ()\[\]]+"""), "_")
            .replace(Regex("""_++"""), "_")
            .trim('_', '.', ' ')
        return cleaned.take(80).ifBlank { "file" }
    }

    sealed interface PreparedExport {
        data class Ok(
            val file: File,
            val fileCount: Int,
            val sizeBytes: Long,
            val displayName: String,
            val mimeType: String
        ) : PreparedExport

        data class Err(val message: String) : PreparedExport
    }
}
