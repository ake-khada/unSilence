package com.unsilence.app.ui.feed

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.model.ParseLimits
import com.unsilence.app.ui.theme.AppType

internal const val MAX_NOTE_FORMAT_MARKS = 150

/** Line-start syntax only: a hashtag, subtraction or decimal is not a heading/list. */
private val NOTE_PREFIX = Regex("""(?m)^ {0,3}(?:(#{1,6})|([-+*])|([0-9]{1,9}[.)]))[ \t]+(?=\S)""")
private val NOTE_BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)

/**
 * Small, render-only kind-1/1111 vocabulary: ATX headings, bullets, numbered
 * items and paired **bold**. Not a Markdown document parser. Original content,
 * media classification and protocol tags never change. Articles/bios stay on
 * their existing paths; the caller supplies the trusted effective kind.
 *
 * Copy annotated slices, not plain strings: links/mentions/emoji retain their
 * targets and callbacks when surrounding markers disappear. Their labels are
 * opaque to syntax detection, as are code literals. No recursive parsing or new
 * composables; bounded input/marks and the caller's normal maxLines still apply.
 */
internal fun formatNoteText(source: AnnotatedString, kind: Int): AnnotatedString {
    if (kind != 1 && kind != 1111) return source
    val text = source.text
    if (text.length > ParseLimits.MAX_NOTE_PARSE_CHARS ||
        ("**" !in text && !NOTE_PREFIX.containsMatchIn(text))) return source

    val literalEnds = IntArray(text.length)
    fun protect(start: Int, end: Int) {
        for (i in start until end) literalEnds[i] = maxOf(literalEnds[i], end)
    }
    source.getLinkAnnotations(0, text.length).forEach { protect(it.start, it.end) }
    source.getStringAnnotations(0, text.length).forEach { protect(it.start, it.end) }

    var remaining = MAX_NOTE_FORMAT_MARKS
    val formatted = buildAnnotatedString {
        // A single forward walk. Unmatched/escaped delimiters stay literal;
        // repeated unmatched markers cannot trigger quadratic closing searches.
        fun appendBody(start: Int, end: Int) {
            var cursor = start
            var opening = -1
            var codeTicks = 0
            var i = start
            while (i < end && remaining > 0) {
                if (literalEnds[i] > i) { i = minOf(literalEnds[i], end); continue }
                if (text[i] == '\\') { i += 2; continue }
                if (text[i] == '`') {
                    var after = i + 1
                    while (after < end && text[after] == '`') after++
                    val width = after - i
                    if (codeTicks == 0) codeTicks = width else if (codeTicks == width) codeTicks = 0
                    i = after
                    continue
                }
                if (codeTicks != 0 || text[i] != '*') { i++; continue }
                var after = i + 1
                while (after < end && text[after] == '*') after++
                if (after - i == 2) {
                    if (opening >= 0 && i > opening + 2 && !text[i - 1].isWhitespace()) {
                        append(source, cursor, opening)
                        val boldStart = length
                        append(source, opening + 2, i)
                        addStyle(NOTE_BOLD_STYLE, boldStart, length)
                        remaining--
                        cursor = after
                        opening = -1
                    } else if (opening < 0 && after < end && !text[after].isWhitespace()) {
                        opening = i
                    }
                }
                i = after
            }
            append(source, cursor, end)
        }

        var lineStart = 0
        var fenceChar = '\u0000'
        var fenceWidth = 0
        while (lineStart < text.length) {
            var newline = text.indexOf('\n', lineStart)
            // A resolved name can contain newlines. Do not split its clickable
            // annotation or mistake those label lines for authored note syntax.
            while (newline >= 0 && literalEnds[newline] > newline) {
                newline = text.indexOf('\n', literalEnds[newline])
            }
            val lineEnd = if (newline < 0) text.length else newline
            val afterLine = if (lineEnd < text.length) lineEnd + 1 else lineEnd
            if (remaining == 0) { append(source, lineStart, text.length); break }
            if (text.startsWith("    ", lineStart) || text[lineStart] == '\t') {
                append(source, lineStart, afterLine)
                lineStart = afterLine
                continue
            }

            // Fenced code is deliberately left literal, including its markers.
            var marker = lineStart
            while (marker < lineEnd && marker - lineStart < 3 && text[marker] == ' ') marker++
            var fenceEnd = marker
            if (marker < lineEnd && text[marker] in "`~") {
                while (fenceEnd < lineEnd && text[fenceEnd] == text[marker]) fenceEnd++
            }
            val isFence = fenceEnd - marker >= 3 && literalEnds[marker] == 0
            if (fenceWidth > 0 || isFence) {
                if (isFence) {
                    if (fenceWidth == 0) {
                        fenceChar = text[marker]
                        fenceWidth = fenceEnd - marker
                    } else if (text[marker] == fenceChar && fenceEnd - marker >= fenceWidth &&
                        text.substring(fenceEnd, lineEnd).isBlank()) {
                        fenceWidth = 0
                    }
                }
                append(source, lineStart, afterLine)
                lineStart = afterLine
                continue
            }

            val prefix = NOTE_PREFIX.matchAt(text, lineStart)?.takeIf { match ->
                match.range.all { literalEnds[it] == 0 }
            }
            val heading = prefix?.groups?.get(1)?.value?.length
            val bullet = prefix?.groups?.get(2)
            val paragraphStart = length
            val bodyStart = prefix?.range?.last?.plus(1) ?: lineStart
            when {
                heading != null -> Unit // omit only the actual heading prefix
                bullet != null -> { append(source, lineStart, bullet.range.first); append("• ") }
                else -> append(source, lineStart, bodyStart) // preserve authored numbers
            }
            if (prefix != null) remaining--
            appendBody(bodyStart, lineEnd)
            append(source, lineEnd, afterLine)
            if (heading != null) {
                val size = when (heading) {
                    1 -> AppType.heading
                    2 -> AppType.subheading
                    else -> AppType.bodyLarge
                }
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size), paragraphStart, length)
                addStyle(ParagraphStyle(lineHeight = (size.value + 6).sp), paragraphStart, length)
            } else if (prefix != null) {
                val indent = if (bullet != null) bullet.range.first - lineStart + 2 else bodyStart - lineStart
                addStyle(ParagraphStyle(textIndent = TextIndent(restLine = (indent * 0.6f).em)), paragraphStart, length)
            }
            lineStart = afterLine
        }
    }
    return if (remaining == MAX_NOTE_FORMAT_MARKS) source else formatted
}
