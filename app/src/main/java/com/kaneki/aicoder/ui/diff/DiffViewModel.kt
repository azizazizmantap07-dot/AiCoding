package com.kaneki.aicoder.ui.diff

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.AppDatabase
import com.kaneki.aicoder.domain.diff.ChangeApplier
import com.kaneki.aicoder.domain.export.ProjectExportService
import com.kaneki.aicoder.domain.export.QuickSaveResult
import com.kaneki.aicoder.domain.model.DiffLine
import com.kaneki.aicoder.domain.model.PendingChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DiffUiState {
    data object Idle : DiffUiState
    data object Applying : DiffUiState
    data class Saving(val message: String) : DiffUiState
    data class SaveReady(
        val uri: Uri,
        val displayName: String,
        val shareIntent: Intent,
        val message: String
    ) : DiffUiState
    data class Error(val message: String) : DiffUiState
}

/**
 * ViewModel Diff Review.
 * Toggle approve per file, hitung diff, apply ke disk.
 *
 * "Terapkan & Simpan" = apply + [ProjectExportService.buildAndSaveToDownloads]
 * (bukan lewat ExportViewModel), supaya tidak ada ViewModel saling membuat instance.
 */
class DiffViewModel(application: Application) : AndroidViewModel(application) {

    private val zipFileStore = ZipFileStore(application)
    private val db = AppDatabase.getInstance(application)
    private val exportService = ProjectExportService(
        context = application,
        projectDao = db.projectDao(),
        chatDao = db.chatDao()
    )

    private var projectId: String = ""

    private val _changes = MutableStateFlow<List<PendingChange>>(emptyList())
    val changes: StateFlow<List<PendingChange>> = _changes.asStateFlow()

    /** path → diff lines (lazy, di-cache setelah pertama kali dihitung) */
    private val _diffCache = MutableStateFlow<Map<String, List<DiffLine>>>(emptyMap())
    val diffCache: StateFlow<Map<String, List<DiffLine>>> = _diffCache.asStateFlow()

    private val _selectedPath = MutableStateFlow<String?>(null)
    val selectedPath: StateFlow<String?> = _selectedPath.asStateFlow()

    private val _uiState = MutableStateFlow<DiffUiState>(DiffUiState.Idle)
    val uiState: StateFlow<DiffUiState> = _uiState.asStateFlow()

    fun init(projectId: String, pending: List<PendingChange>) {
        this.projectId = projectId
        _changes.value = pending
        _diffCache.value = emptyMap()
        _selectedPath.value = pending.firstOrNull()?.path
        _uiState.value = DiffUiState.Idle
        pending.firstOrNull()?.let { ensureDiff(it.path) }
    }

    fun selectFile(path: String) {
        _selectedPath.value = path
        ensureDiff(path)
    }

    fun toggleApproved(path: String) {
        _changes.value = _changes.value.map { c ->
            if (c.path == path) c.copy(approved = !c.approved) else c
        }
    }

    fun approveAll() {
        _changes.value = _changes.value.map { it.copy(approved = true) }
    }

    fun rejectAll() {
        _changes.value = _changes.value.map { it.copy(approved = false) }
    }

    /**
     * Terapkan file yang disetujui ke disk, lalu bangun artefak export dan
     * simpan ke Downloads publik lewat [ProjectExportService].
     */
    fun applyAndSave() {
        val approved = _changes.value.filter { it.approved }
        if (approved.isEmpty()) {
            _uiState.value = DiffUiState.Error("Tidak ada file yang disetujui.")
            return
        }
        if (projectId.isBlank()) {
            _uiState.value = DiffUiState.Error("projectId kosong.")
            return
        }

        _uiState.value = DiffUiState.Applying
        viewModelScope.launch {
            val dir = zipFileStore.projectDir(projectId)
            val result = ChangeApplier.apply(dir, approved)

            val appliedSet = result.appliedPaths.toSet()
            _changes.value = _changes.value.filter { it.path !in appliedSet }
            if (_changes.value.isEmpty()) {
                _selectedPath.value = null
            } else if (_selectedPath.value in appliedSet) {
                _selectedPath.value = _changes.value.firstOrNull()?.path
            }

            if (result.appliedPaths.isEmpty()) {
                _uiState.value = DiffUiState.Error(
                    "Gagal menerapkan perubahan: " +
                        result.failed.joinToString { "${it.first} (${it.second})" }
                )
                return@launch
            }

            _uiState.value = DiffUiState.Saving("Menyimpan ke folder Downloads…")
            when (val saveResult = exportService.buildAndSaveToDownloads(projectId)) {
                is QuickSaveResult.Saved -> {
                    val msg = buildString {
                        append("${result.appliedPaths.size} file diterapkan, tersimpan di Downloads")
                        if (result.failed.isNotEmpty()) {
                            append("; ${result.failed.size} gagal: ")
                            append(result.failed.joinToString { "${it.first} (${it.second})" })
                        }
                    }
                    _uiState.value = DiffUiState.SaveReady(
                        uri = saveResult.uri,
                        displayName = saveResult.displayName,
                        shareIntent = saveResult.shareIntent,
                        message = msg
                    )
                }
                is QuickSaveResult.Error -> {
                    _uiState.value = DiffUiState.Error(
                        "Perubahan diterapkan, tapi gagal menyimpan ke Downloads: " +
                            saveResult.message
                    )
                }
            }
        }
    }

    private fun ensureDiff(path: String) {
        if (_diffCache.value.containsKey(path)) return
        val change = _changes.value.find { it.path == path } ?: return
        val lines = change.computeDiff()
        _diffCache.value = _diffCache.value + (path to lines)
    }
}
