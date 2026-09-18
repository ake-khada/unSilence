package com.unsilence.app.ui.feed

import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineEmojiMeasurementTest {
    private val placeholder = Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.Center)

    @Test fun measuredRangesMatchRenderedEmojiAfterFormatting() {
        val source = buildAnnotatedString {
            appendTextWithEmoji("**Hi :wave:** :unknown: :wave:", mapOf("wave" to "https://example.test/e.png"))
        }
        val formatted = formatNoteText(source, 1)
        val ranges = inlineEmojiPlaceholders(formatted, mapOf("wave" to placeholder))
        assertEquals(2, ranges.size)
        assertEquals(listOf(":wave:", ":wave:"), ranges.map { formatted.text.substring(it.start, it.end) })
        assertEquals(listOf(placeholder, placeholder), ranges.map { it.item })
    }

    @Test fun missingEmojiAndUnrelatedAnnotationsDoNotReserveSpace() {
        val text = buildAnnotatedString {
            pushStringAnnotation("link", "wave")
            append("text")
            pop()
            appendTextWithEmoji(":wave:", mapOf("wave" to "url"))
        }
        assertEquals(emptyList<Any>(), inlineEmojiPlaceholders(text, emptyMap()))
        assertEquals(1, inlineEmojiPlaceholders(text, mapOf("wave" to placeholder)).size)
    }
}
