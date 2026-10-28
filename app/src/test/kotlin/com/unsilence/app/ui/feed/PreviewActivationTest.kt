package com.unsilence.app.ui.feed

import androidx.compose.ui.text.buildAnnotatedString
import com.unsilence.app.data.model.Segment
import org.junit.Assert.*
import org.junit.Test

class PreviewActivationTest {
    private val rejected = listOf(
        "https://0x7f.1/path", "https://127.1", "https://0177.0.0.1", "https://2130706433",
        "https:///empty", "https://", "https:opaque", "https://user@/path",
        "https://example.com:", "https://example.com:99999", "https://example.com:-1",
        "https://[invalid]/", "https://[::1", "https://example.com/has space",
        "https://example.com/\npath", "https://example.com/\u0000path",
        "nostrsigner:fixture", "//example.com", "/relative",
    )

    @Test fun activationAndStrictPreviewLabelRejectTheSameDestinationsAsInlineSpans() {
        for (url in rejected) {
            assertFalse(url, isOpenableContentLink(url))
            assertNull(url, previewHostLabel(url))
            assertNull(url, contentLinkLabel(url))
            val span = buildAnnotatedString { appendLinkSpan(url, "Label") }
            assertEquals("Label", span.text)
            assertTrue(url, span.getLinkAnnotations(0, span.length).isEmpty())
            assertTrue(url, span.spanStyles.isEmpty())
        }
    }

    @Test fun allSixAcceptedSchemesKeepTheirInlineActivationDecision() {
        for (url in listOf("http://example.com", "HTTPS://example.com", "nostr:npub1fixture",
            "mailto:person@example.com", "lightning:lnurl1fixture", "bitcoin:bc1qfixture")) {
            assertTrue(url, isOpenableContentLink(url))
            assertNotNull(url, contentLinkLabel(url))
        }
    }

    @Test fun strictHostLabelPreservesNormalizedHostsIpv6AndEffectivePorts() {
        for ((url, label) in listOf(
            "https://Example.COM:443/path" to "example.com",
            "http://example.com:80/path" to "example.com",
            "https://user@аpple.com:8443/path" to "xn--pple-43d.com:8443",
            "https://[2001:0db8::1]:443/path" to "[2001:db8::1]",
            "https://[2001:db8::1]:8443/path" to "[2001:db8::1]:8443",
        )) assertEquals(label, previewHostLabel(url))
    }

    @Test fun rejectedLinkNeverBecomesAPreviewAndRemainsPlainInlineText() {
        for (url in rejected) {
            val source = listOf(Segment.Link(url))
            assertEquals(url, listOf(InlineRunBlock.Text(source)), planInlineRun(source, true))
            val span = buildAnnotatedString { appendLinkSpan(url) }
            assertEquals(url, span.text)
            assertTrue(url, span.getLinkAnnotations(0, span.length).isEmpty())
            assertTrue(url, span.spanStyles.isEmpty())
        }
    }

    @Test fun rejectedFirstLinkDoesNotConsumeTheSinglePreview() {
        val bad = Segment.Link(rejected.first())
        val good = Segment.Link("https://example.com/guide")
        val later = Segment.Link("https://example.com/later")
        val source = listOf(bad, Segment.Text(" then "), good, Segment.Text(" then "), later)
        assertEquals(listOf(InlineRunBlock.Text(source), InlineRunBlock.Preview(good.url)), planInlineRun(source, true))
    }

    @Test fun standaloneValidLinkStillSplitsAfterRejectedTextInSourceOrder() {
        val bad = Segment.Link(rejected.first())
        val good = Segment.Link("https://example.com/guide")
        assertEquals(
            listOf(InlineRunBlock.Text(listOf(bad)), InlineRunBlock.Preview(good.url),
                InlineRunBlock.Text(listOf(Segment.Text("After")))),
            planInlineRun(listOf(bad, Segment.Text("\n\n"), good, Segment.Text("\n\nAfter")), true),
        )
    }

    @Test fun entirelyRejectedRunKeepsAllTextAndHasNoPreview() {
        val source = rejected.flatMap { listOf(Segment.Link(it), Segment.Text(" ; ")) }
        assertEquals(listOf(InlineRunBlock.Text(source)), planInlineRun(source, true))
    }

    @Test fun disabledPreviewPreservesMixedRun() {
        val source = listOf(Segment.Link(rejected.first()), Segment.Text(" then "), Segment.Link("https://example.com"))
        assertEquals(listOf(InlineRunBlock.Text(source)), planInlineRun(source, false))
    }

    @Test fun acceptedHttpsUnicodeAndPortsKeepTheirOriginalPreviewDestinations() {
        for (url in listOf("https://example.com/path", "https://аpple.com/path",
            "https://example.com:443/path", "https://example.com:8443/path", "https://[2001:db8::1]:8443/")) {
            assertEquals(listOf(InlineRunBlock.Preview(url)), planInlineRun(listOf(Segment.Link(url)), true))
        }
    }
}
