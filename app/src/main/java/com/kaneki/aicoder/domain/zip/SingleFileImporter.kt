package com.kaneki.aicoder.domain.zip

import android.content.ContentResolver
import android.net.Uri
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Import satu file teks/source ke folder project (bukan ZIP).
 * Nama file disanitasi; path relatif = nama file di root project.
 */
class SingleFileImporter(
    private val contentResolver: ContentResolver
) {
    data class Result(val fileTree: FileTree, val projectDir: File)

    suspend fun import(uri: Uri, displayName: String, destDir: File): Result =
        withContext(Dispatchers.IO) {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            val safeName = sanitizeFileName(displayName)
            val outFile = File(destDir, safeName)

            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw SingleFileImportException("Tidak bisa membaca file (permission / URI invalid)")

            if (!outFile.exists() || outFile.length() == 0L) {
                // Izinkan file kosong (mis. .gitkeep) tapi warn untuk 0-byte opsional
            }

            val projectFile = ProjectFile(
                path = safeName,
                sizeBytes = outFile.length(),
                isBinary = isBinaryFile(safeName)
            )
            Result(
                fileTree = FileTreeBuilder.build(listOf(projectFile)),
                projectDir = destDir
            )
        }

    companion object {
        private val binaryExt = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico",
            "so", "apk", "aar", "jar", "zip", "class", "dex",
            "ttf", "otf", "woff", "woff2",
            "mp3", "mp4", "wav", "ogg", "pdf"
        )

        fun isBinaryFile(name: String): Boolean =
            binaryExt.contains(name.substringAfterLast('.', "").lowercase())

        fun sanitizeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "file.txt" }
            // Cegah path traversal
            return base.replace(Regex("""[^\w.\- ()\[\]]+"""), "_")
                .take(180)
                .ifBlank { "file.txt" }
        }

        fun isZipName(name: String): Boolean =
            name.lowercase().endsWith(".zip")
    }
}

class SingleFileImportException(message: String) : Exception(message)
