package com.unsilence.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.model.markdown.MdAlign
import com.unsilence.app.data.model.markdown.MdInline
import com.unsilence.app.data.model.markdown.MdTable
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Surface2

/**
 * Renders a parsed [MdTable] as a native grid that fits the available width — no
 * horizontal scroll. Every column gets equal weight so the whole table fits the
 * row regardless of cell text length (uniform columns, not content-sized), and
 * each row is laid out at [IntrinsicSize.Min] so all of a row's cells share one
 * height — one uniform box per row, cells just wrap their text taller as needed.
 */
@Composable
internal fun MarkdownTable(
    table: MdTable,
    textColor: Color,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colCount = table.columns.size
    if (colCount == 0) return

    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(Surface2),
        ) {
            table.columns.forEach { column ->
                TableCell(
                    inlines        = column.header,
                    align          = column.align,
                    textColor      = textColor,
                    fontWeight     = FontWeight.SemiBold,
                    onHashtagClick = onHashtagClick,
                    modifier       = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        // Body rows
        table.rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                table.columns.forEachIndexed { col, column ->
                    TableCell(
                        inlines        = row.cells.getOrNull(col) ?: emptyList(),
                        align          = column.align,
                        textColor      = textColor,
                        fontWeight     = null,
                        onHashtagClick = onHashtagClick,
                        modifier       = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

private val GridColor = Color(0xFF2A2A2A)

@Composable
private fun TableCell(
    inlines: List<MdInline>,
    align: MdAlign,
    textColor: Color,
    fontWeight: FontWeight?,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Vertically centered (so a short cell sits level with the tall wrapped cell
    // beside it); horizontally it honors the markdown column alignment (left by
    // default). The Text fills the cell width so TextAlign controls horizontal
    // placement and the Box only needs to center vertically.
    val textAlign = when (align) {
        MdAlign.Left   -> TextAlign.Start
        MdAlign.Center -> TextAlign.Center
        MdAlign.Right  -> TextAlign.End
    }
    Box(
        modifier = modifier
            .border(1.dp, GridColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val text = remember(inlines, onHashtagClick) {
            buildAnnotatedString { appendMdInlines(inlines, onHashtagClick) }
        }
        androidx.compose.material3.Text(
            text       = text,
            color      = textColor,
            fontSize   = AppType.bodySmall,
            fontWeight = fontWeight,
            textAlign  = textAlign,
            modifier   = Modifier.fillMaxWidth(),
        )
    }
}
