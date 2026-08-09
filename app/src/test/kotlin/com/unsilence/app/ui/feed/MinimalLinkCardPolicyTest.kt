package com.unsilence.app.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class MinimalLinkCardPolicyTest {

    @Test
    fun `favicon ladder stays on the linked host and never constructs a proxy URL`() {
        val host = "bare-links.example"
        val candidates = listOf(
            minimalLinkIconUrl(host, MinimalLinkIconStage.APPLE_TOUCH_ICON),
            minimalLinkIconUrl(host, MinimalLinkIconStage.FAVICON),
        ).filterNotNull()

        assertEquals(
            listOf(
                "https://bare-links.example/apple-touch-icon.png",
                "https://bare-links.example/favicon.ico",
            ),
            candidates,
        )
        assertTrue(candidates.all { URI(it).host == host })
        assertFalse(candidates.any { URI(it).host == "www.google.com" })
    }

    @Test
    fun `two direct failures reach a stable generic icon without looping`() {
        var stage = MinimalLinkIconStage.APPLE_TOUCH_ICON
        assertTrue(minimalLinkIconUrl("no-icons.example", stage) != null)

        stage = stage.next()
        assertTrue(minimalLinkIconUrl("no-icons.example", stage) != null)

        stage = stage.next()
        assertEquals(MinimalLinkIconStage.GENERIC, stage)
        assertNull(minimalLinkIconUrl("no-icons.example", stage))
        assertEquals(MinimalLinkIconStage.GENERIC, stage.next())
    }

    @Test
    fun `disabled or unusable favicon source renders the generic icon immediately`() {
        assertNull(
            minimalLinkIconUrl(
                host = "bare-links.example",
                stage = MinimalLinkIconStage.APPLE_TOUCH_ICON,
                loadFavicon = false,
            ),
        )
        assertNull(
            minimalLinkIconUrl(
                host = "",
                stage = MinimalLinkIconStage.APPLE_TOUCH_ICON,
            ),
        )
    }
}
