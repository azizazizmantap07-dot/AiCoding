package com.kaneki.aicoder.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAG_URL = "URL"

/**
 * Renderer markdown untuk bubble chat.
 *
 * Didukung:
 * - Heading # … ######
 * - **bold** / __bold__ / *italic* / _italic_ / ***bold-italic*** / ~~strike~~
 * - `inline code` dan ``` fenced code blocks ```
 * - Link [label](url) + URL mentah http(s)://
 * - Image ![alt](url) → tampil sebagai link [🖼 alt]
 * - List - * + dan 1. 2. serta task list - [ ] / - [x]
 * - Blockquote >
 * - Horizontal rule --- *** ___
 * - Tabel markdown sederhana | a | b |
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeSurface = MaterialTheme.colorScheme.surfaceVariant
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    val blocks = remember(markdown) { splitBlocks(markdown) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.CodeFence -> {
                    Text(
                        text = block.content.trimEnd(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(codeSurface, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        style = style.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    )
                }
                is MdBlock.Table -> {
                    val tableText = remember(block) {
                        buildAnnotatedString {
                            block.rows.forEachIndexed { idx, row ->
                                if (idx > 0) append('\n')
                                val weight = if (idx == 0) FontWeight.Bold else FontWeight.Normal
                                withStyle(SpanStyle(fontWeight = weight, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                                    append(row.joinToString(" │ "))
                                }
                            }
                        }
                    }
                    Text(
                        text = tableText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(codeSurface.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                        style = style
                    )
                }
                is MdBlock.Paragraph -> {
                    ClickableMarkdownLine(
                        text = parseInline(
                            text = block.prefix + block.text,
                            linkColor = linkColor,
                            codeBg = codeSurface,
                            baseStyle = when {
                                block.isQuote -> SpanStyle(
                                    color = quoteColor,
                                    fontStyle = FontStyle.Italic
                                )
                                block.headingLevel != null -> SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = when (block.headingLevel) {
                                        1 -> 22.sp
                                        2 -> 20.sp
                                        3 -> 18.sp
                                        4 -> 16.sp
                                        else -> 15.sp
                                    }
                                )
                                else -> SpanStyle()
                            }
                        ),
                        style = style,
                        color = color,
                        onUrl = { runCatching { uriHandler.openUri(it) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun ClickableMarkdownLine(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    onUrl: (String) -> Unit
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .pointerInput(text) {
                detectTapGestures { pos ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(pos)
                    text.getStringAnnotations(TAG_URL, offset, offset)
                        .firstOrNull()
                        ?.let { onUrl(it.item) }
                }
            },
        style = style,
        color = color,
        onTextLayout = { layoutResult = it }
    )
}

private sealed interface MdBlock {
    data class Paragraph(
        val text: String,
        val headingLevel: Int? = null,
        val isQuote: Boolean = false,
        val prefix: String = ""
    ) : MdBlock

    data class CodeFence(val language: String, val content: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
}

private fun splitBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Fenced code
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            out.add(MdBlock.CodeFence(lang, body.toString()))
            i++ // skip closing ```
            continue
        }

        // Table: line with | and next line is |---|
        if (trimmed.startsWith("|") && i + 1 < lines.size &&
            lines[i + 1].trim().matches(Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$"""))
        ) {
            val rows = mutableListOf<List<String>>()
            fun parseRow(raw: String): List<String> =
                raw.trim().trim('|').split('|').map { it.trim() }
            rows.add(parseRow(line))
            i += 2 // skip header + separator
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows.add(parseRow(lines[i]))
                i++
            }
            out.add(MdBlock.Table(rows))
            continue
        }

        // Blank line → skip (spacing handled by padding)
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Horizontal rule
        if (trimmed.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$"""))) {
            out.add(MdBlock.Paragraph("────────"))
            i++
            continue
        }

        // Heading
        val headingMatch = Regex("""^(#{1,6})\s+(.*)$""").find(trimmed)
        if (headingMatch != null) {
            out.add(
                MdBlock.Paragraph(
                    text = headingMatch.groupValues[2],
                    headingLevel = headingMatch.groupValues[1].length
                )
            )
            i++
            continue
        }

        // Blockquote (support multi-line consecutive)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(
                    lines[i].trimStart().removePrefix(">").removePrefix(" ").trimEnd()
                )
                i++
            }
            out.add(
                MdBlock.Paragraph(
                    text = quoteLines.joinToString("\n"),
                    isQuote = true,
                    prefix = "│ "
                )
            )
            continue
        }

        // List item (bullet, numbered, task)
        val listMatch = Regex(
            """^(\s*)([-*+]|\d+\.)\s+(\[[ xX]\]\s+)?(.*)$"""
        ).find(line)
        if (listMatch != null) {
            val indent = listMatch.groupValues[1].length
            val marker = listMatch.groupValues[2]
            val task = listMatch.groupValues[3]
            val content = listMatch.groupValues[4]
            val pad = " ".repeat((indent / 2).coerceAtMost(6) * 2)
            val bullet = when {
                task.startsWith("[x]", ignoreCase = true) ||
                    task.startsWith("[X]") -> "☑ "
                task.startsWith("[ ]") -> "☐ "
                marker.matches(Regex("""\d+\.""")) -> "$marker "
                else -> "• "
            }
            out.add(MdBlock.Paragraph(text = content, prefix = "$pad$bullet"))
            i++
            continue
        }

        // Ordinary paragraph: merge consecutive non-special lines
        val para = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            val nt = next.trimStart()
            if (nt.isEmpty()) break
            if (nt.startsWith("```") || nt.startsWith("#") || nt.startsWith(">") ||
                nt.startsWith("|") ||
                nt.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$""")) ||
                next.matches(Regex("""^\s*([-*+]|\d+\.)\s+.*"""))
            ) break
            para.append('\n').append(next)
            i++
        }
        out.add(MdBlock.Paragraph(text = para.toString()))
    }
    return out
}

private fun parseInline(
    text: String,
    linkColor: Color,
    codeBg: Color,
    baseStyle: SpanStyle = SpanStyle()
): AnnotatedString = buildAnnotatedString {
    withStyle(baseStyle) {
        appendInline(text, linkColor, codeBg)
    }
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    linkColor: Color,
    codeBg: Color
) {
    var i = 0
    while (i < text.length) {
        // Image ![alt](url)
        if (text.startsWith("![", i)) {
            val closeAlt = text.indexOf(']', i + 2)
            if (closeAlt > i && closeAlt + 1 < text.length && text[closeAlt + 1] == '(') {
                val closeUrl = text.indexOf(')', closeAlt + 2)
                if (closeUrl > closeAlt) {
                    val alt = text.substring(i + 2, closeAlt).ifBlank { "image" }
                    val url = text.substring(closeAlt + 2, closeUrl)
                    val start = length
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ) { append("🖼 $alt") }
                    addStringAnnotation(TAG_URL, url, start, length)
                    i = closeUrl + 1
                    continue
                }
            }
        }

        // Link [label](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen)
                    val start = length
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    ) { append(label.ifBlank { url }) }
                    addStringAnnotation(TAG_URL, url, start, length)
                    i = closeParen + 1
                    continue
                }
            }
        }

        // *** bold+italic *** or ___
        if (text.startsWith("***", i) || text.startsWith("___", i)) {
            val marker = text.substring(i, i + 3)
            val end = text.indexOf(marker, i + 3)
            if (end > i) {
                withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                ) { append(text.substring(i + 3, end)) }
                i = end + 3
                continue
            }
        }

        // **bold** or __bold__
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    // allow nested italic inside bold
                    appendInline(text.substring(i + 2, end), linkColor, codeBg)
                }
                i = end + 2
                continue
            }
        }

        // ~~strikethrough~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // *italic* or _italic_ (single, not part of **)
        if ((text[i] == '*' || text[i] == '_') &&
            !(text[i] == '*' && text.startsWith("**", i)) &&
            !(text[i] == '_' && text.startsWith("__", i))
        ) {
            val ch = text[i]
            // require closing same char, and not empty
            val end = text.indexOf(ch, i + 1)
            if (end > i + 1) {
                // avoid matching list markers mid-line poorly: require non-space after open
                if (!text[i + 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
        }

        // `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg.copy(alpha = 0.45f),
                        fontSize = 13.sp
                    )
                ) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // bare URL
        if (text.startsWith("https://", i) || text.startsWith("http://", i)) {
            var end = i
            while (end < text.length &&
                !text[end].isWhitespace() &&
                text[end] !in "<>\"'{}"
            ) end++
            while (end > i && text[end - 1] in ".,;:!?)]、。") end--
            val url = text.substring(i, end)
            val start = length
            withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            ) { append(url) }
            addStringAnnotation(TAG_URL, url, start, length)
            i = end
            continue
        }

        append(text[i])
        i++
    }
}
