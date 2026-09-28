package com.kaneki.aicoder.domain.zip

import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bangun ulang [FileTree] dari folder extract yang sudah ada di disk
 * (untuk membuka kembali project dari riwayat, tanpa re-import ZIP).
 */
object ProjectTreeLoader {

    private val binaryExt = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico",
        "so", "apk", "aar", "jar", "zip", "class", "dex",
        "ttf", "otf", "woff", "woff2",
        "mp3", "mp4", "wav", "ogg", "pdf"
    )

    suspend fun load(projectDir: File): FileTree = withContext(Dispatchers.IO) {
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@withContext FileTree.EMPTY
        }
        val entries = mutableListOf<ProjectFile>()
        projectDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(projectDir).path.replace('\\', '/')
            if (relative.startsWith(".")) return@forEach
            entries.add(
                ProjectFile(
                    path = relative,
                    sizeBytes = file.length(),
                    isBinary = isBinary(relative)
                )
            )
        }
        if (entries.isEmpty()) FileTree.EMPTY else FileTreeBuilder.build(entries)
    }

    private fun isBinary(name: String): Boolean =
        binaryExt.contains(name.substringAfterLast('.', "").lowercase())
}
