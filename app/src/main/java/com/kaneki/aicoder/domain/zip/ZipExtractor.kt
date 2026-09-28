package com.kaneki.aicoder.domain.zip

import android.content.ContentResolver
import android.net.Uri
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Hasil extract satu ZIP: tree file-nya, plus path fisik root di internal storage
 * (dipakai ToolExecutor & ZipRebuilder di tahap-tahap berikutnya).
 */
data class ExtractResult(
    val fileTree: FileTree,
    val projectDir: File
)

class ZipExtractorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Ekstrak ZIP project ke internal storage app.
 *
 * Beda dari contoh mentah di blueprint Bagian 2.1: [ContentResolver] di-inject lewat
 * constructor (bukan diakses langsung dari dalam class seolah class ini punya Context),
 * supaya class ini gampang di-unit-test tanpa perlu Context Android asli.
 *
 * Dijalankan di [Dispatchers.IO] karena ini I/O blocking (checklist Bagian 9: project
 * besar >100 file tidak boleh bikin UI freeze saat build file tree).
 */
class ZipExtractor(
    private val contentResolver: ContentResolver
) {

    /**
     * @param zipUri Uri ZIP hasil pemilihan user (dari ActivityResultContracts.OpenDocument)
     * @param destDir Folder tujuan ekstraksi, biasanya filesDir/projects/<projectId>/
     */
    suspend fun extract(zipUri: Uri, destDir: File): ExtractResult = withContext(Dispatchers.IO) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw ZipExtractorException("Gagal membuat folder tujuan: ${destDir.absolutePath}")
        }

        val entries = mutableListOf<ProjectFile>()
        val canonicalDestDir = destDir.canonicalFile

        val inputStream = contentResolver.openInputStream(zipUri)
            ?: throw ZipExtractorException("Tidak bisa membuka ZIP dari URI yang dipilih")

        inputStream.use { rawStream ->
            ZipInputStream(rawStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val normalizedName = entry.name.replace('\\', '/')
                        val outFile = File(destDir, normalizedName)

                        // Cegah Zip Slip: pastikan hasil resolve tetap di dalam destDir.
                        // Bukan bagian eksplisit blueprint, tapi wajib untuk keamanan ekstraksi
                        // ZIP dari sumber tidak terpercaya — sengaja saya tambahkan.
                        val canonicalOutFile = outFile.canonicalFile
                        if (!canonicalOutFile.path.startsWith(canonicalDestDir.path + File.separator)) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zis.copyTo(output) }

                        entries.add(
                            ProjectFile(
                                path = normalizedName,
                                sizeBytes = outFile.length(),
                                isBinary = isBinaryFile(normalizedName)
                            )
                        )
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (entries.isEmpty()) {
            throw ZipExtractorException("ZIP kosong atau tidak berisi file yang bisa dibaca")
        }

        ExtractResult(
            fileTree = FileTreeBuilder.build(entries),
            projectDir = destDir
        )
    }

    private fun isBinaryFile(name: String): Boolean {
        val binaryExt = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico",
            "so", "apk", "aar", "jar", "zip", "class", "dex",
            "ttf", "otf", "woff", "woff2",
            "mp3", "mp4", "wav", "ogg", "pdf"
        )
        return binaryExt.contains(name.substringAfterLast('.', "").lowercase())
    }
}
