package com.unsilence.app.ui.feed

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import com.unsilence.app.data.model.markdown.MdInline
import com.unsilence.app.ui.markdown.appendMdInlines
import org.junit.Assert.*
import org.junit.Test

class ContentLinkPolicyTest {
    @Test fun `only the six permitted schemes remain active with exact destinations`() {
        for (url in listOf("http://example.com", "HTTPS://example.com", "nostr:npub1example",
            "mailto:person@example.com", "lightning:lnurl1example", "bitcoin:bc1qexample")) {
            val span = buildAnnotatedString { appendLinkSpan(url) }
            val link = span.getLinkAnnotations(0, span.length).single().item as LinkAnnotation.Url
            assertEquals(url, link.url)
        }
    }

    @Test fun `privileged schemes relative opaque and empty destinations remain inert`() {
        for (url in listOf("nostrsigner:%7B%7D?type=sign_event", "nostr+walletauth://grant",
            "nostr+walletconnect://secret", "intent://scan", "javascript:alert(1)", "tel:123",
            "market://details", "file:///file", "content://provider/item", "data:text/plain,hello",
            "//example.com", "/relative", "relative/path", "", ":empty", "https:opaque", "https:", "nostr:")) {
            assertNull(url, contentLinkLabel(url))
            val span = buildAnnotatedString { appendLinkSpan(url, "Read") }
            assertEquals("Read", span.text)
            assertTrue(url, span.getLinkAnnotations(0, span.length).isEmpty())
            assertTrue(url, span.spanStyles.isEmpty())
        }
    }

    @Test fun `forgiving parser repairs cannot activate ambiguous HTTP authorities`() {
        for (url in listOf("https://", "https:///example.com", "https:////example.com", "https://?x",
            "https://#x", "https://user@", "https://:443/", "https://example.com:", "https://example.com:abc",
            "https://example.com:65536", "https://example.com:-1", "https://[::1", "https://[::1]tail",
            "https://[bad::ip]/", "https://::1/", "https://example..com", "https://-bad.example",
            "https://bad_host.example", "https://%65xample.com", "https://example.com%2f.evil.example",
            "https://example.com\n.evil", " https://example.com", "https://example.com ", "https://127.1",
            "https://2130706433", "https://0x7f000001", "https://0177.0.0.1")) {
            assertNull(url, contentLinkLabel(url))
            val span = buildAnnotatedString { appendLinkSpan(url) }
            assertTrue(url, span.getLinkAnnotations(0, span.length).isEmpty())
        }
    }

    @Test fun `different scheme host and effective port are disclosed`() {
        for ((label, url, origin) in listOf(
            Triple("https://bank.example", "https://bank.example.evil.example/path", "https://bank.example.evil.example"),
            Triple("https://bank.example", "http://bank.example", "http://bank.example"),
            Triple("bank.example:8443", "https://bank.example:9443/x", "https://bank.example:9443"),
            Triple("http://bank.example:80", "https://bank.example:443", "https://bank.example"),
            Triple("bank.example", "http://bank.example:8080/path", "http://bank.example:8080"),
        )) assertEquals("$label ($origin)", contentLinkLabel(url, label))
    }

    @Test fun `normalized honest origins and descriptive labels remain unchanged`() {
        for ((label, url) in listOf(
            "Read the guide" to "https://example.com/guide", "v2.0" to "https://example.com",
            "e.g" to "https://example.com", "See release v2.0, e.g the guide" to "https://example.com",
            "https://Example.COM.:443/a" to "https://example.com/b",
            "example.com" to "https://www.example.com/path",
            "www.example.com" to "https://example.com/path",
            "http://example.com:80" to "http://example.com/",
            "Visit example.com now" to "https://example.com/",
            "https://user:password@example.com" to "https://example.com/",
        )) assertEquals(label, contentLinkLabel(url, label))
    }

    @Test fun `userinfo and backslash cannot disguise destination authority`() {
        for (url in listOf("https://bank.example@evil.example/path", "https://evil.example\\@bank.example/path")) {
            assertEquals("https://bank.example (https://evil.example)", contentLinkLabel(url, "https://bank.example"))
        }
        assertEquals("bank.example (https://evil.example:8443)",
            contentLinkLabel("https://user:pass@evil.example:8443/path", "bank.example"))
    }

    @Test fun `Unicode claims and destinations compare ASCII IDN hosts`() {
        assertEquals("https://apple.com (https://xn--pple-43d.com)", contentLinkLabel("https://аpple.com/login", "https://apple.com"))
        assertEquals("Visit аpple.com (https://apple.com)", contentLinkLabel("https://apple.com", "Visit аpple.com"))
        assertEquals("xn--pple-43d.com", contentLinkLabel("https://аpple.com/", "xn--pple-43d.com"))
        assertEquals("xn--r8jz45g.example", contentLinkLabel("https://例え.example"))
        assertTrue(contentLinkLabel("https://аpple.com/%zz")!!.startsWith("https://xn--pple-43d.com/"))
    }

