package com.unsilence.app.data.model.markdown

/**
 * Native document model for GFM article markdown (kind-30023 body). Pure Kotlin —
 * NO Compose, NO Android — so the parser is fully JVM-unit-testable. The Compose
 * renderer (phase 2) consumes this model; it is the article-body analogue of the
 * note path's flat Segment list, but with real block structure (headings, lists,
 * tables, code) the note tokenizer doesn't model.
 *
 * Raw HTML is flattened to text/code and never executed. nostr: URIs are plain Links
 * in v1 (rich note-embed deferred). Mentions are not modeled (→ Link).
 */
data class MarkdownDocument(
    val blocks: List<MdBlock>,
    /** A cap (input chars / per-block inlines / total blocks / table cells) was hit. */
    val truncated: Boolean = false,
)

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class BlockQuote(val blocks: List<MdBlock>) : MdBlock
    /** Ordered/unordered list; each item is itself a list of blocks (nesting). */
    data class ListBlock(val ordered: Boolean, val items: List<List<MdBlock>>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class Image(val url: String, val alt: String?) : MdBlock
    data class Table(val table: MdTable) : MdBlock
    data object HorizontalRule : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Emphasis(val children: List<MdInline>) : MdInline
    data class Strikethrough(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val url: String, val children: List<MdInline>) : MdInline
    data class Hashtag(val tag: String) : MdInline
}

enum class MdAlign { Left, Center, Right }

/**
 * GFM table. Column count is the header's; rows are normalized to it. Alignment is
 * recovered from the separator row's cell text (0.7.3 exposes no alignment metadata).
 */
data class MdTable(
    val columns: List<MdTableColumn>,
    val rows: List<MdTableRow>,
)

data class MdTableColumn(
    val header: List<MdInline>,
    val align: MdAlign,
)

data class MdTableRow(
    /** One inline list per column (already normalized to the column count). */
    val cells: List<List<MdInline>>,
)
