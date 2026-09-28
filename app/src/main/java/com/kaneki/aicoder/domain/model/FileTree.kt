package com.kaneki.aicoder.domain.model

/**
 * Node dalam pohon file/folder. Folder tidak punya [file] (null), hanya [children].
 * File daun (leaf) punya [file] terisi dan [children] kosong.
 */
data class FileTreeNode(
    val name: String,
    val fullPath: String, // path relatif lengkap dari root, "" untuk root itu sendiri
    val isDirectory: Boolean,
    val file: ProjectFile? = null,
    val children: List<FileTreeNode> = emptyList()
)

/**
 * Representasi seluruh struktur project: baik sebagai flat list [ProjectFile]
 * (dipakai untuk kirim daftar path ke AI, lihat Bagian 2.2 blueprint) maupun
 * sebagai tree bertingkat [root] (dipakai FileTreeView & listDirectory tool nanti).
 *
 * Dirancang supaya API-nya ([listChildren], [isBinary], [textFiles]) sudah cocok
 * dipakai langsung oleh ToolExecutor di Tahap 4 tanpa perlu diubah lagi.
 */
class FileTree(
    val files: List<ProjectFile>,
    val root: FileTreeNode
) {
    private val filesByPath: Map<String, ProjectFile> = files.associateBy { it.path }
    private val nodesByPath: Map<String, FileTreeNode> = buildMap {
        fun visit(node: FileTreeNode) {
            put(node.fullPath, node)
            node.children.forEach { visit(it) }
        }
        visit(root)
    }

    val totalFileCount: Int get() = files.size
    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Daftar anak langsung (file & folder) dari sebuah path folder. "" = root. */
    fun listChildren(path: String): List<FileTreeNode> {
        val normalized = path.trim('/')
        return nodesByPath[normalized]?.children ?: emptyList()
    }

    fun isBinary(path: String): Boolean = filesByPath[path.trim('/')]?.isBinary ?: false

    fun exists(path: String): Boolean = filesByPath.containsKey(path.trim('/'))

    /** Semua path file teks (non-biner) — dipakai search_code tool nanti. */
    fun textFiles(): List<String> = files.filter { !it.isBinary }.map { it.path }

    companion object {
        val EMPTY = FileTree(emptyList(), FileTreeNode(name = "", fullPath = "", isDirectory = true))
    }
}
