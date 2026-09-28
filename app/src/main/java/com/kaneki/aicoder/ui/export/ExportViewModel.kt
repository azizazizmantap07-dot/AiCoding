package com.kaneki.aicoder.ui.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaneki.aicoder.data.local.db.AppDatabase
import com.kaneki.aicoder.domain.export.ProjectExportService
import com.kaneki.aicoder.domain.export.QuickExportResult
import com.kaneki.aicoder.domain.export.QuickSaveResult
import com.kaneki.aicoder.domain.zip.ZipExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Building : ExportUiState
    data class Ready(
        val zipFile: File,
        val fileCount: Int,
        val sizeBytes: Long,
        val displayName: String,
        /** Mime type untuk share/save (application/zip atau text/plain, dll.). */
        val mimeType: String = "application/zip"
    ) : ExportUiState

    data class SavedToDownloads(val uri: Uri, val displayName: String) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

/**
 * ViewModel layar Export — UI state saja; logika bangun/simpan artefak ada di
 * [ProjectExportService] (juga dipakai Diff Review tanpa membuat ViewModel lain).
 */
class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val exportService = ProjectExportService(
        context = application,
        projectDao = db.projectDao(),
        chatDao = db.chatDao()
    )

    private var projectId: String = ""

    private val _uiState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    fun init(projectId: String) {
        this.projectId = projectId
        _uiState.value = ExportUiState.Idle
    }

    /** Bangun artefak export dari isi disk project saat ini (setelah apply diff). */
    fun buildZip() {
        if (projectId.isBlank()) {
            _uiState.value = ExportUiState.Error("projectId kosong")
            return
        }
        _uiState.value = ExportUiState.Building
        viewModelScope.launch {
            when (val prepared = exportService.prepare(projectId)) {
                is ProjectExportService.PreparedExport.Ok -> {
                    _uiState.value = ExportUiState.Ready(
                        zipFile = prepared.file,
                        fileCount = prepared.fileCount,
                        sizeBytes = prepared.sizeBytes,
                        displayName = prepared.displayName,
                        mimeType = prepared.mimeType
                    )
                }
                is ProjectExportService.PreparedExport.Err -> {
                    _uiState.value = ExportUiState.Error(prepared.message)
                }
            }
        }
    }

    /**
     * Bangun artefak + share intent (tombol export di menu chat).
     * Delegasi ke [ProjectExportService]; UI state ikut di-update bila sukses.
     */
    suspend fun buildAndGetShareIntent(projectId: String): QuickExportResult {
        this.projectId = projectId
        return when (val prepared = exportService.prepare(projectId)) {
            is ProjectExportService.PreparedExport.Ok -> {
                _uiState.value = ExportUiState.Ready(
                    zipFile = prepared.file,
                    fileCount = prepared.fileCount,
                    sizeBytes = prepared.sizeBytes,
                    displayName = prepared.displayName,
                    mimeType = prepared.mimeType
                )
                QuickExportResult.Ready(
                    exportService.createShareIntent(
                        prepared.file,
                        prepared.displayName,
                        prepared.mimeType
                    )
                )
            }
            is ProjectExportService.PreparedExport.Err -> QuickExportResult.Error(prepared.message)
        }
    }

    /**
     * Bangun artefak + simpan ke Downloads (dipakai Diff Review lewat service
     * yang sama; method ini tetap ada untuk API konsisten jika dipanggil dari UI).
     */
    suspend fun buildAndSaveToDownloads(projectId: String): QuickSaveResult {
        this.projectId = projectId
        val result = exportService.buildAndSaveToDownloads(projectId)
        when (result) {
            is QuickSaveResult.Saved -> {
                _uiState.value = ExportUiState.SavedToDownloads(result.uri, result.displayName)
            }
            is QuickSaveResult.Error -> {
                // Biarkan caller (Diff) menampilkan error; state export tidak dipaksa Error
                // agar layar Export yang kebuka terpisah tidak ikut kotor.
            }
        }
        return result
    }

    fun saveToDownloads() {
        val ready = _uiState.value as? ExportUiState.Ready ?: return
        viewModelScope.launch {
            val uri = exportService.saveToDownloads(
                ready.zipFile,
                ready.displayName,
                ready.mimeType
            )
            if (uri != null) {
                _uiState.value = ExportUiState.SavedToDownloads(uri, ready.displayName)
            } else {
                _uiState.value = ExportUiState.Error(
                    "Gagal menyimpan ke Downloads (umum di Android 8–9 tanpa izin storage). " +
                        "Gunakan tombol Share untuk menyimpan lewat app lain."
                )
            }
        }
    }

    fun createShareIntent(): Intent? {
        val ready = _uiState.value
        return when (ready) {
            is ExportUiState.Ready ->
                exportService.createShareIntent(ready.zipFile, ready.displayName, ready.mimeType)
            is ExportUiState.SavedToDownloads -> {
                val cache = File(getApplication<Application>().cacheDir, ready.displayName)
                if (!cache.exists()) return null
                val mime = ZipExporter.mimeForFileName(ready.displayName)
                exportService.createShareIntent(cache, ready.displayName, mime)
            }
            else -> null
        }
    }
}
