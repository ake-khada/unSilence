package com.unsilence.app.data.model.markdown

import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.ParseLimits
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Parses GFM article markdown (kind-30023 body) into the native [MarkdownDocument]
 * model. Pure Kotlin / Android-free — no Log here so phase-1 tests run on plain JVM;
 * the PARSE-HEAVY probe fires at the phase-2 Compose integration boundary using the
 * returned [MarkdownDocument.truncated] flag.
 *
 * Caps (H-spam draw-bound discipline):
 *  - input chars: [ParseLimits.MAX_ARTICLE_PARSE_CHARS] (shared with the note path).
 *  - per-block inlines: [MAX_INLINES_PER_BLOCK] — the load-bearing bound (one block's
 *    draw ≈ one note's: a mega-paragraph is the rotating-npub-spam stall).
 *  - total blocks: [MAX_BLOCKS] — generous backstop (real longform has hundreds of
 *    paragraphs; this only stops pathological input).
 *  - tables: [MAX_TABLE_COLS] / [MAX_TABLE_ROWS] / [MAX_CELL_CHARS]; cells also count
 *    toward the per-block inline bound when rendered.
 */
object NativeMarkdownParser {

    private const val MAX_INLINES_PER_BLOCK = 150
    private const val MAX_BLOCKS = 2_000
    private const val MAX_TABLE_COLS = 12
    private const val MAX_TABLE_ROWS = 200
    private const val MAX_CELL_CHARS = 4_000
    /** Document-wide cell budget across ALL tables — bounds the ~50k-cell case the
     *  200k input cap otherwise allows; generous for legit multi-table articles. */
    private const val MAX_TABLE_CELLS_TOTAL = 5_000

    /** Blockquote nesting depth cap. The 200k input cap does NOT bound depth: a ~20KB
     *  wall of nested `>` lines recurses the GFM parser ~5–10k deep → OOM/SOE. */
    private const val MAX_BLOCKQUOTE_DEPTH = 32

    /** Independent guard for recursion in our AST walkers. The lexical blockquote
     *  pre-pass protects the third-party parser; this protects our own traversal if
     *  another markdown construct produces an unexpectedly deep tree. */
    private const val MAX_AST_DEPTH = 64

    /** Inline metacharacters; absence lets a table cell skip the sub-parse (fast path). */
    private val INLINE_MARKUP = charArrayOf('*', '_', '~', '\\', '[', ']', '(', ')', '<', '>', '!', '`')

    /** Inline delimiter tokens that must not leak into rendered text. */
    private val DELIMITERS = setOf(
        MarkdownTokenTypes.EMPH,
        GFMTokenTypes.TILDE,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.LT,
        MarkdownTokenTypes.GT,
        MarkdownTokenTypes.EXCLAMATION_MARK,
    )

    /** Document-wide budget: total blocks + total table cells. */
    private class Budget {
        var truncated = false
        var blocksRemaining = MAX_BLOCKS
        var cellsRemaining = MAX_TABLE_CELLS_TOTAL
    }

    /** Per-block inline budget — fresh for each block/heading/cell, shared across that
     *  block's nested spans so the TOTAL flattened inline count is bounded (the bug a
     *  per-list cap missed; mirrors the note path's capSegmentsFlat). */
    private class InlineBudget(val doc: Budget) {
        var remaining = MAX_INLINES_PER_BLOCK
    }

    fun parse(markdown: String): MarkdownDocument {
        val inputCapped = markdown.length > ParseLimits.MAX_ARTICLE_PARSE_CHARS
        val lengthCapped = if (inputCapped) markdown.take(ParseLimits.MAX_ARTICLE_PARSE_CHARS) else markdown
        // Cheap O(n) depth pre-pass BEFORE any expensive parse: cut the input at the
        // first line whose blockquote nesting exceeds the cap (see MAX_BLOCKQUOTE_DEPTH).
        val depthCut = firstLineBeyondBlockquoteDepth(lengthCapped)
        val depthCapped = depthCut >= 0
        val src = if (depthCapped) lengthCapped.substring(0, depthCut) else lengthCapped
        val budget = Budget()
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(src)
        val blocks = parseBlocks(tree.children, src, budget).toMutableList()
        val truncated = inputCapped || depthCapped || budget.truncated
        if (truncated) blocks.add(MdBlock.Paragraph(listOf(MdInline.Text(ParseLimits.TRUNCATION_MARKER))))
        return MarkdownDocument(blocks, truncated)
    }

    /**
     * Returns the START offset of the first line whose leading blockquote-marker
     * depth exceeds [MAX_BLOCKQUOTE_DEPTH], or -1 when the input is within bounds.
     * Single O(n) index walk, no regex per line. Marker form per CommonMark: up to
     * three leading spaces, then `>` markers separated by indentation. We count
     * spaces and tabs conservatively between markers; over-counting pathological
     * prefixes is safer than letting parser recursion through.
     */
    private fun firstLineBeyondBlockquoteDepth(src: String): Int {
        var lineStart = 0
        while (lineStart < src.length) {
            var i = lineStart
            var spaces = 0
            while (i < src.length && src[i] == ' ' && spaces < 3) { i++; spaces++ }
            // Four-space indentation is a code block, not a block quote.
            if (i < src.length && src[i] == ' ') {
                val newline = src.indexOf('\n', i)
                if (newline == -1) return -1
                lineStart = newline + 1
                continue
            }
            var depth = 0
            while (i < src.length && src[i] != '\n' && src[i] != '\r') {
                when (src[i]) {
                    '>' -> { depth++; i++ }
                    ' ', '\t' -> if (depth > 0) i++ else break
                    else -> break
                }
            }
            if (depth > MAX_BLOCKQUOTE_DEPTH) return lineStart
            val newline = src.indexOf('\n', i)
            if (newline == -1) return -1
            lineStart = newline + 1
        }
        return -1
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    private fun parseBlocks(
        nodes: List<ASTNode>,
        src: String,
        budget: Budget,
        depth: Int = 0,
    ): List<MdBlock> {
        if (depth >= MAX_AST_DEPTH) {
            budget.truncated = true
            return flattenDeepBlocks(nodes, src)
        }
        val out = mutableListOf<MdBlock>()
        for (node in nodes) {
            if (budget.blocksRemaining <= 0) { budget.truncated = true; break }
            val block = blockFor(node, src, budget, depth) ?: continue
            budget.blocksRemaining--
            out.add(block)
        }
        return out
    }

    private fun flattenDeepBlocks(nodes: List<ASTNode>, src: String): List<MdBlock> {
        val text = nodes.joinToString(separator = "") { it.text(src) }.trim()
        return if (text.isEmpty()) emptyList()
        else listOf(MdBlock.Paragraph(listOf(MdInline.Text(text))))
    }

    private fun blockFor(node: ASTNode, src: String, budget: Budget, depth: Int): MdBlock? = when (node.type) {
        MarkdownElementTypes.ATX_1 -> MdBlock.Heading(1, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_2 -> MdBlock.Heading(2, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_3 -> MdBlock.Heading(3, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_4 -> MdBlock.Heading(4, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_5 -> MdBlock.Heading(5, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_6 -> MdBlock.Heading(6, headingInlines(node, src, budget))
        MarkdownElementTypes.SETEXT_1 -> MdBlock.Heading(1, headingInlines(node, src, budget))
        MarkdownElementTypes.SETEXT_2 -> MdBlock.Heading(2, headingInlines(node, src, budget))

        MarkdownElementTypes.PARAGRAPH -> paragraphOrImage(node, src, budget)
        MarkdownElementTypes.BLOCK_QUOTE ->
            MdBlock.BlockQuote(parseBlocks(node.children, src, budget, depth + 1))
        MarkdownElementTypes.UNORDERED_LIST -> listBlock(node, src, budget, ordered = false, depth = depth)
        MarkdownElementTypes.ORDERED_LIST -> listBlock(node, src, budget, ordered = true, depth = depth)
        MarkdownElementTypes.CODE_FENCE -> codeFence(node, src)
        MarkdownElementTypes.CODE_BLOCK -> MdBlock.CodeBlock(null, indentedCode(node, src))
        MarkdownElementTypes.HTML_BLOCK -> MdBlock.Paragraph(listOf(MdInline.Text(node.text(src).trim())))
        GFMElementTypes.TABLE -> parseTable(node, src, budget)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MdBlock.HorizontalRule
        // EOL/whitespace between blocks, link definitions, etc. — skip.
        else -> null
    }

    /** A paragraph that's just a single image becomes a block Image; else a Paragraph. */
    private fun paragraphOrImage(node: ASTNode, src: String, budget: Budget): MdBlock {
        val meaningful = node.children.filter {
            it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownTokenTypes.EOL
        }
        val single = meaningful.singleOrNull()
        if (single != null && single.type == MarkdownElementTypes.IMAGE) {
            return imageBlock(single, src)
        }
        return MdBlock.Paragraph(parseInlines(node, src, budget))
    }

    private fun listBlock(
        node: ASTNode,
        src: String,
        budget: Budget,
        ordered: Boolean,
        depth: Int,
    ): MdBlock {
        val items = node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .map { parseBlocks(it.children, src, budget, depth + 1) }
        return MdBlock.ListBlock(ordered, items)
    }

    private fun codeFence(node: ASTNode, src: String): MdBlock {
        val lang = node.children
            .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
            ?.text(src)?.trim()?.takeIf { it.isNotEmpty() }
        val code = node.children
            .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
            .joinToString("") { it.text(src) }
            .trim('\n')
        return MdBlock.CodeBlock(lang, code)
    }

    private fun indentedCode(node: ASTNode, src: String): String =
        node.children
            .filter { it.type == MarkdownTokenTypes.CODE_LINE || it.type == MarkdownTokenTypes.EOL }
            .joinToString("") { it.text(src) }
            .trim('\n')

    private fun imageBlock(node: ASTNode, src: String): MdBlock.Image {
        val url = node.findDescendant(MarkdownElementTypes.LINK_DESTINATION)?.text(src)?.trimAngle() ?: ""
        val alt = node.findDescendant(MarkdownElementTypes.LINK_TEXT)?.text(src)
            ?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
        return MdBlock.Image(url, alt)
    }

    // ── Inlines ──────────────────────────────────────────────────────────────

    private fun headingInlines(node: ASTNode, src: String, budget: Budget): List<MdInline> {
        // ATX/SETEXT content is a single token (the `#`/`=`/`-` markers are siblings) —
        // parse its text as inline markdown so the marker never leaks and bold/links/
        // hashtags inside the heading still resolve.
        val content = node.findChild(MarkdownTokenTypes.ATX_CONTENT)?.text(src)
            ?: node.findChild(MarkdownTokenTypes.SETEXT_CONTENT)?.text(src)
        return if (content != null) parseInlineMarkdown(content, budget)
        else parseInlines(node, src, budget)
    }

    /** Walk a block node's children into inline spans, bounded by a fresh per-block budget. */
    private fun parseInlines(node: ASTNode, src: String, doc: Budget): List<MdInline> {
        val out = mutableListOf<MdInline>()
        appendInlines(node.children, src, out, InlineBudget(doc))
        return out
    }

    /** Adds one inline, decrementing the SHARED per-block budget; no-op when exhausted. */
    private fun emit(out: MutableList<MdInline>, inline: MdInline, ib: InlineBudget) {
        if (ib.remaining <= 0) { ib.doc.truncated = true; return }
        out.add(inline)
        ib.remaining--
    }

    private fun appendInlines(
        nodes: List<ASTNode>,
        src: String,
        out: MutableList<MdInline>,
        ib: InlineBudget,
        depth: Int = 0,
    ) {
        if (depth >= MAX_AST_DEPTH) {
            ib.doc.truncated = true
            val text = nodes.joinToString(separator = "") { it.text(src) }
            if (text.isNotEmpty()) emit(out, MdInline.Text(text), ib)
            return
        }
        for (node in nodes) {
            if (ib.remaining <= 0) { ib.doc.truncated = true; return }
            when (node.type) {
                MarkdownTokenTypes.TEXT, MarkdownTokenTypes.WHITE_SPACE ->
                    appendTextWithHashtags(node.text(src), out, ib)
                MarkdownTokenTypes.EOL -> emit(out, MdInline.Text(" "), ib)
                MarkdownTokenTypes.HARD_LINE_BREAK -> emit(out, MdInline.Text("\n"), ib)

                // Children parsed first (sharing ib), then the wrapper emitted.
                MarkdownElementTypes.EMPH ->
                    emit(out, MdInline.Emphasis(childInlines(node, src, ib, depth + 1)), ib)
                MarkdownElementTypes.STRONG ->
                    emit(out, MdInline.Strong(childInlines(node, src, ib, depth + 1)), ib)
                GFMElementTypes.STRIKETHROUGH ->
                    emit(out, MdInline.Strikethrough(childInlines(node, src, ib, depth + 1)), ib)
                MarkdownElementTypes.CODE_SPAN ->
                    emit(out, MdInline.Code(node.text(src).trim('`').trim()), ib)

                MarkdownElementTypes.INLINE_LINK,
                MarkdownElementTypes.FULL_REFERENCE_LINK,
                MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                    emit(out, linkInline(node, src, ib, depth + 1), ib)

                MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.AUTOLINK,
                MarkdownTokenTypes.EMAIL_AUTOLINK -> {
                    val url = node.text(src).trimAngle()
                    emit(out, MdInline.Link(url, listOf(MdInline.Text(url))), ib)
                }

                // Inline image (not a standalone block) → flatten to a link (no media).
                MarkdownElementTypes.IMAGE -> {
                    val url = node.findDescendant(MarkdownElementTypes.LINK_DESTINATION)?.text(src)?.trimAngle() ?: ""
                    val alt = node.findDescendant(MarkdownElementTypes.LINK_TEXT)?.text(src)
                        ?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.let(::decodeHtmlEntities) ?: url
                    if (url.isNotEmpty()) emit(out, MdInline.Link(url, listOf(MdInline.Text(alt))), ib)
                }

                in DELIMITERS -> { /* skip delimiters */ }

                else ->
                    // Unknown element → recurse; unknown leaf token → keep its text (don't drop content).
                    if (node.children.isEmpty()) appendTextWithHashtags(node.text(src), out, ib)
                    else appendInlines(node.children, src, out, ib, depth + 1)
            }
        }
    }

    /** Inlines of a wrapper node (emph/strong/strikethrough/link-text), SHARING the budget. */
    private fun childInlines(
        node: ASTNode,
        src: String,
        ib: InlineBudget,
        depth: Int,
    ): List<MdInline> {
        val out = mutableListOf<MdInline>()
        appendInlines(node.children, src, out, ib, depth)
        return out
    }

    private fun linkInline(node: ASTNode, src: String, ib: InlineBudget, depth: Int): MdInline {
        val dest = node.findChild(MarkdownElementTypes.LINK_DESTINATION)?.text(src)?.trimAngle()
        val textNode = node.findChild(MarkdownElementTypes.LINK_TEXT)
        val children = if (textNode != null) childInlines(textNode, src, ib, depth)
            else listOf(MdInline.Text(node.text(src)))
        // FULL/SHORT_REFERENCE_LINK carry no LINK_DESTINATION → url "" (definitions
        // aren't resolved in v1); phase 2 renders empty-url Links as plain styled text.
        return MdInline.Link(dest ?: "", children.ifEmpty { listOf(MdInline.Text(dest ?: "")) })
    }

    /** Split a raw text run into Text + Hashtag inlines, SHARING the per-block budget.
     *  HTML entities are decoded here (the WebView did this for free) so `&#x20;`,
     *  `&amp;`, `&mdash;` etc. render as their characters, not literal source. */
    private fun appendTextWithHashtags(rawText: String, out: MutableList<MdInline>, ib: InlineBudget) {
        val text = decodeHtmlEntities(rawText)
        if (text.isEmpty()) return
        val tags = ContentParser.findHashtags(text)
        if (tags.isEmpty()) { emit(out, MdInline.Text(text), ib); return }
        var cursor = 0
        for ((start, end, tag) in tags) {
            if (ib.remaining <= 0) { ib.doc.truncated = true; return }
            if (start > cursor) emit(out, MdInline.Text(text.substring(cursor, start)), ib)
            emit(out, MdInline.Hashtag(tag), ib)
            cursor = end
        }
        if (cursor < text.length) emit(out, MdInline.Text(text.substring(cursor)), ib)
    }

    // ── Tables ───────────────────────────────────────────────────────────────

    /**
     * GFM table. 0.7.3 exposes no alignment metadata, so structure + alignment come
     * from the raw source lines (split on UNescaped `|`); cell inline formatting is
     * parsed per-cell. Malformed (no separator row) → CodeBlock of the raw source so
     * the author's column intent survives in monospace, never a broken grid.
     */
    private fun parseTable(node: ASTNode, src: String, budget: Budget): MdBlock {
        val raw = node.text(src).trim()
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || !isSeparatorRow(lines[1])) {
            return MdBlock.CodeBlock(null, raw) // malformed → preserve as monospace
        }
        val headerCells = splitRow(lines[0]).let { capCells(it, budget) }
        val aligns = splitRow(lines[1]).map(::alignOf)
        val columns = headerCells.mapIndexed { i, cell ->
            budget.cellsRemaining--
            MdTableColumn(
                header = parseInlineMarkdown(cell, budget),
                align = aligns.getOrElse(i) { MdAlign.Left },
            )
        }
        val colCount = columns.size
        val bodyLines = lines.drop(2)
        if (bodyLines.size > MAX_TABLE_ROWS) budget.truncated = true
        val rows = mutableListOf<MdTableRow>()
        for (line in bodyLines.take(MAX_TABLE_ROWS)) {
            // Global cell budget: stop emitting rows once the document-wide cap is hit.
            if (budget.cellsRemaining <= 0) { budget.truncated = true; break }
            val cells = capCells(splitRow(line), budget)
            // Normalize to header column count: pad missing, truncate extras.
            val normalized = (0 until colCount).map { idx ->
                budget.cellsRemaining--
                parseInlineMarkdown(cells.getOrElse(idx) { "" }, budget)
            }
            rows.add(MdTableRow(normalized))
        }
        return MdBlock.Table(MdTable(columns, rows))
    }

    private fun capCells(cells: List<String>, budget: Budget): List<String> {
        if (cells.size > MAX_TABLE_COLS) budget.truncated = true
        return cells.take(MAX_TABLE_COLS).map {
            if (it.length > MAX_CELL_CHARS) { budget.truncated = true; it.take(MAX_CELL_CHARS) } else it
        }
    }

    /** Parse a fragment's text as inline markdown (table cells + headings). Fresh
     *  per-fragment inline budget; plain fragments skip the sub-parse (fast path). */
    private fun parseInlineMarkdown(text: String, doc: Budget): List<MdInline> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val ib = InlineBudget(doc)
        val out = mutableListOf<MdInline>()
        // Fast path: no inline markup → skip buildMarkdownTreeFromString, but still
        // hashtag-split and respect the per-fragment budget (collapses the per-cell
        // sub-parse cost on the common all-plain table).
        if (t.none { it in INLINE_MARKUP }) {
            appendTextWithHashtags(t, out, ib)
            return out.ifEmpty { listOf(MdInline.Text(t)) }
        }
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(t)
        fun walk(n: ASTNode, depth: Int) {
            if (depth >= MAX_AST_DEPTH) {
                doc.truncated = true
                emit(out, MdInline.Text(n.text(t)), ib)
            } else if (n.type == MarkdownElementTypes.PARAGRAPH) {
                appendInlines(n.children, t, out, ib, depth + 1)
            } else {
                n.children.forEach { walk(it, depth + 1) }
            }
        }
        walk(tree, 0)
        return out.ifEmpty { listOf(MdInline.Text(t)) }
    }

    private fun isSeparatorRow(line: String): Boolean {
        val cells = splitRow(line)
        return cells.isNotEmpty() && cells.all { c ->
            val s = c.trim()
            s.isNotEmpty() && s.all { it == '-' || it == ':' } && s.contains('-')
        }
    }

    private fun alignOf(sep: String): MdAlign {
        val s = sep.trim()
        val left = s.startsWith(":")
        val right = s.endsWith(":")
        return when {
            left && right -> MdAlign.Center
            right -> MdAlign.Right
            else -> MdAlign.Left
        }
    }

    /** Split a table row on UNescaped pipes, dropping leading/trailing empties, unescaping `\|`. */
    private fun splitRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|') {
                sb.append('|'); i += 2; continue
            }
            if (c == '|') { cells.add(sb.toString().trim()); sb.clear(); i++; continue }
            sb.append(c); i++
        }
        cells.add(sb.toString().trim())
        return cells
    }

    // ── ASTNode helpers ──────────────────────────────────────────────────────

    private fun ASTNode.text(src: String): String = src.substring(startOffset, endOffset)

    private fun ASTNode.findChild(type: org.intellij.markdown.IElementType): ASTNode? =
        children.firstOrNull { it.type == type }

    /** Depth-first descendant search — an IMAGE wraps an INLINE_LINK in 0.7.3, so its
     *  LINK_DESTINATION/LINK_TEXT aren't direct children. */
    private fun ASTNode.findDescendant(
        type: org.intellij.markdown.IElementType,
        depth: Int = 0,
    ): ASTNode? {
        if (depth >= MAX_AST_DEPTH) return null
        for (c in children) {
            if (c.type == type) return c
            c.findDescendant(type, depth + 1)?.let { return it }
        }
        return null
    }

    private fun String.trimAngle(): String = trim().removeSurrounding("<", ">")

    // ── HTML entities ──────────────────────────────────────────────────────────

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "deg" to "°",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "laquo" to "«", "raquo" to "»", "times" to "×", "divide" to "÷",
        "bull" to "•", "middot" to "·", "dagger" to "†", "Dagger" to "‡",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "euro" to "€", "pound" to "£", "cent" to "¢", "yen" to "¥",
        "sect" to "§", "para" to "¶",
    )

    /** Decodes named + numeric (decimal/hex) HTML entities. Unknown sequences pass
     *  through verbatim. Cheap fast-path when the run has no `&`. */
    private fun decodeHtmlEntities(text: String): String {
        if ('&' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = text.indexOf(';', i + 1)
            if (semi == -1 || semi - i > 12) { sb.append(c); i++; continue }
            val body = text.substring(i + 1, semi)
            val decoded: String? = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.toCharsOrNull()
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.toCharsOrNull()
                else -> NAMED_ENTITIES[body]
            }
            if (decoded != null) { sb.append(decoded); i = semi + 1 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun Int.toCharsOrNull(): String? =
        if (this in 0..0x10FFFF) runCatching { String(Character.toChars(this)) }.getOrNull() else null
}
