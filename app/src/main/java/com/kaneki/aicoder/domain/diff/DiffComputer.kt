package com.kaneki.aicoder.domain.diff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.kaneki.aicoder.domain.model.DiffLine
import com.kaneki.aicoder.domain.model.DiffLineType

/**
 * Hitung line-by-line diff memakai java-diff-utils (Myers),
 * sesuai Bagian 6.4 blueprint.
 *
 * Kasus khusus:
 * - original kosong + new terisi → semua INSERT (file baru)
 * - original terisi + new kosong → semua DELETE (file dihapus total)
 */
object DiffComputer {

    fun compute(originalContent: String, newContent: String): List<DiffLine> {
        val originalLines = originalContent.lines()
        val newLines = newContent.lines()

        // lines() pada string kosong menghasilkan listOf("") — normalisasi
        val oldList = if (originalContent.isEmpty()) emptyList() else originalLines
        val newList = if (newContent.isEmpty()) emptyList() else newLines

        if (oldList.isEmpty() && newList.isEmpty()) return emptyList()

        val patch = DiffUtils.diff(oldList, newList)
        val result = mutableListOf<DiffLine>()

        var oldIdx = 0 // 0-based index ke oldList
        var newIdx = 0

        for (delta in patch.deltas) {
            // Equal prefix sebelum delta ini
            while (oldIdx < delta.source.position) {
                result.add(
                    DiffLine(
                        type = DiffLineType.EQUAL,
                        text = oldList[oldIdx],
                        oldLineNumber = oldIdx + 1,
                        newLineNumber = newIdx + 1
                    )
                )
                oldIdx++
                newIdx++
            }

            when (delta.type) {
                DeltaType.INSERT -> {
                    for (line in delta.target.lines) {
                        result.add(
                            DiffLine(
                                type = DiffLineType.INSERT,
                                text = line,
                                oldLineNumber = null,
                                newLineNumber = newIdx + 1
                            )
                        )
                        newIdx++
                    }
                }
                DeltaType.DELETE -> {
                    for (line in delta.source.lines) {
                        result.add(
                            DiffLine(
                                type = DiffLineType.DELETE,
                                text = line,
                                oldLineNumber = oldIdx + 1,
                                newLineNumber = null
                            )
                        )
                        oldIdx++
                    }
                }
                DeltaType.CHANGE -> {
                    for (line in delta.source.lines) {
                        result.add(
                            DiffLine(
                                type = DiffLineType.DELETE,
                                text = line,
                                oldLineNumber = oldIdx + 1,
                                newLineNumber = null
                            )
                        )
                        oldIdx++
                    }
                    for (line in delta.target.lines) {
                        result.add(
                            DiffLine(
                                type = DiffLineType.INSERT,
                                text = line,
                                oldLineNumber = null,
                                newLineNumber = newIdx + 1
                            )
                        )
                        newIdx++
                    }
                }
                else -> {
                    // EQUAL delta tidak muncul di patch.deltas (hanya change/insert/delete)
                }
            }
        }

        // Sisa equal di akhir
        while (oldIdx < oldList.size) {
            result.add(
                DiffLine(
                    type = DiffLineType.EQUAL,
                    text = oldList[oldIdx],
                    oldLineNumber = oldIdx + 1,
                    newLineNumber = newIdx + 1
                )
            )
            oldIdx++
            newIdx++
        }

        return result
    }
}
