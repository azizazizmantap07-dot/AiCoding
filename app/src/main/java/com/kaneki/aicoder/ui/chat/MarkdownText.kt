package com.kaneki.aicoder.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaneki.aicoder.ui.diff.SyntaxHighlighter

private const val TAG_URL = "URL"

/**
 * Renderer markdown untuk bubble chat — setara app AI modern (Claude / Grok / ChatGPT).
 *
 * Didukung:
 * - Heading # … ######
 * - **bold** / __bold__ / *italic* / _italic_ / ***bold-italic*** / ~~strike~~
 * - `inline code` dan ``` fenced code blocks ``` (label bahasa + tombol salin)
 * - Link [label](url) + URL mentah http(s)://
 * - Image ![alt](url) → tampil sebagai link 🖼 alt
 * - List - * + dan 1. 2. serta task list - [ ] / - [x] (nested)
 * - Blockquote > (garis aksen kiri)
 * - Horizontal rule --- *** ___
 * - Tabel markdown | a | b | (grid + border + header + scroll horizontal)
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

    // Jangan force fillMaxWidth di root — biar bubble pendek mengikuti teks.
    // Code/tabel tetap fillMaxWidth sendiri agar lega.
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.CodeFence -> {
                    CodeBlock(
                        language = block.language,
                        content = block.content.trimEnd(),
                        codeSurface = codeSurface,
                        style = style
                    )
                }
                is MdBlock.Table -> {
                    MarkdownTable(
                        rows = block.rows,
                        style = style,
                        linkColor = linkColor,
                        codeBg = codeSurface,
                        color = color,
                        onUrl = { runCatching { uriHandler.openUri(it) } }
                    )
                }
                is MdBlock.MathBlock -> {
                    MathBlockView(
                        latex = block.latex,
                        style = style
                    )
                }
                is MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }
                is MdBlock.BlockQuote -> {
                    BlockQuoteView(
                        text = block.text,
                        linkColor = linkColor,
                        codeBg = codeSurface,
                        quoteColor = quoteColor,
                        style = style,
                        color = color,
                        onUrl = { runCatching { uriHandler.openUri(it) } }
                    )
                }
                is MdBlock.Paragraph -> {
                    ClickableMarkdownLine(
                        text = parseInline(
                            text = block.prefix + block.text,
                            linkColor = linkColor,
                            codeBg = codeSurface,
                            baseStyle = when {
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

/** Code fence modern: header bahasa + tombol salin + body monospace scrollable. */
@Composable
private fun CodeBlock(
    language: String,
    content: String,
    codeSurface: Color,
    style: TextStyle
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val langLabel = language.ifBlank { "code" }.lowercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(codeSurface)
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = langLabel,
                style = style.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.3.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(langLabel, content))
                    Toast.makeText(context, "Kode disalin", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Salin kode",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = borderColor)
        val highlighted = remember(content, langLabel) {
            SyntaxHighlighter.highlightByLanguage(content, langLabel)
        }
        Text(
            text = highlighted,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = style.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Blockquote dengan garis aksen kiri (gaya Claude / ChatGPT). */
@Composable
private fun BlockQuoteView(
    text: String,
    linkColor: Color,
    codeBg: Color,
    quoteColor: Color,
    style: TextStyle,
    color: Color,
    onUrl: (String) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        ClickableMarkdownLine(
            text = parseInline(
                text = text,
                linkColor = linkColor,
                codeBg = codeBg,
                baseStyle = SpanStyle(
                    color = quoteColor,
                    fontStyle = FontStyle.Italic
                )
            ),
            style = style,
            color = color,
            onUrl = onUrl
        )
    }
}

/**
 * Tabel markdown yang rapi — mirip Claude / Grok / ChatGPT.
 * - Kolom sejajar (lebar tetap per kolom berdasarkan konten terpanjang)
 * - Header tebal + background berbeda
 * - Border & divider antar sel
 * - Horizontal scroll untuk tabel lebar
 * - Alternating row color agar mudah dibaca
 */
@Composable
private fun MarkdownTable(
    rows: List<List<String>>,
    style: TextStyle,
    linkColor: Color,
    codeBg: Color,
    color: Color,
    onUrl: (String) -> Unit
) {
    if (rows.isEmpty()) return

    val colCount = rows.maxOf { it.size }
    val normalized = remember(rows) {
        rows.map { row ->
            if (row.size >= colCount) row
            else row + List(colCount - row.size) { "" }
        }
    }

    // Lebar kolom dari konten terpanjang (abaikan markup ** ` agar tidak terlalu lebar)
    val colWidthsDp = remember(normalized) {
        (0 until colCount).map { col ->
            val maxChars = normalized.maxOf { row ->
                val plain = row[col]
                    .replace(Regex("""[*_`~\[\]]"""), "")
                    .replace(Regex("""\([^)]*\)"""), "")
                plain.sumOf { ch ->
                    when {
                        ch.code > 0x1F000 -> 2
                        else -> 1
                    }.toInt()
                }
            }
            (maxChars * 7.5f + 20f).toInt().coerceIn(64, 200).dp
        }
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val evenRowBg = Color.Transparent
    val oddRowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    val shape = RoundedCornerShape(8.dp)

    val totalWidth = colWidthsDp.fold(0.dp) { acc, w -> acc + w } +
        (1.dp * (colCount - 1).coerceAtLeast(0))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.width(totalWidth)) {
            normalized.forEachIndexed { rowIndex, row ->
                val isHeader = rowIndex == 0
                val rowBg = when {
                    isHeader -> headerBg
                    rowIndex % 2 == 0 -> evenRowBg
                    else -> oddRowBg
                }

                Row(
                    modifier = Modifier
                        .width(totalWidth)
                        .background(rowBg)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEachIndexed { colIndex, cell ->
                        if (colIndex > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor)
                            )
                        }
                        val cellAnnotated = remember(cell, isHeader, linkColor, codeBg) {
                            parseInline(
                                text = cell,
                                linkColor = linkColor,
                                codeBg = codeBg,
                                baseStyle = SpanStyle(
                                    fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 12.5.sp
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(colWidthsDp[colIndex])
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                            Text(
                                text = cellAnnotated,
                                modifier = Modifier.pointerInput(cellAnnotated) {
                                    detectTapGestures { pos ->
                                        val layout = layoutResult ?: return@detectTapGestures
                                        val offset = layout.getOffsetForPosition(pos)
                                        cellAnnotated.getStringAnnotations(TAG_URL, offset, offset)
                                            .firstOrNull()
                                            ?.let { onUrl(it.item) }
                                    }
                                },
                                style = style.copy(
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp
                                ),
                                color = if (isHeader) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    color.takeUnless { it == Color.Unspecified }
                                        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                                },
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { layoutResult = it }
                            )
                        }
                    }
                }

                if (rowIndex < normalized.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.width(totalWidth),
                        thickness = 1.dp,
                        color = borderColor
                    )
                }
            }
        }
    }
}

