package com.kaneki.aicoder.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaneki.aicoder.domain.model.DiffLine
import com.kaneki.aicoder.domain.model.DiffLineType

@Composable
fun DiffLineView(
    line: DiffLine,
    filePath: String = ""
) {
    val bg = when (line.type) {
        DiffLineType.INSERT -> Color(0xFF1B5E20).copy(alpha = 0.35f)
        DiffLineType.DELETE -> Color(0xFFB71C1C).copy(alpha = 0.35f)
        DiffLineType.EQUAL -> Color.Transparent
    }
    val prefix = when (line.type) {
        DiffLineType.INSERT -> "+"
        DiffLineType.DELETE -> "-"
        DiffLineType.EQUAL -> " "
    }
    val baseColor = when (line.type) {
        DiffLineType.INSERT -> Color(0xFFA5D6A7)
        DiffLineType.DELETE -> Color(0xFFEF9A9A)
        DiffLineType.EQUAL -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    }

    val highlighted = if (filePath.isNotBlank() && line.type != DiffLineType.DELETE) {
        SyntaxHighlighter.highlight(line.text, filePath)
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 1.dp)
    ) {
        Text(
            text = formatLineNo(line.oldLineNumber),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text = formatLineNo(line.newLineNumber),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = baseColor,
            modifier = Modifier.padding(end = 4.dp)
        )
        if (highlighted != null) {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            )
        } else {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = baseColor
            )
        }
    }
}

private fun formatLineNo(n: Int?): String =
    if (n == null) "    " else n.toString().padStart(4, ' ')
