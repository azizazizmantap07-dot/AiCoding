package com.kaneki.aicoder.ui.diff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Highlighter ringan berbasis regex untuk diff viewer.
 * Mendukung Kotlin/Java, XML, JSON, Gradle — cukup untuk baca cepat,
 * bukan parser AST penuh.
 */
object SyntaxHighlighter {

    private val keywordColor = Color(0xFFCE93D8)      // ungu
    private val stringColor = Color(0xFFA5D6A7)       // hijau
    private val commentColor = Color(0xFF9E9E9E)      // abu
    private val numberColor = Color(0xFFFFCC80)       // oranye
    private val typeColor = Color(0xFF90CAF9)         // biru
    private val annotationColor = Color(0xFFFFF59D)   // kuning

    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed",
        "if", "else", "when", "for", "while", "return", "import", "package",
        "true", "false", "null", "is", "in", "as", "try", "catch", "finally",
        "throw", "super", "this", "private", "public", "protected", "internal",
        "override", "open", "abstract", "companion", "suspend", "inline",
        "reified", "typealias", "enum", "const", "lateinit", "by", "where",
        "get", "set", "out", "actual", "expect", "typealias"
    )

    private val javaKeywords = kotlinKeywords + setOf(
        "void", "int", "long", "boolean", "static", "final", "new", "extends",
        "implements", "throws", "synchronized", "volatile", "native", "strictfp"
    )

    fun highlight(line: String, filePath: String): AnnotatedString {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return highlightByExt(line, ext)
    }

    /**
     * Highlight berdasarkan label bahasa markdown (```kotlin, ```python, dll).
     * Digunakan di code block chat.
     */
    fun highlightByLanguage(code: String, language: String): AnnotatedString {
        val lang = language.lowercase().trim()
        val ext = when (lang) {
            "kotlin", "kt", "kts" -> "kt"
            "java" -> "java"
            "python", "py" -> "py"
            "javascript", "js", "jsx", "typescript", "ts", "tsx" -> "js"
            "xml", "html", "htm", "svg" -> "xml"
            "json", "jsonc" -> "json"
            "gradle", "groovy" -> "gradle"
            "c", "cpp", "c++", "h", "hpp", "csharp", "cs", "go", "rust", "rs",
            "swift", "dart", "ruby", "rb", "php", "shell", "bash", "sh", "sql" -> "kt" // pakai keyword set generik
            else -> lang.ifBlank { "" }
        }
        // Highlight per baris agar komentar // tidak merusak baris berikutnya
        if (!code.contains('\n')) return highlightByExt(code, ext)
        return buildAnnotatedString {
            val lines = code.split('\n')
            lines.forEachIndexed { idx, line ->
                if (idx > 0) append('\n')
                append(highlightByExt(line, ext))
            }
        }
    }

    private fun highlightByExt(line: String, ext: String): AnnotatedString {
        return when (ext) {
            "kt", "kts", "java" -> highlightCode(line, javaKeywords)
            "xml", "html", "htm" -> highlightXml(line)
            "json" -> highlightJson(line)
            "gradle", "groovy" -> highlightCode(line, javaKeywords)
            "py" -> highlightCode(line, pythonKeywords)
            "js", "ts", "jsx", "tsx" -> highlightCode(line, jsKeywords)
            else -> {
                // Coba keyword generik bila bahasa dikenal sebagian
                if (ext.isNotEmpty()) highlightCode(line, javaKeywords)
                else AnnotatedString(line)
            }
        }
    }

    private val pythonKeywords = setOf(
        "def", "class", "if", "else", "elif", "for", "while", "return",
        "import", "from", "as", "True", "False", "None", "with", "try",
        "except", "finally", "raise", "pass", "yield", "async", "await",
        "lambda", "in", "is", "not", "and", "or", "global", "nonlocal",
        "assert", "break", "continue", "del", "print"
    )

    private val jsKeywords = setOf(
        "function", "const", "let", "var", "if", "else", "for", "while",
        "return", "import", "export", "from", "as", "class", "extends",
        "new", "this", "super", "true", "false", "null", "undefined",
        "async", "await", "try", "catch", "finally", "throw", "typeof",
        "instanceof", "switch", "case", "break", "continue", "default",
        "of", "in", "yield", "interface", "type", "enum"
    )

    private fun highlightCode(line: String, keywords: Set<String>): AnnotatedString {
        // komentar
        val commentIdx = line.indexOf("//")
        val codePart = if (commentIdx >= 0) line.substring(0, commentIdx) else line
        val commentPart = if (commentIdx >= 0) line.substring(commentIdx) else null

        return buildAnnotatedString {
            var i = 0
            while (i < codePart.length) {
                when {
                    codePart[i] == '"' || codePart[i] == '\'' -> {
                        val quote = codePart[i]
                        val start = i
                        i++
                        while (i < codePart.length && codePart[i] != quote) {
                            if (codePart[i] == '\\' && i + 1 < codePart.length) i++
                            i++
                        }
                        if (i < codePart.length) i++
                        withStyle(SpanStyle(color = stringColor)) {
                            append(codePart.substring(start, i))
                        }
                    }
                    codePart[i] == '@' -> {
                        val start = i
                        i++
                        while (i < codePart.length && (codePart[i].isLetterOrDigit() || codePart[i] == '_')) i++
                        withStyle(SpanStyle(color = annotationColor)) {
                            append(codePart.substring(start, i))
                        }
                    }
                    codePart[i].isDigit() -> {
                        val start = i
                        while (i < codePart.length && (codePart[i].isDigit() || codePart[i] == '.' || codePart[i] == 'f' || codePart[i] == 'L')) i++
                        withStyle(SpanStyle(color = numberColor)) {
                            append(codePart.substring(start, i))
                        }
                    }
                    codePart[i].isLetter() || codePart[i] == '_' -> {
                        val start = i
                        while (i < codePart.length && (codePart[i].isLetterOrDigit() || codePart[i] == '_')) i++
                        val word = codePart.substring(start, i)
                        when {
                            word in keywords -> withStyle(
                                SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)
                            ) { append(word) }
                            word.first().isUpperCase() && word.length > 1 -> withStyle(
                                SpanStyle(color = typeColor)
                            ) { append(word) }
                            else -> append(word)
                        }
                    }
                    else -> {
                        append(codePart[i])
                        i++
                    }
                }
            }
            if (commentPart != null) {
                withStyle(SpanStyle(color = commentColor)) {
                    append(commentPart)
                }
            }
        }
    }

    private fun highlightXml(line: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("<!--", i) -> {
                    val end = line.indexOf("-->", i).let { if (it < 0) line.length else it + 3 }
                    withStyle(SpanStyle(color = commentColor)) {
                        append(line.substring(i, end))
                    }
                    i = end
                }
                line[i] == '<' -> {
                    val start = i
                    while (i < line.length && line[i] != '>' && line[i] != ' ') i++
                    withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) {
                        append(line.substring(start, i))
                    }
                }
                line[i] == '"' -> {
                    val start = i
                    i++
                    while (i < line.length && line[i] != '"') i++
                    if (i < line.length) i++
                    withStyle(SpanStyle(color = stringColor)) {
                        append(line.substring(start, i))
                    }
                }
                else -> {
                    append(line[i])
                    i++
                }
            }
        }
    }

    private fun highlightJson(line: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '"' -> {
                    val start = i
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length) i++
                        i++
                    }
                    if (i < line.length) i++
                    // key vs string value: kasar — jika diikuti : maka key
                    val slice = line.substring(start, i)
                    val rest = line.substring(i).trimStart()
                    val color = if (rest.startsWith(":")) typeColor else stringColor
                    withStyle(SpanStyle(color = color)) { append(slice) }
                }
                line[i].isDigit() || (line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                    val start = i
                    if (line[i] == '-') i++
                    while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
                    withStyle(SpanStyle(color = numberColor)) {
                        append(line.substring(start, i))
                    }
                }
                line.startsWith("true", i) || line.startsWith("false", i) || line.startsWith("null", i) -> {
                    val word = when {
                        line.startsWith("true", i) -> "true"
                        line.startsWith("false", i) -> "false"
                        else -> "null"
                    }
                    withStyle(SpanStyle(color = keywordColor)) { append(word) }
                    i += word.length
                }
                else -> {
                    append(line[i])
                    i++
                }
            }
        }
    }
}
