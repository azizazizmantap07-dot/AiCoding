package com.kaneki.aicoder.domain.zip

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simpan ZIP ke Downloads (MediaStore) dan siapkan Intent share.
 * minSdk 26 → MediaStore Downloads collection aman di API 29+;
 * di API 26–28 tulis ke public Downloads via getExternalStoragePublicDirectory
 * (permission WRITE_EXTERNAL_STORAGE tidak diminta di manifest karena
 * target utama API 29+ path; API 26–28 masih bisa gagal tanpa permission —
 * fallback: cache dir + share saja).
 */
class ZipExporter(private val context: Context) {

    /**
     * Salin [file] ke folder Downloads dengan nama [displayName].
     * [mimeType] default application/zip; untuk file tunggal pakai mime yang sesuai (text/plain, dll.).
     * @return Uri hasil (content:// atau file://) atau null jika gagal.
     */
    suspend fun saveToDownloads(
        file: File,
        displayName: String,
        mimeType: String = "application/zip"
    ): Uri? =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(file, displayName, mimeType)
                } else {
                    saveViaLegacyPublicDownloads(file, displayName)
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Intent ACTION_SEND untuk membagikan file lewat FileProvider.
     */
    fun createShareIntent(
        file: File,
        displayName: String,
        mimeType: String = "application/zip"
    ): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun saveViaMediaStore(file: File, displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: run {
            resolver.delete(itemUri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }
        return itemUri
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyPublicDownloads(file: File, displayName: String): Uri? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() && !downloads.mkdirs()) return null
        val dest = File(downloads, displayName)
        file.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    companion object {
        /** Mime type sederhana berdasarkan ekstensi file. */
        fun mimeForFileName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "txt", "log", "md", "markdown" -> "text/plain"
                "kt", "kts" -> "text/x-kotlin"
                "java" -> "text/x-java-source"
                "py" -> "text/x-python"
                "js", "mjs", "cjs" -> "text/javascript"
                "ts", "tsx" -> "text/typescript"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "csv" -> "text/csv"
                "yaml", "yml" -> "text/yaml"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        }
    }
}
