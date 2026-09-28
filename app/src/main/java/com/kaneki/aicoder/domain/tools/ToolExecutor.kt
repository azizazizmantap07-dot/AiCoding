package com.kaneki.aicoder.domain.tools

import com.kaneki.aicoder.domain.util.FormatUtils
import com.kaneki.aicoder.domain.model.FileTree
import com.kaneki.aicoder.domain.model.PendingChange
import com.kaneki.aicoder.domain.model.ToolCall
import java.io.File

/**
 * Eksekusi tools terhadap file hasil extract (Bagian 3.4 + 4.3 blueprint).
 *
 * write_file hanya staging. read_file menolak file >300 baris tanpa range.
 * search_code membatasi 50 match; opsional sertakan cuplikan batas fungsi
 * heuristik (Bagian 4.3) di sekitar match pertama per file.
 *
 * rename_file juga hanya staging: dicatat di [renames] (path baru -> path
 * lama) supaya [snapshotPendingChanges] bisa membentuk PendingChange dengan
 * [PendingChange.oldPath] terisi. ChangeApplier yang nanti benar-benar
 * menghapus file lama & menulis file baru di disk saat user approve.
 */
class ToolExecutor(
    private val projectDir: File,
    private val fileTree: FileTree,
    private val pendingChanges: MutableMap<String, String>,
    private val renames: MutableMap<String, String> = mutableMapOf()
) {
    companion object {
        const val LARGE_FILE_LINE_THRESHOLD = 300
        const val MAX_SEARCH_MATCHES = 50
    }

    fun execute(call: ToolCall): String = when (call.name) {
        "list_directory" -> listDirectory(argString(call, "path") ?: "")
        "read_file" -> readFile(
            path = argString(call, "path") ?: "",
            startLine = argInt(call, "start_line"),
            endLine = argInt(call, "end_line")
        )
        "search_code" -> searchCode(argString(call, "query") ?: "")
        "write_file" -> writeFile(
            path = argString(call, "path") ?: "",
            content = argString(call, "content") ?: ""
        )
        "rename_file" -> renameFile(
            oldPath = argString(call, "old_path") ?: "",
            newPath = argString(call, "new_path") ?: ""
        )
        else -> "Error: tool tidak dikenali: ${call.name}"
    }

    fun snapshotPendingChanges(): List<PendingChange> {
        val renamedNewPaths = renames.keys

        val fromRenames = renames.map { (newPath, oldPath) ->
            val content = pendingChanges[newPath] ?: readOriginalContent(oldPath)
            PendingChange(
                path = newPath,
                originalContent = readOriginalContent(oldPath),
                newContent = content,
                oldPath = oldPath
            )
        }

        val fromWrites = pendingChanges
            .filterKeys { it !in renamedNewPaths }
            .map { (path, newContent) ->
                PendingChange(
                    path = path,
                    originalContent = readOriginalContent(path),
                    newContent = newContent
                )
            }

        return fromRenames + fromWrites
    }

    private fun listDirectory(path: String): String {
        val normalized = path.trim().trim('/').let { if (it == ".") "" else it }
        val children = fileTree.listChildren(normalized)
        if (children.isEmpty()) {
            return if (normalized.isEmpty()) {
                "Root project kosong atau path tidak ditemukan."
            } else {
                "Folder kosong atau path tidak ditemukan: $normalized"
            }
        }
        val oldToNewRenamed = renames.values.toSet()
        val listing = children.joinToString("\n") { node ->
            if (node.isDirectory) {
                "📁 ${node.name}/"
            } else {
                val size = node.file?.sizeBytes ?: 0L
                val binary = if (node.file?.isBinary == true) " [binary]" else ""
                val renamedNote = if (node.fullPath in oldToNewRenamed) {
                    val newPath = renames.entries.first { it.value == node.fullPath }.key
                    " [staging: sudah di-rename ke \"$newPath\"]"
                } else ""
                "📄 ${node.name} (${FormatUtils.formatByteSize(size)})$binary$renamedNote"
            }
        }
        val newFromRenames = renames.keys
            .filter { it.substringBeforeLast('/', "") == normalized && !fileTree.exists(it) }
            .joinToString("\n") { "📄 ${it.substringAfterLast('/')} [staging: baru, hasil rename]" }

        return if (newFromRenames.isBlank()) listing else "$listing\n$newFromRenames".trim('\n')
    }

    private fun readFile(path: String, startLine: Int?, endLine: Int?): String {
        val requested = path.trim().trim('/')
        if (requested.isEmpty()) return "Error: path kosong"

        // Kalau path yang diminta adalah path LAMA dari rename yang sudah di-staging,
        // arahkan ke path baru supaya model tidak "membaca" file yang secara logis
        // sudah tidak ada lagi di staging.
        val renamedTo = renames.entries.find { it.value == requested }?.key
        if (renamedTo != null) {
            return "File ini sudah di-rename (staging) ke \"$renamedTo\". " +
                "Gunakan path \"$renamedTo\" untuk membaca/menulisnya."
        }

        val normalized = requested

        val staged = pendingChanges[normalized]
        if (staged != null) {
            return sliceLines(staged.lines(), startLine, endLine, normalized)
        }

        // Path baru hasil rename yang belum ada write_file lanjutan -> isinya
        // sama seperti file lama, ambil dari situ.
        val oldPathForRename = renames[normalized]
        if (oldPathForRename != null) {
            val oldFile = File(projectDir, oldPathForRename)
            val lines = try {
                oldFile.readLines()
            } catch (e: Exception) {
                return "Error: gagal membaca file: ${e.message}"
            }
            return sliceLines(lines, startLine, endLine, normalized)
        }

        val file = File(projectDir, normalized)
        if (!file.exists() || !file.isFile) {
            return "Error: file tidak ditemukan di $normalized"
        }
        if (fileTree.isBinary(normalized)) {
            return "Error: file ini biner, tidak bisa dibaca sebagai teks"
        }

        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            return "Error: gagal membaca file: ${e.message}"
        }

        if (lines.size > LARGE_FILE_LINE_THRESHOLD && startLine == null) {
            return "File ini memiliki ${lines.size} baris. Gunakan search_code untuk " +
                "menemukan bagian relevan, lalu baca dengan start_line/end_line " +
                "(threshold $LARGE_FILE_LINE_THRESHOLD baris)."
        }

        return sliceLines(lines, startLine, endLine, normalized)
    }

    private fun sliceLines(
        lines: List<String>,
        startLine: Int?,
        endLine: Int?,
        path: String
    ): String {
        if (startLine != null && endLine != null) {
            val start = (startLine - 1).coerceIn(0, lines.size)
            val end = endLine.coerceIn(0, lines.size)
            if (start >= end) {
                return "Error: rentang baris tidak valid ($startLine-$endLine) untuk $path (${lines.size} baris)"
            }
            val selected = lines.subList(start, end)
            return selected.mapIndexed { i, line ->
                "${start + i + 1}| $line"
            }.joinToString("\n")
        }
        return lines.mapIndexed { i, line -> "${i + 1}| $line" }.joinToString("\n")
    }

    private fun searchCode(query: String): String {
        if (query.isBlank()) return "Error: query kosong"
        val matches = mutableListOf<String>()
        val filesWithMatch = mutableSetOf<String>()

        // Path di disk yang sudah di-rename (staging) diganti representasinya
        // dengan path barunya, supaya hasil search tidak menunjuk ke path lama
        // yang secara logis sudah tidak berlaku lagi. Tambahkan juga path-path
        // baru hasil rename yang isinya berasal murni dari rename (belum masuk
        // fileTree karena belum di-apply ke disk).
        val oldToNewRenamed = renames.values.toSet()
        val searchPaths = fileTree.textFiles()
            .filterNot { it in oldToNewRenamed }
            .toMutableList()
        renames.keys.forEach { newPath -> if (newPath !in searchPaths) searchPaths.add(newPath) }

        searchPaths.forEach { path ->
            val lines = readLinesForSearch(path) ?: return@forEach
            lines.forEachIndexed { idx, line ->
                if (line.contains(query, ignoreCase = true)) {
                    matches.add("$path:${idx + 1}: ${line.trim()}")
                    if (path !in filesWithMatch && matches.size <= MAX_SEARCH_MATCHES) {
                        // Cuplikan batas fungsi heuristik (Bagian 4.3) — sekali per file
                        val range = findFunctionBoundaries(lines, idx)
                        if (range.last - range.first > 0) {
                            matches.add(
                                "  └ context $path:${range.first + 1}-${range.last + 1} " +
                                    "(~${range.last - range.first + 1} baris; gunakan read_file)"
                            )
                        }
                        filesWithMatch.add(path)
                    }
                }
            }
        }
        return if (matches.isEmpty()) {
            "Tidak ditemukan."
        } else {
            matches.take(MAX_SEARCH_MATCHES).joinToString("\n")
        }
    }

    private fun readLinesForSearch(path: String): List<String>? {
        val content = pendingChanges[path]
        if (content != null) return content.lines()

        // Path baru hasil rename yang belum diedit ulang -> baca dari file lama.
        val oldPathForRename = renames[path]
        if (oldPathForRename != null) {
            val oldFile = File(projectDir, oldPathForRename)
            return try {
                oldFile.readLines()
            } catch (_: Exception) {
                null
            }
        }

        val file = File(projectDir, path)
        if (!file.exists()) return null
        return try {
            file.readLines()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Heuristik batas fungsi/method di sekitar [matchLine] (0-based).
     * Cukup untuk Kotlin/Java/JS — bukan AST parser penuh (Bagian 4.3).
     */
    fun findFunctionBoundaries(lines: List<String>, matchLine: Int): IntRange {
        if (lines.isEmpty()) return matchLine..matchLine
        val safeMatch = matchLine.coerceIn(0, lines.lastIndex)

        var start = safeMatch
        var depth = 0
        while (start > 0) {
            val line = lines[start]
            if (line.contains("}")) depth++
            if (line.contains("{")) depth--
            val isDecl = line.contains(Regex("""\b(fun|function|void|class|interface|object)\b"""))
            if (isDecl && depth <= 0) break
            start--
        }

        var end = safeMatch
        depth = 0
        var seenOpen = false
        while (end < lines.size) {
            val line = lines[end]
            if (line.contains("{")) {
                depth++
                seenOpen = true
            }
            if (line.contains("}")) {
                depth--
                if (seenOpen && depth <= 0) {
                    end++
                    break
                }
            }
            end++
            // Batasi cuplikan context agar tidak membengkak
            if (end - start > 80) break
        }
        return start until end.coerceAtMost(lines.size)
    }

    private fun writeFile(path: String, content: String): String {
        val normalized = path.trim().trim('/')
        if (normalized.isEmpty()) return "Error: path kosong"
        if (content.isEmpty()) {
            return "Error: content kosong — write_file membutuhkan SELURUH isi file."
        }

        // Path lama yang sudah di-rename (staging) tidak boleh ditulis lagi —
        // arahkan ke rename_file/path baru supaya tidak muncul dua entri untuk
        // file yang sama.
        val renamedTo = renames.entries.find { it.value == normalized }?.key
        if (renamedTo != null) {
            return "Error: \"$normalized\" sudah di-rename (staging) ke \"$renamedTo\". " +
                "Panggil write_file dengan path=\"$renamedTo\"."
        }

        // Guard: project single-file (mis. hasil import .txt/.kt tunggal) hanya
        // boleh ditulis ke path aslinya (atau path hasil rename_file yang sah).
        // Ini mencegah model membuat file baru seperti "nama_fixed.txt" sebagai
        // cara menyalin isi file lama, yang akan mengubah project single-file
        // menjadi multi-file secara tidak sengaja.
        if (fileTree.totalFileCount == 1 && !fileTree.exists(normalized) && normalized !in renames) {
            val originalPath = fileTree.textFiles().firstOrNull()
            if (originalPath != null) {
                return "Error: path \"$normalized\" tidak sama dengan file project (\"$originalPath\"). " +
                    "Jangan membuat file baru untuk menyimpan hasil edit — panggil ulang write_file " +
                    "dengan path=\"$originalPath\" agar hasil edit menimpa file yang sama, atau " +
                    "gunakan tool rename_file kalau memang ingin mengganti nama file tersebut."
            }
        }

        pendingChanges[normalized] = content
        return "Perubahan disimpan sementara (staging) untuk $normalized, menunggu review user."
    }

    private fun renameFile(oldPath: String, newPath: String): String {
        val from = oldPath.trim().trim('/')
        val to = newPath.trim().trim('/')
        if (from.isEmpty() || to.isEmpty()) {
            return "Error: old_path dan new_path tidak boleh kosong."
        }
        if (from == to) {
            return "Error: old_path dan new_path sama — tidak ada yang perlu di-rename."
        }

        // Sumber harus benar-benar ada: di disk, atau path baru dari rename lain
        // yang sudah di-staging sebelumnya (rename berantai).
        val sourceExistsOnDisk = fileTree.exists(from) || File(projectDir, from).let { it.exists() && it.isFile }
        val sourceIsStagedNewFile = pendingChanges.containsKey(from) && !fileTree.exists(from)
        val sourceIsPriorRenameTarget = renames.containsKey(from)
        if (!sourceExistsOnDisk && !sourceIsStagedNewFile && !sourceIsPriorRenameTarget) {
            return "Error: file sumber tidak ditemukan: $from"
        }
        if (fileTree.exists(from) && fileTree.isBinary(from)) {
            return "Error: file ini biner. rename_file hanya untuk file teks yang bisa diedit AI " +
                "(untuk file biner, buat ulang project via import/edit manual)."
        }

        // Tujuan tidak boleh menimpa file lain yang sudah ada (dan bukan file
        // yang sedang di-rename ini sendiri).
        val destExists = fileTree.exists(to) || File(projectDir, to).let { it.exists() && it.isFile }
        if (destExists) {
            return "Error: path tujuan \"$to\" sudah dipakai file lain. Pilih nama/path lain, " +
                "atau hapus dulu file tujuan kalau memang ingin menimpanya."
        }

        // Guard single-file project, sama seperti write_file: rename tetap harus
        // menyasar satu-satunya file yang ada.
        if (fileTree.totalFileCount == 1 && !fileTree.exists(from) && from !in renames) {
            val originalPath = fileTree.textFiles().firstOrNull()
            if (originalPath != null && from != originalPath) {
                return "Error: old_path \"$from\" tidak sama dengan file project (\"$originalPath\")."
            }
        }

        // Kalau `from` adalah path baru dari rename sebelumnya (rename berantai),
        // gabungkan jadi satu entri: hapus entri lama, arahkan ke path asal aslinya.
        val trueOldPath = renames.remove(from) ?: from
        val stagedContent = pendingChanges.remove(from)

        renames[to] = trueOldPath
        if (stagedContent != null) {
            pendingChanges[to] = stagedContent
        }

        return "File di-rename (staging) dari \"$from\" ke \"$to\", menunggu review user. " +
            "Belum permanen sampai user approve di layar Diff."
    }

    private fun readOriginalContent(path: String): String {
        val file = File(projectDir, path)
        if (!file.exists() || !file.isFile) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    private fun argString(call: ToolCall, key: String): String? {
        val v = call.arguments[key] ?: return null
        return when (v) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> v.toString()
        }
    }

    private fun argInt(call: ToolCall, key: String): Int? {
        val v = call.arguments[key] ?: return null
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is Float -> v.toInt()
            is String -> v.toIntOrNull()
            is Number -> v.toInt()
            else -> null
        }
    }

}
