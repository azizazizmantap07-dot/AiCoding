package com.kaneki.aicoder.data.local

import android.content.Context
import java.io.File

/**
 * Simpan hasil extract di context.filesDir/projects/<projectId>/ — bukan cache,
 * sesuai catatan Bagian 2.1 blueprint, supaya tidak dihapus otomatis oleh sistem
 * saat storage penuh.
 */
class ZipFileStore(private val context: Context) {

    fun projectDir(projectId: String): File =
        File(context.filesDir, "projects/$projectId")

    /** Direktori kosong untuk mode "chat bebas" (tanpa project di-import). */
    fun freeChatDir(): File =
        File(context.filesDir, "free_chat_empty").apply { mkdirs() }

    fun deleteProject(projectId: String): Boolean {
        val dir = projectDir(projectId)
        return !dir.exists() || dir.deleteRecursively()
    }
}
