package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.UserEntity
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

    @Test
    fun `search safety filters remove muted pubkey notes and profiles`() {
        val muteList = muteList(pubkeys = setOf("muted"))
        val mutedNote = row(id = "muted-note", pubkey = "muted")
        val visibleNote = row(id = "visible-note", pubkey = "visible")
        val mutedUser = UserEntity(pubkey = "muted", name = "Muted")
        val visibleUser = UserEntity(pubkey = "visible", name = "Visible")

        assertEquals(listOf(visibleNote), filterSearchNoteRows(listOf(mutedNote, visibleNote), muteList, hashtagCap = null))
        assertEquals(listOf(visibleUser), filterSearchPeople(listOf(mutedUser, visibleUser), muteList))
    }

    private fun muteList(
        pubkeys: Set<String> = emptySet(),
        hashtags: Set<String> = emptySet(),
        words: Set<String> = emptySet(),
        eventIds: Set<String> = emptySet(),
    ): MuteList = MuteList(
        pubkeys = pubkeys,
        hashtags = hashtags,
        words = words,
        eventIds = eventIds,
    )

    private fun row(
        id: String,
        pubkey: String,
        kind: Int = 1,
        content: String = "",
        tags: String = "[]",
    ): FeedRow = FeedRow(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0L,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )
}
