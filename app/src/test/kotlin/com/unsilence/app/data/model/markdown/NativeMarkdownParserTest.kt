package com.unsilence.app.data.model.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NativeMarkdownParser] — pure Kotlin, runs on plain JVM (no Compose,
 * no Quartz crypto, so JDK-17-clean). The unit suite is the gate for phase 1.
 */
class NativeMarkdownParserTest {

    private fun parse(md: String) = NativeMarkdownParser.parse(md)
    private fun blocks(md: String) = parse(md).blocks

    /** Flatten an inline tree to its visible text for assertions. */
    private fun MdInline.plain(): String = when (this) {
        is MdInline.Text -> text
        is MdInline.Strong -> children.plain()
        is MdInline.Emphasis -> children.plain()
        is MdInline.Strikethrough -> children.plain()
        is MdInline.Code -> text
        is MdInline.Link -> children.plain()
        is MdInline.Hashtag -> "#$tag"
    }
    private fun List<MdInline>.plain(): String = joinToString("") { it.plain() }

    private fun List<MdInline>.flatten(): List<MdInline> = flatMap {
        when (it) {
            is MdInline.Strong -> it.children.flatten()
            is MdInline.Emphasis -> it.children.flatten()
            is MdInline.Strikethrough -> it.children.flatten()
            is MdInline.Link -> listOf(it) + it.children.flatten()
            else -> listOf(it)
        }
    }

    // ── Headings ──────────────────────────────────────────────────────────────

