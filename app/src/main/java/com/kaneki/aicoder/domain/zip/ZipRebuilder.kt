package com.kaneki.aicoder.domain.zip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rebuild ZIP dari isi projectDir (Bagian 6.5 blueprint).
 *
 * [contentOverrides]: path relatif → konten baru (byte). Setelah Tahap 5 apply,
 * disk sudah up-to-date sehingga map ini biasanya kosong; tetap didukung
 * untuk export staging tanpa apply (opsional).
 *
 * Jalan di [Dispatchers.IO]; zip slip tidak relevan di sini karena kita
 * membaca dari projectDir yang sudah di-sanitize saat extract.
 */
class ZipRebuilder {

    suspend fun rebuild(
        projectDir: File,
        outputZip: File,
        contentOverrides: Map<String, ByteArray> = emptyMap()
    ): RebuildResult = withContext(Dispatchers.IO) {
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@withContext RebuildResult.Error("Project dir tidak ditemukan: ${projectDir.absolutePath}")
        }

        outputZip.parentFile?.mkdirs()
        if (outputZip.exists() && !outputZip.delete()) {
            return@withContext RebuildResult.Error("Gagal menimpa file ZIP lama")
        }

        var fileCount = 0
        try {
            ZipOutputStream(outputZip.outputStream().buffered()).use { zos ->
                projectDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relativePath = file.relativeTo(projectDir).path.replace('\\', '/')
                        // Skip file sistem / tersembunyi yang tidak perlu di export
                        if (relativePath.startsWith(".")) return@forEach

                        zos.putNextEntry(ZipEntry(relativePath))
                        val bytes = contentOverrides[relativePath] ?: file.readBytes()
                        zos.write(bytes)
                        zos.closeEntry()
                        fileCount++
                    }
            }
            RebuildResult.Success(
                outputFile = outputZip,
                fileCount = fileCount,
                sizeBytes = outputZip.length()
            )
        } catch (e: Exception) {
            runCatching { outputZip.delete() }
            RebuildResult.Error(e.message ?: "Gagal membuat ZIP")
        }
    }
}

sealed interface RebuildResult {
    data class Success(
        val outputFile: File,
        val fileCount: Int,
        val sizeBytes: Long
    ) : RebuildResult

    data class Error(val message: String) : RebuildResult
}
