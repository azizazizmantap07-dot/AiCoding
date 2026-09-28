package com.kaneki.aicoder.domain.util

/**
 * Helper format ukuran file / byte yang dipakai di beberapa layer (UI + domain tools).
 * Sebelumnya fungsi identik diulang di ChatViewModel, ToolExecutor, ExportScreen,
 * FileTreeView, dan UploadScreen.
 */
object FormatUtils {

    fun formatByteSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
