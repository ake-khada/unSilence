package com.unsilence.app.ui.feed

import com.unsilence.app.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineRunLayoutTest {
    private val link = Segment.Link("https://example.test/paintings")
    private val preview = InlineRunBlock.Preview(link.url)
    private fun text(value: String) = Segment.Text(value)
    private fun block(vararg segments: Segment) = InlineRunBlock.Text(segments.toList())

    @Test fun standaloneLinkReplacesItsLineInSourceOrder() {
        val source = listOf(text("Intro\n\n"), link, text("\n\nAfter\n\nLast paragraph"))
        assertEquals(
            listOf(block(text("Intro")), preview, block(text("After\n\nLast paragraph"))),
            planInlineRun(source, true),
        )
        assertEquals("Intro\n\n", (source.first() as Segment.Text).text)
        assertEquals("\n\nAfter\n\nLast paragraph", (source.last() as Segment.Text).text)
    }

    @Test fun linkOnlyNeverBecomesBlank() {
        assertEquals(listOf(preview), planInlineRun(listOf(link), true))
        assertEquals(listOf(preview), planInlineRun(listOf(text("\r\n \t"), link, text(" \r\n")), true))
    }

    @Test fun leadingAndTrailingStandaloneLinksKeepTheirPosition() {
        assertEquals(listOf(preview, block(text("After"))), planInlineRun(listOf(link, text("\nAfter")), true))
        assertEquals(listOf(block(text("Before")), preview), planInlineRun(listOf(text("Before\n"), link), true))
    }

    @Test fun sentenceLinkStaysReadableWithPreviewAfterTheRun() {
        val source = listOf(text("Read "), link, text(" for details."))
        assertEquals(listOf(InlineRunBlock.Text(source), preview), planInlineRun(source, true))
    }

    @Test fun punctuationOrOtherInlineTokensPreventExtraction() {
        val mention = Segment.MentionPubkey("a".repeat(64), emptyList())
        for (before in listOf(text("("), mention, Segment.Hashtag("art"))) {
            val source = listOf(before, text(" "), link, text("\nAfter"))
            val expectedText = if (before is Segment.Text) listOf(text("( "), link, text("\nAfter")) else source
            assertEquals(listOf(InlineRunBlock.Text(expectedText), preview), planInlineRun(source, true))
        }
        val source = listOf(link, text("."))
        assertEquals(listOf(InlineRunBlock.Text(source), preview), planInlineRun(source, true))
    }

    @Test fun exhaustedPreviewCapRetainsEveryUrlAndParagraph() {
        val source = listOf(text("Intro\n\n"), link, text("\n\nAfter"))
        assertEquals(listOf(InlineRunBlock.Text(source)), planInlineRun(source, false))
    }

    @Test fun onlyFirstLinkIsPromotedAndLaterLinksRemainInOrder() {
        val second = Segment.Link("https://example.test/second")
        val source = listOf(link, text("\n\nMiddle\n\n"), second, text("\n\nEnd"))
        assertEquals(
            listOf(preview, block(text("Middle\n\n"), second, text("\n\nEnd"))),
            planInlineRun(source, true),
        )
        val sameLine = listOf(link, text(" "), second)
        assertEquals(listOf(InlineRunBlock.Text(sameLine), preview), planInlineRun(sameLine, true))
    }

    @Test fun crlfAndWhitespaceOnlySeparatorLinesAreConsumedNotParagraphIndentation() {
        val source = listOf(text("Intro\r\n \t\r\n  "), link, text(" \r\n\t\r\n  After\r\n\r\nLast"))
        assertEquals(
            listOf(block(text("Intro")), preview, block(text("  After\r\n\r\nLast"))),
            planInlineRun(source, true),
        )
    }

    @Test fun ordinaryParagraphSpacingAndIndentationAreUntouched() {
        val value = "  First\n\n\nSecond\n    indented"
        assertEquals(listOf(block(text(value))), planInlineRun(listOf(text(value)), true))
    }

    @Test fun whitespaceOnlyRunsDoNotCreateEmptyTextBlocks() {
        assertEquals(emptyList<InlineRunBlock>(), planInlineRun(listOf(text(" \n\r\n\t")), true))
        assertEquals(emptyList<InlineRunBlock>(), planInlineRun(emptyList(), true))
    }

    @Test fun adjacentTextSegmentsDoNotChangeStandaloneDetection() {
        val source = listOf(text("Intro"), text("\n"), text("  "), link, text("\n"), text("After"))
        assertEquals(listOf(block(text("Intro")), preview, block(text("After"))), planInlineRun(source, true))
        val inline = listOf(text("Intro"), text(" "), link)
        assertEquals(listOf(block(text("Intro "), link), preview), planInlineRun(inline, true))
        val indented = listOf(text("  "), text("  "), link)
        assertEquals(listOf(block(text("    "), link), preview), planInlineRun(indented, true))
    }

    @Test fun fencedAndIndentedLiteralContextIsNotSplit() {
        for (prefix in listOf("```\n", "~~~text\n", "    ", "\t")) {
            val source = listOf(text(prefix), link, text("\n**literal**\n```"))
            assertEquals(listOf(InlineRunBlock.Text(source), preview), planInlineRun(source, true))
        }
    }

    @Test fun indentedLinkRetainsItsLiteralIndentationWithoutAFence() {
        for (indent in listOf("    ", "\t")) {
            val source = listOf(text(indent), link, text("\n    **literal**"))
            assertEquals(listOf(InlineRunBlock.Text(source), preview), planInlineRun(source, true))
        }
    }
}