    @Test fun `IPv4 and bracketed IPv6 claims include scheme and service ports`() {
        for ((label, url, expected) in listOf(
            Triple("127.0.0.1", "https://192.0.2.1", "127.0.0.1 (https://192.0.2.1)"),
            Triple("[2001:db8::1]:8443", "https://[2001:db8::1]:9443/", "[2001:db8::1]:8443 (https://[2001:db8::1]:9443)"),
            Triple("https://[2001:0db8:0:0:0:0:0:1]:443/", "https://[2001:db8::1]/", "https://[2001:0db8:0:0:0:0:0:1]:443/"),
            Triple("Use 192.0.2.1 now", "https://192.0.2.1/", "Use 192.0.2.1 now"),
            Triple("Visit [2001:db8::1] now", "http://[2001:db8::1]/", "Visit [2001:db8::1] now (http://[2001:db8::1])"),
        )) assertEquals(expected, contentLinkLabel(url, label))
    }

    @Test fun `embedded address claims disclose once and raw URLs never gain origin suffix`() {
        val label = "Visit wallet.example and bank.example now"
        assertEquals("$label (https://evil.example)", contentLinkLabel("https://evil.example/x", label))
        assertEquals("Go to (example.com).", contentLinkLabel("https://example.com", "Go to (example.com)."))
        assertEquals("evil.example/path", contentLinkLabel("https://evil.example/path"))
    }

    @Test fun `authored web claims disclose allowed non-web schemes without changing targets`() {
        for (url in listOf("lightning:lnurl1example", "bitcoin:bc1qexample", "mailto:person@example.com", "nostr:npub1example")) {
            for (label in listOf("https://wallet.example", "Visit wallet.example now")) {
                val shared = buildAnnotatedString { appendLinkSpan(url, label) }
                val article = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text(label)))), {}) }
                assertEquals("$label (${url.substringBefore(':')}:)", shared.text)
                assertEquals(shared, article)
                assertEquals(url, (shared.getLinkAnnotations(0, shared.length).single().item as LinkAnnotation.Url).url)
            }
            assertEquals(url, contentLinkLabel(url))
            assertEquals("Read the guide", contentLinkLabel(url, "Read the guide"))
        }
    }

    @Test fun `email labels are not web claims for non-web destinations in notes and articles`() {
        for (url in listOf("mailto:person@example.com", "lightning:lnurl1example", "bitcoin:bc1qexample", "nostr:npub1example")) {
            for (label in listOf("person@example.com", "Email support@bank.example now", "first.last+help@bank.example")) {
                val note = buildAnnotatedString { appendLinkSpan(url, label) }
                val article = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text(label)))), {}) }
                assertEquals(label, note.text)
                assertEquals(note, article)
                assertEquals(url, (note.getLinkAnnotations(0, note.length).single().item as LinkAnnotation.Url).url)
            }
            val label = "Email person@example.com or visit wallet.example"
            val note = buildAnnotatedString { appendLinkSpan(url, label) }
            val article = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text(label)))), {}) }
            assertEquals("$label (${url.substringBefore(':')}:)", note.text)
            assertEquals(note, article)
        }
    }

    @Test fun `email labels still claim their domain for web destinations in notes and articles`() {
        for (label in listOf("support@bank.example", "first.last+help@bank.example")) {
            for ((url, expected) in listOf(
                "https://evil.example" to "$label (https://evil.example)",
                "https://bank.example" to label,
            )) {
                val note = buildAnnotatedString { appendLinkSpan(url, label) }
                val article = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text(label)))), {}) }
                assertEquals(expected, note.text)
                assertEquals(note, article)
                assertEquals(url, (note.getLinkAnnotations(0, note.length).single().item as LinkAnnotation.Url).url)
            }
        }
    }

    @Test fun `note and native Markdown share every activation and disclosure decision`() {
        for ((url, label) in listOf(
            "https://evil.example:8443/a" to "Visit bank.example now",
            "https://аpple.com/path" to "https://аpple.com/path",
            "https:opaque" to "Read this", "nostrsigner:request" to "Verify",
            "mailto:person@example.com" to "Mail", "https://example.com" to "Read the guide",
        )) {
            val shared = buildAnnotatedString { appendLinkSpan(url, label) }
            val article = buildAnnotatedString { appendMdInlines(listOf(MdInline.Link(url, listOf(MdInline.Text(label)))), {}) }
            assertEquals(shared, article)
            shared.getLinkAnnotations(0, shared.length).forEach { assertEquals(url, (it.item as LinkAnnotation.Url).url) }
        }
    }
}
