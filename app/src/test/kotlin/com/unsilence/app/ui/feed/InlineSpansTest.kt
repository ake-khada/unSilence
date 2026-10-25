package com.unsilence.app.ui.feed

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.unsilence.app.data.model.markdown.MdInline
import com.unsilence.app.ui.markdown.appendMdInlines
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep
import org.junit.Assert.*
import org.junit.Test

class InlineSpansTest {
    private fun note(url: String) = buildAnnotatedString { appendLinkSpan(url) }

    @Test fun `raw links keep entire host and port while shortening only displayed suffix`() {
        val host = "www.very-long-subdomain.example.com:8443"
        val url = "https://$host/some/long/path/to/a/resource?token=original#section"
        val text = note(url)
        assertTrue(text.text.startsWith("$host/"))
        assertTrue(text.text.endsWith("…"))
        assertFalse(text.text.contains("token="))
        assertEquals(url, (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url)
        assertEquals(0, text.getLinkAnnotations(0, text.length).single().start)
        assertEquals(text.length, text.getLinkAnnotations(0, text.length).single().end)
    }

    @Test fun `host length is never subject to the path budget`() {
        val host = "a".repeat(60) + "." + "b".repeat(60) + ".example.com"
        assertEquals(host, note("https://$host").text)
        assertTrue(note("https://$host/" + "path".repeat(30)).text.startsWith("$host/"))
    }

    @Test fun `userinfo cannot disguise the actual destination host`() {
        val url = "https://trusted.example@actual.example:443/a/long/path?secret=unchanged"
        val text = note(url)
        assertTrue(text.text.startsWith("actual.example:443/"))
        assertFalse(text.text.contains("trusted.example"))
        assertEquals(url, (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url)
    }

    @Test fun `short paths stay readable and omitted query or fragment is indicated`() {
        assertEquals("example.com/guide", note("https://example.com/guide").text)
        assertEquals("example.com/item…", note("https://example.com/item?id=123").text)
        assertEquals("example.com/…", note("https://example.com/?long=" + "x".repeat(100)).text)
        assertEquals("example.com/guide…", note("https://example.com/guide#intro").text)
        assertTrue(note("https://[2001:db8::1]:8443/" + "path".repeat(20)).text.startsWith("[2001:db8::1]:8443/"))
    }

    @Test fun `path abbreviation does not leave a partial escape or surrogate`() {
        val percent = "https://example.com/" + "a".repeat(21) + "%20suffix-long-enough-to-shorten"
        assertEquals("example.com/" + "a".repeat(21) + "…", note(percent).text)
        val emoji = "https://example.com/" + "a".repeat(22) + "🐈suffix-long-enough-to-shorten"
        assertEquals("example.com/" + "a".repeat(22) + "…", note(emoji).text)
        assertEquals(percent, (note(percent).getLinkAnnotations(0, note(percent).length).single().item as LinkAnnotation.Url).url)
        assertEquals(emoji, (note(emoji).getLinkAnnotations(0, note(emoji).length).single().item as LinkAnnotation.Url).url)
    }

    @Test fun `unsupported or ambiguously parsed URLs keep their original label and destination`() {
        for (url in listOf("mailto:person@example.com", "nostr:note1example", "https://example.com/%zz", "https://例え.example/" + "path".repeat(20))) {
            assertEquals(url, note(url).text)
            assertEquals(url, (note(url).getLinkAnnotations(0, note(url).length).single().item as LinkAnnotation.Url).url)
        }
    }

    @Test fun `notes and article raw links share quieter styling and authored labels stay intact`() {
        val url = "https://example.com/" + "long-path/".repeat(10)
        val raw = note(url)
        val article = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text(url)))), {}) }
        assertEquals(raw, article)
        val named = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text("Read the guide")))), {}) }
        assertEquals("Read the guide", named.text)
        for (text in listOf(raw, article, named)) {
            val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
            assertEquals(url, link.url)
            assertEquals(BrandDeep, link.styles!!.style!!.color)
            assertEquals(FontWeight.Medium, link.styles!!.style!!.fontWeight)
        }
    }

    @Test fun `mentions hashtags and surrounding formatting keep their existing behavior`() {
        val text = buildAnnotatedString {
            appendMentionSpan("author", "Alice", {})
            append(" ")
            appendHashtagSpan("topic", {})
        }
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(Brand, links[0].item.styles!!.style!!.color)
        assertEquals(BrandDeep, links[1].item.styles!!.style!!.color)
        val url = "https://example.com/" + "very-long-path/".repeat(10)
        val formatted = formatNoteText(buildAnnotatedString { append("**Read** "); appendLinkSpan(url) }, 1)
        assertTrue(formatted.text.startsWith("Read example.com/"))
        assertEquals(url, (formatted.getLinkAnnotations(0, formatted.length).single().item as LinkAnnotation.Url).url)
    }
}
