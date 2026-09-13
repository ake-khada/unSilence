package com.unsilence.app.ui.feed

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.ParseLimits
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.theme.AppType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextFormattingTest {
    private fun format(text: String, kind: Int = 1) = formatNoteText(AnnotatedString(text), kind)

    @Test
    fun `newsletter labels from the reported screen render bold without their markers`() {
        val result = format("📰 **In this week's issue:**\n\n🗞 **BREAKING**")
        assertEquals("📰 In this week's issue:\n\n🗞 BREAKING", result.text)
        assertEquals(listOf("In this week's issue:", "BREAKING"), result.spanStyles.map {
            assertEquals(FontWeight.Bold, it.item.fontWeight)
            result.text.substring(it.start, it.end)
        })
    }

    @Test
    fun `headings use compact note typography and preserve line breaks`() {
        val result = format("# Title\n\n## Section\n### Detail\nBody")
        assertEquals("Title\n\nSection\nDetail\nBody", result.text)
        assertEquals(listOf(AppType.heading, AppType.subheading, AppType.bodyLarge), result.spanStyles.map { it.item.fontSize })
        assertEquals(3, result.paragraphStyles.size)
        assertTrue(result.spanStyles.all { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `all six heading prefixes are supported without article-sized titles`() {
        val result = format((1..6).joinToString("\n") { "${"#".repeat(it)} H$it" })
        assertEquals((1..6).joinToString("\n") { "H$it" }, result.text)
        assertTrue(result.spanStyles.all { it.item.fontSize.value <= AppType.heading.value })
    }

    @Test
    fun `bullets have one glyph and hanging indentation for wrapped lines`() {
        val result = format("- First\n* Second\n+ Third")
        assertEquals("• First\n• Second\n• Third", result.text)
        assertEquals(3, result.paragraphStyles.size)
        assertTrue(result.paragraphStyles.all { it.item.textIndent!!.restLine.value > 0 })
    }

    @Test
    fun `numbered items retain authored numbers and gain hanging indentation`() {
        val result = format("7. Seven\n8) Eight\n42. Forty two")
        assertEquals("7. Seven\n8) Eight\n42. Forty two", result.text)
        assertEquals(3, result.paragraphStyles.size)
        assertTrue(result.paragraphStyles.all { it.item.textIndent!!.restLine.value > 0 })
    }

    @Test
    fun `hashtags arithmetic decimals and mid-line markers stay literal`() {
        val input = AnnotatedString("#nostr\nC# code\n1.5 sats\n-42\na - b\nTitle # elsewhere\n####### not a heading")
        assertSame(input, formatNoteText(input, 1))
    }

    @Test
    fun `ordinary text remains an unchanged fast path`() {
        val input = AnnotatedString("A short note.\nAnother line.")
        assertSame(input, formatNoteText(input, 1))
        val empty = AnnotatedString("")
        assertSame(empty, formatNoteText(empty, 1))
    }

    @Test
    fun `unmatched escaped and whitespace-only bold markers stay literal`() {
        for (input in listOf("**unfinished", "finished**", "\\**literal**", "** **", "****", "***combined***")) {
            val result = format(input)
            assertEquals(input, result.text)
            assertTrue(result.spanStyles.isEmpty())
        }
    }

    @Test
    fun `bold does not span separate source lines`() {
        val input = "**first\nsecond**"
        val result = format(input)
        assertEquals(input, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `inline and fenced code stay literal without formatting their contents`() {
        val input = "`**literal**`\n```text\n# literal\n- literal\n**literal**\n```\n**styled**"
        val result = format(input)
        assertEquals(input.removeSuffix("**styled**") + "styled", result.text)
        assertEquals(1, result.spanStyles.size)
    }

    @Test
    fun `tilde fences and unclosed fences do not expose contained heading syntax`() {
        val input = "~~~\n# literal\n- literal\n**literal**"
        val result = format(input)
        assertEquals(input, result.text)
        assertTrue(result.spanStyles.isEmpty())
        assertTrue(result.paragraphStyles.isEmpty())
    }

    @Test
    fun `four-space indentation does not become a heading or list`() {
        val input = "    # literal\n    - literal\n    **literal**\n\t**literal**"
        assertEquals(input, format(input).text)
        assertTrue(format(input).paragraphStyles.isEmpty())
        assertTrue(format(input).spanStyles.isEmpty())
    }

    @Test
    fun `bold and headings preserve URL targets and hashtag mention callbacks`() {
        var authorTapped = ""
        var tagTapped = ""
        val input = buildAnnotatedString {
            append("# **Read ")
            appendLinkSpan("https://example.com/path?x=1#fragment", "the link")
            append(" with ")
            appendHashtagSpan("nostr") { tagTapped = it }
            append(" and ")
            appendMentionSpan("author", "Alice") { authorTapped = it }
            append("**")
        }
        val result = formatNoteText(input, 1)
        assertEquals("Read the link with #nostr and @Alice", result.text)
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(3, links.size)
        assertEquals("https://example.com/path?x=1#fragment", (links[0].item as LinkAnnotation.Url).url)
        assertEquals("the link", result.text.substring(links[0].start, links[0].end))
        for (link in links.drop(1)) link.item.linkInteractionListener!!.onClick(link.item)
        assertEquals("nostr", tagTapped)
        assertEquals("author", authorTapped)
    }

    @Test
    fun `resolved names and URL labels cannot introduce formatting syntax`() {
        val input = buildAnnotatedString {
            appendMentionSpan("author", "**literal**\n# not a heading") {}
            append("\n")
            appendLinkSpan("https://example.com/**literal**")
        }
        val result = formatNoteText(input, 1)
        assertEquals(input.text, result.text)
        assertEquals(input.getLinkAnnotations(0, input.length), result.getLinkAnnotations(0, result.length))
        assertTrue(result.spanStyles.isEmpty())
        assertTrue(result.paragraphStyles.isEmpty())
    }

    @Test
    fun `a multiline resolved name keeps a single clickable annotation beside formatted text`() {
        val input = buildAnnotatedString {
            append("**Hi** ")
            appendMentionSpan("author", "Alice\n# **literal**") {}
            append("\n**Bye**")
        }
        val result = formatNoteText(input, 1)
        assertEquals("Hi @Alice\n# **literal**\nBye", result.text)
        val mention = result.getLinkAnnotations(0, result.length).single()
        assertEquals("@Alice\n# **literal**", result.text.substring(mention.start, mention.end))
        assertEquals(listOf("Hi", "Bye"), result.spanStyles.map { result.text.substring(it.start, it.end) })
        assertTrue(result.paragraphStyles.isEmpty())
    }

    @Test
    fun `custom emoji annotation survives marker removal at its new offsets`() {
        val input = buildAnnotatedString {
            append("**Hello ")
            appendInlineContent("wave", ":wave:")
            append("**")
        }
        val result = formatNoteText(input, 1)
        assertEquals("Hello :wave:", result.text)
        val before = input.getStringAnnotations(0, input.length).single()
        val after = result.getStringAnnotations(0, result.length).single()
        assertEquals(before.item, after.item)
        assertEquals(before.tag, after.tag)
        assertEquals(before.start - 2, after.start)
        assertEquals(before.end - 2, after.end)
    }

    @Test
    fun `article bodies bios and native media kinds do not gain note formatting`() {
        val input = AnnotatedString("# Header\n**bold**\n- item")
        for (kind in listOf(0, 6, 16, 20, 21, 22, 30023)) assertSame(input, formatNoteText(input, kind))
        assertEquals("Header\nbold\n• item", formatNoteText(input, 1111).text)
    }

    @Test
    fun `source content media and blockquote membership are not rewritten`() {
        val raw = "# Title\nhttps://example.com/photo.jpg\n> **Quote**\n> - item"
        val model = ContentParser.parse(
            id = "note", pubkey = "a".repeat(64), kind = 1, content = raw,
            tagsJson = "[]", createdAt = 1, relayUrl = "wss://relay.test",
            replyToId = null, rootId = null, hasContentWarning = false, contentWarningReason = null,
        )
        assertEquals(raw, model.displayContent)
        assertEquals("https://example.com/photo.jpg", model.media.images.single().url)
        val quote = model.segments.filterIsInstance<Segment.BlockQuote>().single()
        val body = quote.segments.filterIsInstance<Segment.Text>().joinToString("") { it.text }
        assertEquals("Quote\n• item", format(body, model.effectiveKind).text)
        assertEquals("**Quote**\n- item", body)
    }

    @Test
    fun `formatting marks are capped and excess source stays readable`() {
        val result = format("**x** ".repeat(MAX_NOTE_FORMAT_MARKS + 20))
        assertEquals(MAX_NOTE_FORMAT_MARKS, result.spanStyles.size)
        assertTrue(result.text.endsWith("**x** ".repeat(20)))
        assertEquals(MAX_NOTE_FORMAT_MARKS + 20, result.text.count { it == 'x' })
    }

    @Test
    fun `headings and bold share one mark budget`() {
        val result = format("# **Heading**\n".repeat(MAX_NOTE_FORMAT_MARKS))
        assertTrue(result.spanStyles.size <= MAX_NOTE_FORMAT_MARKS)
        assertTrue(result.paragraphStyles.size <= MAX_NOTE_FORMAT_MARKS)
        assertTrue(result.text.endsWith("# **Heading**\n"))
    }

    @Test
    fun `oversized fallback content gets no extra parsing or styling`() {
        val input = AnnotatedString("**x**".repeat(ParseLimits.MAX_NOTE_PARSE_CHARS / 5 + 1))
        assertSame(input, formatNoteText(input, 1))
    }
}
