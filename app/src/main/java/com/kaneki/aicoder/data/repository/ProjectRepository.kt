package com.kaneki.aicoder.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.kaneki.aicoder.data.local.ZipFileStore
import com.kaneki.aicoder.data.local.db.ProjectDao
import com.kaneki.aicoder.data.local.db.ProjectEntity
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.zip.ProjectTreeLoader
import com.kaneki.aicoder.domain.zip.SingleFileImporter
import com.kaneki.aicoder.domain.zip.ZipExtractor
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class ImportedProject(
    val projectId: String,
    val fileTree: FileTree
)

class ProjectRepository(
    private val contentResolver: ContentResolver,
    private val zipExtractor: ZipExtractor,
    private val singleFileImporter: SingleFileImporter,
    private val zipFileStore: ZipFileStore,
    private val projectDao: ProjectDao
) {

    fun observeProjects(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    /**
     * Import dari URI: ZIP → extract; file tunggal → copy sebagai project 1 file.
     */
    suspend fun importFromUri(uri: Uri, displayName: String): ImportedProject {
        return if (SingleFileImporter.isZipName(displayName) || isZipMime(uri)) {
            importZip(uri, displayName)
        } else {
            importSingleFile(uri, displayName)
        }
    }

    suspend fun importZip(zipUri: Uri, displayName: String): ImportedProject {
        val projectId = UUID.randomUUID().toString()
        val destDir = zipFileStore.projectDir(projectId)
        val result = zipExtractor.extract(zipUri, destDir)
        persist(projectId, displayName, destDir.absolutePath, result.fileTree)
        return ImportedProject(projectId, result.fileTree)
    }

    suspend fun importSingleFile(uri: Uri, displayName: String): ImportedProject {
        val projectId = UUID.randomUUID().toString()
        val destDir = zipFileStore.projectDir(projectId)
        val result = singleFileImporter.import(uri, displayName, destDir)
        val name = displayName.ifBlank { "file" }
        persist(projectId, name, destDir.absolutePath, result.fileTree)
        return ImportedProject(projectId, result.fileTree)
    }

    suspend fun openExisting(projectId: String): ImportedProject? {
        val entity = projectDao.getById(projectId) ?: return null
        val dir = zipFileStore.projectDir(projectId)
        if (!dir.exists()) {
            projectDao.deleteById(projectId)
            return null
        }
        val tree = ProjectTreeLoader.load(dir)
        if (tree.files.isEmpty()) return null
        return ImportedProject(projectId = entity.id, fileTree = tree)
    }

    suspend fun deleteProject(projectId: String) {
        zipFileStore.deleteProject(projectId)
        projectDao.deleteById(projectId)
    }

    private suspend fun persist(
        projectId: String,
        name: String,
        path: String,
        tree: FileTree
    ) {
        projectDao.insert(
            ProjectEntity(
                id = projectId,
                name = name,
                extractedPath = path,
                createdAt = System.currentTimeMillis(),
                fileCount = tree.totalFileCount,
                totalSizeBytes = tree.totalSizeBytes
            )
        )
    }

    private fun isZipMime(uri: Uri): Boolean {
        val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
        return mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            mime == "application/octet-stream" && false // jangan tebak octet sebagai zip
    }
}
