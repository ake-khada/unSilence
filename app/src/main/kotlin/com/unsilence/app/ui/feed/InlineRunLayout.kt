package com.unsilence.app.ui.feed

import com.unsilence.app.data.model.Segment

/** Render-only blocks from one bounded parser run; never rewrite signed content. */
internal sealed interface InlineRunBlock {
    data class Text(val segments: List<Segment>) : InlineRunBlock
    data class Preview(val url: String) : InlineRunBlock
}

/**
 * A standalone link replaces its own line, not a hole in a larger text node.
 * Sentence links stay clickable in context; their preview follows the run.
 * The caller owns the per-note preview cap and the shared collapsed line budget.
 */
internal fun planInlineRun(segments: List<Segment>, allowPreview: Boolean): List<InlineRunBlock> {
    val run = coalesceAdjacentText(segments)
    val linkIndex = if (allowPreview) run.indexOfFirst { it is Segment.Link } else -1
    val link = run.getOrNull(linkIndex) as? Segment.Link
    return buildList {
        fun text(segments: List<Segment>) {
            val trimmed = trimTextRunEdges(segments)
            if (trimmed.isNotEmpty()) add(InlineRunBlock.Text(trimmed))
        }
        if (link != null && run.isStandaloneLink(linkIndex)) {
            text(run.subList(0, linkIndex))
            add(InlineRunBlock.Preview(link.url))
            text(run.subList(linkIndex + 1, run.size))
        } else {
            text(run)
            if (link != null) add(InlineRunBlock.Preview(link.url))
        }
    }
}

private fun coalesceAdjacentText(segments: List<Segment>): List<Segment> {
    // Parser runs normally alternate text and tokens; avoid copying that path.
    if ((1 until segments.size).none { segments[it - 1] is Segment.Text && segments[it] is Segment.Text }) {
        return segments
    }
    return buildList {
        var index = 0
        while (index < segments.size) {
            val segment = segments[index++]
            if (segment is Segment.Text) {
                add(Segment.Text(buildString {
                    append(segment.text)
                    while (index < segments.size && segments[index] is Segment.Text) {
                        append((segments[index++] as Segment.Text).text)
                    }
                }))
            } else add(segment)
        }
    }
}

private fun List<Segment>.isStandaloneLink(index: Int): Boolean {
    // Keep the existing formatter's fenced-literal context in one text node.
    // Conservatively keep links inline in such runs rather than splitting an
    // open fence and treating the following literal text as note formatting.
    if (any { it is Segment.Text && ("```" in it.text || "~~~" in it.text) }) return false
    val prefix = (getOrNull(index - 1) as? Segment.Text)?.text
        ?.substringAfterLast('\n')?.substringAfterLast('\r').orEmpty()
    if (prefix.startsWith("    ") || prefix.startsWith('\t')) return false
    return blankToLineBoundary(index - 1, -1) && blankToLineBoundary(index + 1, 1)
}

/** Mentions, hashtags and other links count as content, never as separators. */
private fun List<Segment>.blankToLineBoundary(start: Int, step: Int): Boolean {
    var index = start
    while (index in indices) {
        val text = (this[index] as? Segment.Text)?.text ?: return false
        var cursor = if (step < 0) text.lastIndex else 0
        while (cursor in text.indices) {
            val char = text[cursor]
            if (char == '\n' || char == '\r') return true
            if (!char.isWhitespace()) return false
            cursor += step
        }
        index += step
    }
    return true
}

/** Strip only blank boundary lines; retain internal paragraphs and text indentation. */
private fun trimTextRunEdges(segments: List<Segment>): List<Segment> {
    val run = segments.toMutableList()
    while (run.firstOrNull() is Segment.Text) {
        val first = run.first() as Segment.Text
        val trimmed = first.text.trimBlankLines(leading = true)
        if (trimmed.isEmpty()) { run.removeAt(0); continue }
        if (trimmed != first.text) run[0] = Segment.Text(trimmed)
        break
    }
    while (run.lastOrNull() is Segment.Text) {
        val last = run.last() as Segment.Text
        val trimmed = last.text.trimBlankLines(leading = false)
        if (trimmed.isEmpty()) { run.removeAt(run.lastIndex); continue }
        if (trimmed != last.text) run[run.lastIndex] = Segment.Text(trimmed)
        break
    }
    return if (run.all { it is Segment.Text && it.text.isBlank() }) emptyList() else run
}

private fun String.trimBlankLines(leading: Boolean): String {
    val step = if (leading) 1 else -1
    var cursor = if (leading) 0 else lastIndex
    var boundary = if (leading) 0 else length
    while (cursor in indices) {
        val char = this[cursor]
        if (char == '\n' || char == '\r') boundary = if (leading) cursor + 1 else cursor
        else if (!char.isWhitespace()) return if (leading) substring(boundary) else substring(0, boundary)
        cursor += step
    }
    return if (leading) substring(boundary) else substring(0, boundary)
}
