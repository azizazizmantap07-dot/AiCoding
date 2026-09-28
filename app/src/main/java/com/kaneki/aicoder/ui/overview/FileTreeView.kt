package com.kaneki.aicoder.ui.overview

import com.kaneki.aicoder.domain.util.FormatUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.FileTreeNode

/**
 * LazyColumn dengan item recursive untuk folder tree, expand/collapse per node
 * (state per node, bukan global), sesuai Bagian 6.2 blueprint.
 *
 * Diimplementasikan dengan "flatten on demand": setiap kali expandedPaths berubah,
 * daftar baris yang terlihat dihitung ulang secara ringan (bukan re-flatten
 * seluruh tree ribuan node sekaligus tiap frame — cukup untuk skala project wajar).
 */
@Composable
fun FileTreeView(
    fileTree: FileTree,
    modifier: Modifier = Modifier
) {
    var expandedPaths by remember(fileTree) {
        // Buka folder level pertama agar struktur langsung terlihat
        val firstLevel = fileTree.root.children
            .filter { it.isDirectory }
            .map { it.fullPath }
            .toSet()
        mutableStateOf(firstLevel)
    }

    val visibleRows = remember(fileTree, expandedPaths) {
        buildVisibleRows(fileTree.root, depth = 0, expandedPaths = expandedPaths)
    }

    LazyColumn(modifier = modifier) {
        items(visibleRows, key = { it.node.fullPath.ifEmpty { "__root__" } }) { row ->
            FileTreeRow(
                row = row,
                onToggle = {
                    expandedPaths = if (row.node.fullPath in expandedPaths) {
                        expandedPaths - row.node.fullPath
                    } else {
                        expandedPaths + row.node.fullPath
                    }
                }
            )
        }
    }
}

private data class TreeRow(val node: FileTreeNode, val depth: Int)

private fun buildVisibleRows(
    node: FileTreeNode,
    depth: Int,
    expandedPaths: Set<String>
): List<TreeRow> {
    // Root sendiri tidak dirender sebagai baris, langsung children-nya di depth 0
    val children = node.children
    val result = mutableListOf<TreeRow>()
    for (child in children) {
        result.add(TreeRow(child, depth))
        if (child.isDirectory && child.fullPath in expandedPaths) {
            result.addAll(buildVisibleRows(child, depth + 1, expandedPaths))
        }
    }
    return result
}

@Composable
private fun FileTreeRow(row: TreeRow, onToggle: () -> Unit) {
    val node = row.node
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = node.isDirectory) { onToggle() }
            .padding(start = (16 * row.depth).dp, top = 6.dp, bottom = 6.dp, end = 8.dp)
    ) {
        Column {
            val prefix = if (node.isDirectory) "\uD83D\uDCC1" else "\uD83D\uDCC4"
            val sizeLabel = node.file?.let { " (${FormatUtils.formatByteSize(it.sizeBytes)})" } ?: ""
            Text(text = "$prefix ${node.name}$sizeLabel", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