/** Block math $$...$$ ditampilkan sebagai kartu terpusat. */
@Composable
private fun MathBlockView(
    latex: String,
    style: TextStyle
) {
    val rendered = remember(latex) { latexToUnicode(latex.trim()) }
    val shape = RoundedCornerShape(8.dp)
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rendered,
            style = style.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private sealed interface MdBlock {
    data class Paragraph(
        val text: String,
        val headingLevel: Int? = null,
        val prefix: String = ""
    ) : MdBlock

    data class CodeFence(val language: String, val content: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class BlockQuote(val text: String) : MdBlock
    data class MathBlock(val latex: String) : MdBlock
    data object HorizontalRule : MdBlock
}

private fun splitBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Block math $$ ... $$
        if (trimmed.startsWith("$$")) {
            val sameLine = trimmed.removePrefix("$$")
            if (sameLine.contains("$$")) {
                val latex = sameLine.substringBefore("$$").trim()
                out.add(MdBlock.MathBlock(latex))
                i++
                continue
            }
            val body = StringBuilder()
            if (sameLine.isNotBlank()) body.append(sameLine)
            i++
            while (i < lines.size && !lines[i].contains("$$")) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) {
                val endPart = lines[i].substringBefore("$$")
                if (endPart.isNotBlank()) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(endPart)
                }
                i++
            }
            out.add(MdBlock.MathBlock(body.toString().trim()))
            continue
        }

        // Fenced code (``` atau ~~~)
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = if (trimmed.startsWith("```")) "```" else "~~~"
            val lang = trimmed.removePrefix(fence).trim()
            // ```math atau ```latex → math block
            if (lang.equals("math", ignoreCase = true) ||
                lang.equals("latex", ignoreCase = true) ||
                lang.equals("tex", ignoreCase = true)
            ) {
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out.add(MdBlock.MathBlock(body.toString().trim()))
                i++
                continue
            }
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            out.add(MdBlock.CodeFence(lang, body.toString()))
            i++ // skip closing fence
            continue
        }

        // Table: header + separator |---|
        val looksLikeTableHeader = (trimmed.startsWith("|") || trimmed.count { it == '|' } >= 2) &&
            i + 1 < lines.size &&
            lines[i + 1].trim().matches(
                Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
            )
        if (looksLikeTableHeader) {
            val rows = mutableListOf<List<String>>()
            fun parseRow(raw: String): List<String> =
                raw.trim().trim('|').split('|').map { it.trim() }
            rows.add(parseRow(line))
            i += 2 // skip header + separator
            while (i < lines.size) {
                val nextTrim = lines[i].trimStart()
                if (nextTrim.startsWith("|") ||
                    (nextTrim.isNotEmpty() && nextTrim.count { it == '|' } >= 1)
                ) {
                    if (nextTrim.startsWith("#") ||
                        nextTrim.startsWith("```") ||
                        nextTrim.startsWith("~~~") ||
                        nextTrim.startsWith(">") ||
                        nextTrim.matches(Regex("""^([-*+]|\d+\.)\s+.*"""))
                    ) break
                    rows.add(parseRow(lines[i]))
                    i++
                } else {
                    break
                }
            }
            if (rows.isNotEmpty()) {
                out.add(MdBlock.Table(rows))
                continue
            }
        }

        // Blank line
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Horizontal rule
        if (trimmed.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$"""))) {
            out.add(MdBlock.HorizontalRule)
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

        // Blockquote (multi-line consecutive)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(
                    lines[i].trimStart().removePrefix(">").removePrefix(" ").trimEnd()
                )
                i++
            }
            out.add(MdBlock.BlockQuote(text = quoteLines.joinToString("\n")))
            continue
        }

        // List item (bullet, numbered, task) — termasuk nested indent
        val listMatch = Regex(
            """^(\s*)([-*+]|\d+\.)\s+(\[[ xX]\]\s+)?(.*)$"""
        ).find(line)
        if (listMatch != null) {
            val indent = listMatch.groupValues[1].length
            val marker = listMatch.groupValues[2]
            val task = listMatch.groupValues[3]
            val content = listMatch.groupValues[4]
            // Indent visual: 2 spasi per level, max 6 level
            val level = (indent / 2).coerceAtMost(6)
            val pad = "  ".repeat(level)
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
            if (nt.startsWith("```") || nt.startsWith("~~~") ||
                nt.startsWith("#") || nt.startsWith(">") ||
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
            val end = text.indexOf(ch, i + 1)
            if (end > i + 1) {
                if (!text[i + 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
        }

        // `code` (juga support `` nested backticks sederhana)
        if (text[i] == '`') {
            // hitung jumlah backtick pembuka
            var ticks = 0
            while (i + ticks < text.length && text[i + ticks] == '`') ticks++
            val close = text.indexOf("`".repeat(ticks), i + ticks)
            if (close > i) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg.copy(alpha = 0.45f),
                        fontSize = 13.sp
                    )
                ) { append(text.substring(i + ticks, close)) }
                i = close + ticks
                continue
            }
        }

        // Inline math $...$ (bukan $$)
        if (text[i] == '$' && !(i + 1 < text.length && text[i + 1] == '$')) {
            val end = text.indexOf('$', i + 1)
            if (end > i + 1) {
                val latex = text.substring(i + 1, end)
                withStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp
                    )
                ) { append(latexToUnicode(latex)) }
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

/**
 * Konversi LaTeX sederhana → Unicode agar terbaca tanpa engine MathJax.
 * Mendukung: Greek, operator, \frac, superscript/subscript umum, dll.
 */
private fun latexToUnicode(latex: String): String {
    var s = latex.trim()

    // \frac{a}{b} → (a)/(b) atau a⁄b untuk yang pendek
    val fracRegex = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
    s = fracRegex.replace(s) { m ->
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (a.length <= 3 && b.length <= 3) "$a⁄$b" else "($a)/($b)"
    }

    // \sqrt{x} → √x
    s = Regex("""\\sqrt\{([^{}]+)\}""").replace(s) { "√${it.groupValues[1]}" }
    s = s.replace("\\sqrt", "√")

    // \text{...} / \mathrm{...} → isi saja
    s = Regex("""\\(?:text|mathrm|mathbf|mathit)\{([^{}]+)\}""").replace(s) { it.groupValues[1] }

    // Superscript ^{...} atau ^n
    s = Regex("""\^\{([^{}]+)\}""").replace(s) { toSuperscript(it.groupValues[1]) }
    s = Regex("""\^([0-9a-zA-Z+\-()]+)""").replace(s) { toSuperscript(it.groupValues[1]) }

    // Subscript _{...} atau _n
    s = Regex("""_\{([^{}]+)\}""").replace(s) { toSubscript(it.groupValues[1]) }
    s = Regex("""_([0-9a-zA-Z+\-()]+)""").replace(s) { toSubscript(it.groupValues[1]) }

    // Greek + symbols (urutan panjang dulu)
    val replacements = listOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ",
        "\\upsilon" to "υ", "\\phi" to "φ", "\\varphi" to "ϕ", "\\chi" to "χ",
        "\\psi" to "ψ", "\\omega" to "ω",
        "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
        "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\oint" to "∮",
        "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\div" to "÷",
        "\\cdot" to "·", "\\ast" to "∗",
        "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\approx" to "≈",
        "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝",
        "\\ll" to "≪", "\\gg" to "≫",
        "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\subseteq" to "⊆",
        "\\supset" to "⊃", "\\supseteq" to "⊇", "\\cup" to "∪", "\\cap" to "∩",
        "\\emptyset" to "∅", "\\forall" to "∀", "\\exists" to "∃", "\\neg" to "¬",
        "\\land" to "∧", "\\lor" to "∨", "\\Rightarrow" to "⇒", "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\rightarrow" to "→", "\\leftarrow" to "←", "\\leftrightarrow" to "↔",
        "\\to" to "→", "\\gets" to "←",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…",
        "\\degree" to "°", "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥",
        "\\hbar" to "ℏ", "\\ell" to "ℓ",
        "\\left(" to "(", "\\right)" to ")",
        "\\left[" to "[", "\\right]" to "]",
        "\\left\\{" to "{", "\\right\\}" to "}",
        "\\{" to "{", "\\}" to "}",
        "\\," to " ", "\\;" to " ", "\\!" to "", "\\quad" to "  ", "\\qquad" to "    ",
        "\\ " to " "
    )
    for ((from, to) in replacements) {
        s = s.replace(from, to)
    }

    // Bersihkan sisa backslash perintah sederhana \cmd → cmd
    s = Regex("""\\([a-zA-Z]+)""").replace(s) { it.groupValues[1] }

    return s.trim()
}

private fun toSuperscript(src: String): String {
    val map = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ'
    )
    return src.map { map[it] ?: it }.joinToString("")
}

private fun toSubscript(src: String): String {
    val map = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'n' to 'ₙ',
        'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'm' to 'ₘ', 'p' to 'ₚ',
        'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ'
    )
    return src.map { map[it] ?: it }.joinToString("")
}
