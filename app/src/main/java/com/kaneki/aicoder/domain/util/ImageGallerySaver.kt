package com.kaneki.aicoder.domain.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Simpan file gambar ke album Galeri publik: Pictures/AI Coder.
 * Di Android 10+ memakai MediaStore (tanpa permission WRITE_EXTERNAL_STORAGE).
 */
object ImageGallerySaver {

    private const val ALBUM = "AI Coder"

    sealed interface Result {
        data class Success(val uri: Uri) : Result
        data class Error(val message: String) : Result
    }

    fun saveImageFile(context: Context, sourcePath: String): Result {
        val source = File(sourcePath)
        if (!source.exists() || !source.isFile) {
            return Result.Error("File gambar tidak ditemukan")
        }
        val mime = guessMime(source.name)
        val displayName = source.name.ifBlank {
            "aicoder_${System.currentTimeMillis()}.png"
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values)
                ?: return Result.Error("Gagal membuat entri MediaStore")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: return Result.Error("Gagal menulis file ke Galeri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Result.Success(uri)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Gagal menyimpan ke Galeri")
        }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            else -> "image/png"
        }
    }
}
