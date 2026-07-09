package com.unsilence.app.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchViewModelTest {

    @Test
    fun `tag search query turns bare word into hashtag value`() {
        assertEquals("calle", SearchViewModel.extractTagSearchQuery("Calle"))
    }

    @Test
    fun `tag search query strips optional hash prefix`() {
        assertEquals("nostr", SearchViewModel.extractTagSearchQuery("#Nostr"))
    }

    @Test
    fun `tag search query rejects text phrases`() {
        assertNull(SearchViewModel.extractTagSearchQuery("called calle"))
    }

    @Test
    fun `explicit hashtag query requires hash prefix`() {
        assertEquals("nostr", SearchViewModel.extractExplicitHashtagQuery("#Nostr"))
        assertNull(SearchViewModel.extractExplicitHashtagQuery("Nostr"))
    }
}