    @Test
    fun `heading levels map from node type`() {
        for (n in 1..6) {
            val b = blocks("${"#".repeat(n)} Title $n").first()
            assertTrue(b is MdBlock.Heading)
            b as MdBlock.Heading
            assertEquals(n, b.level)
            assertEquals("Title $n", b.inlines.plain().trim())
        }
    }

    // ── Paragraphs ──────────────────────────────────────────────────────────────

    @Test
    fun `two paragraphs become two paragraph blocks`() {
        val bs = blocks("First para.\n\nSecond para.")
        val paras = bs.filterIsInstance<MdBlock.Paragraph>()
        assertEquals(2, paras.size)
        assertEquals("First para.", paras[0].inlines.plain().trim())
        assertEquals("Second para.", paras[1].inlines.plain().trim())
    }

    // ── Blockquotes ─────────────────────────────────────────────────────────────

    @Test
    fun `nested blockquote nests`() {
        val outer = blocks("> outer\n>\n> > inner").first()
        assertTrue(outer is MdBlock.BlockQuote)
        outer as MdBlock.BlockQuote
        assertTrue("contains a nested blockquote", outer.blocks.any { it is MdBlock.BlockQuote })
    }

    // ── Lists ────────────────────────────────────────────────────────────────────

    @Test
    fun `unordered list collects items`() {
        val b = blocks("- a\n- b\n- c").first()
        assertTrue(b is MdBlock.ListBlock)
        b as MdBlock.ListBlock
        assertFalse(b.ordered)
        assertEquals(3, b.items.size)
    }

    @Test
    fun `ordered list is marked ordered`() {
        val b = blocks("1. a\n2. b").first()
        assertTrue(b is MdBlock.ListBlock)
        assertTrue((b as MdBlock.ListBlock).ordered)
    }

    // ── Code ───────────────────────────────────────────────────────────────────

    @Test
    fun `fenced code keeps language and body`() {
        val b = blocks("```kotlin\nval x = 1\nval y = 2\n```").first()
        assertTrue(b is MdBlock.CodeBlock)
        b as MdBlock.CodeBlock
        assertEquals("kotlin", b.language)
        assertTrue(b.code.contains("val x = 1"))
        assertTrue(b.code.contains("val y = 2"))
    }

    // ── Inline spans ─────────────────────────────────────────────────────────────

    @Test
    fun `inline strong emphasis code strikethrough`() {
        val inlines = (blocks("**b** *i* `c` ~~s~~").first() as MdBlock.Paragraph).inlines
        assertTrue(inlines.any { it is MdInline.Strong && it.children.plain() == "b" })
        assertTrue(inlines.any { it is MdInline.Emphasis && it.children.plain() == "i" })
        assertTrue(inlines.any { it is MdInline.Code && it.text == "c" })
        assertTrue(inlines.any { it is MdInline.Strikethrough && it.children.plain() == "s" })
    }

    @Test
    fun `inline link keeps url and text`() {
        val inlines = (blocks("see [docs](https://example.com/x) here").first() as MdBlock.Paragraph).inlines
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/x", link.url)
        assertEquals("docs", link.children.plain())
    }

    @Test
    fun `autolink becomes a link`() {
        val inlines = (blocks("<https://example.com>").first() as MdBlock.Paragraph).inlines
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `inline hashtag becomes MdInline Hashtag`() {
        val inlines = (blocks("gm #nostr fam").first() as MdBlock.Paragraph).inlines
        assertTrue(inlines.any { it is MdInline.Hashtag && it.tag == "nostr" })
    }

    // ── Raw HTML ─────────────────────────────────────────────────────────────────

    @Test
    fun `raw html does not execute and is preserved as text`() {
        val bs = blocks("<div onclick=\"x\">danger</div>")
        // Never an Image/Table/executed node; content kept as plain text somewhere.
        assertTrue(bs.none { it is MdBlock.Image })
        assertTrue(bs.any { b -> b is MdBlock.Paragraph && b.inlines.plain().contains("danger") }
            || bs.any { b -> b is MdBlock.CodeBlock && b.code.contains("danger") })
    }

    // ── Tables ───────────────────────────────────────────────────────────────────

    @Test
    fun `simple table parses header and rows`() {
        val md = """
            | Name | Age |
            |------|-----|
            | Ada  | 36  |
            | Bob  | 42  |
        """.trimIndent()
        val b = blocks(md).first()
        assertTrue(b is MdBlock.Table)
        val t = (b as MdBlock.Table).table
        assertEquals(2, t.columns.size)
        assertEquals("Name", t.columns[0].header.plain().trim())
        assertEquals("Age", t.columns[1].header.plain().trim())
        assertEquals(2, t.rows.size)
        assertEquals("Ada", t.rows[0].cells[0].plain().trim())
        assertEquals("42", t.rows[1].cells[1].plain().trim())
    }

    @Test
    fun `table alignment from separator row`() {
        val md = """
            | L | C | R |
            |:--|:-:|--:|
            | 1 | 2 | 3 |
        """.trimIndent()
        val t = (blocks(md).first() as MdBlock.Table).table
        assertEquals(MdAlign.Left, t.columns[0].align)
        assertEquals(MdAlign.Center, t.columns[1].align)
        assertEquals(MdAlign.Right, t.columns[2].align)
    }

    @Test
    fun `escaped pipe inside a cell stays one cell`() {
        val md = """
            | expr | note |
            |------|------|
            | a \| b | or |
        """.trimIndent()
        val t = (blocks(md).first() as MdBlock.Table).table
        assertEquals(2, t.rows[0].cells.size)
        assertEquals("a | b", t.rows[0].cells[0].plain().trim())
    }

    @Test
    fun `uneven rows normalized to column count`() {
        val md = """
            | a | b | c |
            |---|---|---|
            | 1 | 2 |
            | 1 | 2 | 3 | 4 |
        """.trimIndent()
        val t = (blocks(md).first() as MdBlock.Table).table
        assertEquals(3, t.columns.size)
        // short row padded to 3 (third empty), long row truncated to 3
        assertEquals(3, t.rows[0].cells.size)
        assertEquals("", t.rows[0].cells[2].plain().trim())
        assertEquals(3, t.rows[1].cells.size)
    }

    @Test
    fun `inline formatting inside table cells preserved`() {
        val md = """
            | a | b |
            |---|---|
            | **bold** | [lnk](https://e.com) |
        """.trimIndent()
        val t = (blocks(md).first() as MdBlock.Table).table
        assertTrue(t.rows[0].cells[0].flatten().any { it is MdInline.Strong } ||
            t.rows[0].cells[0].any { it is MdInline.Strong })
        val link = t.rows[0].cells[1].filterIsInstance<MdInline.Link>().singleOrNull()
        assertEquals("https://e.com", link?.url)
    }

    @Test
    fun `table-like text without a separator is not a broken table`() {
        // No separator row → GFM doesn't see a table; must NOT render as a Table block.
        val bs = blocks("| a | b |\n| c | d |")
        assertTrue("no Table block for separator-less input", bs.none { it is MdBlock.Table })
    }

    // ── Render caps (H-spam) ─────────────────────────────────────────────────────

    @Test
    fun `over-length input is truncated`() {
        val huge = "word ".repeat(60_000) // ~300k chars, over the 200k cap
        val doc = parse(huge)
        assertTrue(doc.truncated)
        assertTrue(doc.blocks.last() is MdBlock.Paragraph)
    }

    @Test
    fun `mega-paragraph over the per-block inline cap is truncated`() {
        val manyTags = (1..400).joinToString(" ") { "#t$it" } // one paragraph, >150 inlines
        val doc = parse(manyTags)
        assertTrue("per-block inline cap trips truncation", doc.truncated)
    }

    @Test
    fun `huge block count over the backstop is truncated`() {
        val manyBlocks = (1..2_100).joinToString("\n\n") { "p$it" }
        val doc = parse(manyBlocks)
        assertTrue("total-block backstop trips truncation", doc.truncated)
    }

    @Test
    fun `normal article is not truncated`() {
        val md = """
            # Title

            A normal paragraph with a [link](https://e.com) and a #hashtag.

            ## Section

            - one
            - two

            > a quote

            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val doc = parse(md)
        assertFalse(doc.truncated)
        assertTrue(doc.blocks.any { it is MdBlock.Heading })
        assertTrue(doc.blocks.any { it is MdBlock.ListBlock })
        assertTrue(doc.blocks.any { it is MdBlock.CodeBlock })
        assertTrue(doc.blocks.any { it is MdBlock.BlockQuote })
    }

    // ── Phase 1b: nested cap, global cells, fast path, contracts ────────────────

    @Test
    fun `nested inline spans count toward the shared per-block cap`() {
        // 4 adjacent bold spans, each 60 code-spans → no single inline list exceeds 150,
        // but the TOTAL flattened count does. A per-list cap (the old bug) would miss it;
        // the shared per-block budget catches it.
        val md = (1..4).joinToString(" ") { "**" + (1..60).joinToString(" ") { "`c`" } + "**" }
        assertTrue("nested spans must count toward the per-block bound", parse(md).truncated)
    }

    @Test
    fun `flat per-block hashtag cap still trips (regression)`() {
        val manyTags = (1..400).joinToString(" ") { "#t$it" }
        assertTrue(parse(manyTags).truncated)
    }

    @Test
    fun `combined table cells over the global budget truncate`() {
        fun table(rows: Int, cols: Int): String {
            val header = "| " + (1..cols).joinToString(" | ") { "h$it" } + " |"
            val sep = "|" + "---|".repeat(cols)
            val body = (1..rows).joinToString("\n") { "| " + (1..cols).joinToString(" | ") { "c" } + " |" }
            return "$header\n$sep\n$body"
        }
        // 3 × (200 rows × 12 cols) = 7200 cells > the 5000 global budget.
        val md = (1..3).joinToString("\n\n") { table(200, 12) }
        assertTrue(parse(md).truncated)
    }

    @Test
    fun `plain table cell still splits hashtags via the fast path`() {
        val md = """
            | topic | x |
            |-------|---|
            | #nostr | y |
        """.trimIndent()
        val t = (blocks(md).first() as MdBlock.Table).table
        assertTrue(t.rows[0].cells[0].any { it is MdInline.Hashtag && it.tag == "nostr" })
    }

    @Test
    fun `reference link has empty url contract`() {
        val md = "see [docs][ref] here\n\n[ref]: https://e.com"
        val link = (blocks(md).first() as MdBlock.Paragraph).inlines
            .filterIsInstance<MdInline.Link>().single()
        assertEquals("", link.url)
        assertEquals("docs", link.children.plain())
    }

    @Test
    fun `image inside a table cell flattens to a link`() {
        val md = """
            | pic | x |
            |-----|---|
            | ![alt](https://e.com/i.png) | y |
        """.trimIndent()
        val t = (blocks(md).first() as MdBlock.Table).table
        val link = t.rows[0].cells[0].filterIsInstance<MdInline.Link>().single()
        assertEquals("https://e.com/i.png", link.url)
        assertEquals("alt", link.children.plain())
    }
}
