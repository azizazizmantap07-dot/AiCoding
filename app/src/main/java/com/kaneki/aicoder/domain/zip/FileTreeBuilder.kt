package com.kaneki.aicoder.domain.zip

import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.FileTreeNode
import com.kaneki.aicoder.domain.model.ProjectFile

/**
 * Membangun [FileTree] bertingkat dari flat list [ProjectFile] hasil ZipExtractor.
 * Lihat Bagian 2.2 blueprint — tree ini dipakai baik untuk representasi teks
 * (daftar path dikirim ke AI) maupun untuk render FileTreeView di UI.
 */
object FileTreeBuilder {

    private class MutableDirNode(val name: String, val fullPath: String) {
        val childDirs = LinkedHashMap<String, MutableDirNode>()
        val childFiles = mutableListOf<ProjectFile>()
    }

    fun build(files: List<ProjectFile>): FileTree {
        val root = MutableDirNode(name = "", fullPath = "")

        for (file in files) {
            val segments = file.path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue

            var current = root
            // Semua segmen kecuali yang terakhir adalah folder
            for (i in 0 until segments.size - 1) {
                val segment = segments[i]
                val childPath = if (current.fullPath.isEmpty()) segment else "${current.fullPath}/$segment"
                current = current.childDirs.getOrPut(segment) { MutableDirNode(segment, childPath) }
            }
            current.childFiles.add(file)
        }

        fun toNode(dir: MutableDirNode): FileTreeNode {
            val dirChildren = dir.childDirs.values
                .map { toNode(it) }
                .sortedBy { it.name.lowercase() }
            val fileChildren = dir.childFiles
                .sortedBy { it.path.substringAfterLast('/').lowercase() }
                .map { pf ->
                    FileTreeNode(
                        name = pf.path.substringAfterLast('/'),
                        fullPath = pf.path,
                        isDirectory = false,
                        file = pf
                    )
                }
            return FileTreeNode(
                name = dir.name,
                fullPath = dir.fullPath,
                isDirectory = true,
                children = dirChildren + fileChildren
            )
        }

        return FileTree(files = files, root = toNode(root))
    }
}
