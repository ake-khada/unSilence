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

    private class Budget {
        var truncated = false
        var blocksRemaining = MAX_BLOCKS
    }

    fun parse(markdown: String): MarkdownDocument {
        val inputCapped = markdown.length > ParseLimits.MAX_ARTICLE_PARSE_CHARS
        val src = if (inputCapped) markdown.take(ParseLimits.MAX_ARTICLE_PARSE_CHARS) else markdown
        val budget = Budget()
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(src)
        val blocks = parseBlocks(tree.children, src, budget).toMutableList()
        val truncated = inputCapped || budget.truncated
        if (truncated) blocks.add(MdBlock.Paragraph(listOf(MdInline.Text(ParseLimits.TRUNCATION_MARKER))))
        return MarkdownDocument(blocks, truncated)
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    private fun parseBlocks(nodes: List<ASTNode>, src: String, budget: Budget): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        for (node in nodes) {
            if (budget.blocksRemaining <= 0) { budget.truncated = true; break }
            val block = blockFor(node, src, budget) ?: continue
            budget.blocksRemaining--
            out.add(block)
        }
        return out
    }

    private fun blockFor(node: ASTNode, src: String, budget: Budget): MdBlock? = when (node.type) {
        MarkdownElementTypes.ATX_1 -> MdBlock.Heading(1, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_2 -> MdBlock.Heading(2, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_3 -> MdBlock.Heading(3, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_4 -> MdBlock.Heading(4, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_5 -> MdBlock.Heading(5, headingInlines(node, src, budget))
        MarkdownElementTypes.ATX_6 -> MdBlock.Heading(6, headingInlines(node, src, budget))
        MarkdownElementTypes.SETEXT_1 -> MdBlock.Heading(1, headingInlines(node, src, budget))
        MarkdownElementTypes.SETEXT_2 -> MdBlock.Heading(2, headingInlines(node, src, budget))

        MarkdownElementTypes.PARAGRAPH -> paragraphOrImage(node, src, budget)
        MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.BlockQuote(parseBlocks(node.children, src, budget))
        MarkdownElementTypes.UNORDERED_LIST -> listBlock(node, src, budget, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> listBlock(node, src, budget, ordered = true)
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

    private fun listBlock(node: ASTNode, src: String, budget: Budget, ordered: Boolean): MdBlock {
        val items = node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .map { parseBlocks(it.children, src, budget) }
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
        val url = node.findChild(MarkdownElementTypes.LINK_DESTINATION)?.text(src)?.trimAngle() ?: ""
        val alt = node.findChild(MarkdownElementTypes.LINK_TEXT)?.text(src)
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

    /** Walk a block node's children into inline spans, bounded by the per-block cap. */
    private fun parseInlines(node: ASTNode, src: String, budget: Budget): List<MdInline> {
        val out = mutableListOf<MdInline>()
        appendInlines(node.children, src, out, budget)
        return out
    }

    private fun appendInlines(
        nodes: List<ASTNode>,
        src: String,
        out: MutableList<MdInline>,
        budget: Budget,
    ) {
        for (node in nodes) {
            if (out.size >= MAX_INLINES_PER_BLOCK) { budget.truncated = true; return }
            when (node.type) {
                MarkdownTokenTypes.TEXT, MarkdownTokenTypes.WHITE_SPACE ->
                    appendTextWithHashtags(node.text(src), out)
                MarkdownTokenTypes.EOL -> out.add(MdInline.Text(" "))
                MarkdownTokenTypes.HARD_LINE_BREAK -> out.add(MdInline.Text("\n"))

                MarkdownElementTypes.EMPH ->
                    out.add(MdInline.Emphasis(childInlines(node, src, budget)))
                MarkdownElementTypes.STRONG ->
                    out.add(MdInline.Strong(childInlines(node, src, budget)))
                GFMElementTypes.STRIKETHROUGH ->
                    out.add(MdInline.Strikethrough(childInlines(node, src, budget)))
                MarkdownElementTypes.CODE_SPAN ->
                    out.add(MdInline.Code(node.text(src).trim('`').trim()))

                MarkdownElementTypes.INLINE_LINK,
                MarkdownElementTypes.FULL_REFERENCE_LINK,
                MarkdownElementTypes.SHORT_REFERENCE_LINK ->
                    out.add(linkInline(node, src, budget))

                MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.AUTOLINK,
                MarkdownTokenTypes.EMAIL_AUTOLINK -> {
                    val url = node.text(src).trimAngle()
                    out.add(MdInline.Link(url, listOf(MdInline.Text(url))))
                }

                // Inline image (not a standalone block) → flatten to a link (no media).
                MarkdownElementTypes.IMAGE -> {
                    val url = node.findChild(MarkdownElementTypes.LINK_DESTINATION)?.text(src)?.trimAngle() ?: ""
                    val alt = node.findChild(MarkdownElementTypes.LINK_TEXT)?.text(src)
                        ?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: url
                    if (url.isNotEmpty()) out.add(MdInline.Link(url, listOf(MdInline.Text(alt))))
                }

                in DELIMITERS -> { /* skip delimiters */ }

                else ->
                    // Unknown element → recurse; unknown leaf token → keep its text (don't drop content).
                    if (node.children.isEmpty()) {
                        appendTextWithHashtags(node.text(src), out)
                    } else {
                        appendInlines(node.children, src, out, budget)
                    }
            }
        }
    }

    /** Inlines of a wrapper node (emph/strong/strikethrough/link-text) minus delimiters. */
    private fun childInlines(node: ASTNode, src: String, budget: Budget): List<MdInline> {
        val out = mutableListOf<MdInline>()
        appendInlines(node.children, src, out, budget)
        return out
    }

    private fun linkInline(node: ASTNode, src: String, budget: Budget): MdInline {
        val dest = node.findChild(MarkdownElementTypes.LINK_DESTINATION)?.text(src)?.trimAngle()
        val textNode = node.findChild(MarkdownElementTypes.LINK_TEXT)
        val children = if (textNode != null) childInlines(textNode, src, budget)
            else listOf(MdInline.Text(node.text(src)))
        // Reference links without a resolvable destination → render the text, no url.
        return MdInline.Link(dest ?: "", children.ifEmpty { listOf(MdInline.Text(dest ?: "")) })
    }

    /** Split a raw text run into Text + Hashtag inlines (reuses the note tokenizer's rule). */
    private fun appendTextWithHashtags(text: String, out: MutableList<MdInline>) {
        if (text.isEmpty()) return
        val tags = ContentParser.findHashtags(text)
        if (tags.isEmpty()) { out.add(MdInline.Text(text)); return }
        var cursor = 0
        for ((start, end, tag) in tags) {
            if (start > cursor) out.add(MdInline.Text(text.substring(cursor, start)))
            out.add(MdInline.Hashtag(tag))
            cursor = end
        }
        if (cursor < text.length) out.add(MdInline.Text(text.substring(cursor)))
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
            MdTableColumn(
                header = parseInlineMarkdown(cell, budget),
                align = aligns.getOrElse(i) { MdAlign.Left },
            )
        }
        val colCount = columns.size
        val bodyLines = lines.drop(2)
        if (bodyLines.size > MAX_TABLE_ROWS) budget.truncated = true
        val rows = bodyLines.take(MAX_TABLE_ROWS).map { line ->
            val cells = splitRow(line).let { capCells(it, budget) }
            // Normalize to header column count: pad missing, truncate extras.
            val normalized = (0 until colCount).map { idx ->
                parseInlineMarkdown(cells.getOrElse(idx) { "" }, budget)
            }
            MdTableRow(normalized)
        }
        return MdBlock.Table(MdTable(columns, rows))
    }

    private fun capCells(cells: List<String>, budget: Budget): List<String> {
        if (cells.size > MAX_TABLE_COLS) budget.truncated = true
        return cells.take(MAX_TABLE_COLS).map {
            if (it.length > MAX_CELL_CHARS) { budget.truncated = true; it.take(MAX_CELL_CHARS) } else it
        }
    }

    /** Parse a fragment's text as inline markdown (table cells + headings) via a sub-parse. */
    private fun parseInlineMarkdown(cellText: String, budget: Budget): List<MdInline> {
        val t = cellText.trim()
        if (t.isEmpty()) return emptyList()
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(t)
        val out = mutableListOf<MdInline>()
        // Collect inlines from every paragraph in the sub-tree (cells are single-line).
        fun walk(n: ASTNode) {
            if (n.type == MarkdownElementTypes.PARAGRAPH) appendInlines(n.children, t, out, budget)
            else n.children.forEach(::walk)
        }
        walk(tree)
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

    private fun String.trimAngle(): String = trim().removeSurrounding("<", ">")
}
