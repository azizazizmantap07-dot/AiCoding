package com.kaneki.aicoder.ui.upload

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.AppDatabase
import com.kaneki.aicoder.data.local.db.ProjectEntity
import com.kaneki.aicoder.data.repository.ChatRepository
import com.kaneki.aicoder.data.repository.ProjectRepository
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.zip.SingleFileImportException
import com.kaneki.aicoder.domain.zip.SingleFileImporter
import com.kaneki.aicoder.domain.zip.ZipExtractor
import com.kaneki.aicoder.domain.zip.ZipExtractorException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val MAX_IMPORT_SIZE_BYTES: Long = 30L * 1024 * 1024

sealed interface UploadUiState {
    data object Idle : UploadUiState
    data object Extracting : UploadUiState
    data object LoadingProject : UploadUiState
    data class Success(val projectId: String, val fileTree: FileTree) : UploadUiState
    data class Error(val message: String) : UploadUiState
}

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val contentResolver = application.contentResolver
    private val zipExtractor = ZipExtractor(contentResolver)
    private val singleFileImporter = SingleFileImporter(contentResolver)
    private val zipFileStore = ZipFileStore(application)
    private val projectDao = AppDatabase.getInstance(application).projectDao()
    private val repository = ProjectRepository(
        contentResolver = contentResolver,
        zipExtractor = zipExtractor,
        singleFileImporter = singleFileImporter,
        zipFileStore = zipFileStore,
        projectDao = projectDao
    )
    private val chatRepository = ChatRepository(AppDatabase.getInstance(application).chatDao())

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    val projectHistory: StateFlow<List<ProjectEntity>> =
        repository.observeProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onFileSelected(uri: Uri) {
        val sizeBytes = querySizeBytes(contentResolver, uri)
        if (sizeBytes != null && sizeBytes > MAX_IMPORT_SIZE_BYTES) {
            val sizeMb = sizeBytes / (1024 * 1024)
            _uiState.value = UploadUiState.Error(
                "File berukuran ${sizeMb}MB, melebihi batas maksimal 30MB."
            )
            return
        }

        val displayName = queryDisplayName(contentResolver, uri)
            ?: "file-${System.currentTimeMillis()}"

        _uiState.value = UploadUiState.Extracting
        viewModelScope.launch {
            try {
                val imported = repository.importFromUri(uri, displayName)
                _uiState.value = UploadUiState.Success(
                    projectId = imported.projectId,
                    fileTree = imported.fileTree
                )
            } catch (e: ZipExtractorException) {
                _uiState.value = UploadUiState.Error(e.message ?: "Gagal mengekstrak ZIP")
            } catch (e: SingleFileImportException) {
                _uiState.value = UploadUiState.Error(e.message ?: "Gagal mengimpor file")
            } catch (e: Exception) {
                _uiState.value = UploadUiState.Error("Terjadi kesalahan: ${e.message}")
            }
        }
    }

    fun openProject(projectId: String) {
        _uiState.value = UploadUiState.LoadingProject
        viewModelScope.launch {
            val imported = repository.openExisting(projectId)
            if (imported == null) {
                _uiState.value = UploadUiState.Error(
                    "Project tidak ditemukan di storage (mungkin sudah dihapus sistem)."
                )
            } else {
                _uiState.value = UploadUiState.Success(
                    projectId = imported.projectId,
                    fileTree = imported.fileTree
                )
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            chatRepository.clearProject(projectId)
            repository.deleteProject(projectId)
            val current = _uiState.value
            if (current is UploadUiState.Success && current.projectId == projectId) {
                _uiState.value = UploadUiState.Idle
            }
        }
    }

    fun reset() {
        _uiState.value = UploadUiState.Idle
    }

    private fun querySizeBytes(contentResolver: ContentResolver, uri: Uri): Long? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex == -1 || !cursor.moveToFirst()) return@use null
            if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
        }
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex == -1 || !cursor.moveToFirst()) return@use null
            cursor.getString(nameIndex)
        }
    }
}
