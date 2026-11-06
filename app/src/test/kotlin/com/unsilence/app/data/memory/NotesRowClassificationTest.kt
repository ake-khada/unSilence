package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.ui.feed.FeedContentFilter
import com.unsilence.app.ui.profile.matchesProfileContentFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Characterizes selection, including intentional differences between feed and profile. */
@RunWith(Parameterized::class)
class NotesRowClassificationTest(
    private val kind: Int,
    private val parents: String,
    private val notesRoot: Boolean,
    private val profileNote: Boolean,
    private val userFeedNote: Boolean,
    private val userFeedReply: Boolean,
) {
    @Test
    fun `shared selection and profile exception preserve their truth tables`() {
        val event = event()
        assertEquals(notesRoot, event.isNotesRowRoot())
        assertEquals(profileNote, matchesProfileContentFilter(event, FeedContentFilter.NOTES_ONLY))
        assertEquals(!profileNote, matchesProfileContentFilter(event, FeedContentFilter.REPLIES_ONLY))
    }

    @Test
    fun `MES feed selection preserves roots replies and unfiltered rows`() {
        val store = store()
        val event = event()
        store.insert(event)
        for ((filter, included) in listOf(0 to true, 1 to notesRoot, 2 to !notesRoot)) {
            assertEquals(
                "contentFilter=$filter",
                if (included) listOf(event.id) else emptyList<String>(),
                store.feedEvents(FeedQuery(kinds = setOf(kind), contentFilter = filter)).map { it.id },
            )
        }
    }

    @Test
    fun `displayable floor counts roots but retains intervening replies`() {
        val store = store()
        val candidate = event()
        val olderRoot = candidate.copy(id = "older-root", kind = 1, createdAt = 1L,
            tags = emptyList(), replyToId = null, rootId = null)
        store.insert(olderRoot)
        store.insert(candidate)
        val result = store.recentEventsWithDisplayableFloor(
            kinds = setOf(kind, 1), isDisplayable = { true }, floor = 1,
        )
        assertEquals(
            if (notesRoot) listOf(candidate.id) else listOf(candidate.id, olderRoot.id),
            result.map { it.id },
        )
        // A hidden root must not satisfy the floor; replies still remain in the returned slice.
        assertEquals(
            listOf(candidate.id, olderRoot.id),
            store.recentEventsWithDisplayableFloor(
                kinds = setOf(kind, 1), isDisplayable = { it.id == olderRoot.id }, floor = 1,
            ).map { it.id },
        )
    }

    @Test
    fun `user feed keeps its distinct kind whitelist unchanged`() = runTest {
        val store = store()
        val event = event()
        store.insert(event)
        for ((filter, included) in listOf(0 to true, 1 to userFeedNote, 2 to userFeedReply)) {
            assertEquals(
                "user contentFilter=$filter",
                if (included) listOf(event.id) else emptyList<String>(),
                store.userFeedFlow(event.pubkey, contentFilter = filter, kinds = setOf(kind))
                    .first().map { it.id },
            )
        }
    }

    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())

    private fun event() = NostrEvent(
        id = "candidate-$kind-$parents", pubkey = "author", kind = kind, content = "content",
        createdAt = 2L,
        // Coordinate-only NIP-22 comments intentionally differ between feed and profile Notes.
        tags = if (kind == 1111) listOf(listOf("A", "34236:author:clip")) else emptyList(),
        sig = "sig", relayUrl = "wss://relay.example",
        replyToId = if (parents == "reply" || parents == "both") "parent" else null,
        rootId = if (parents == "root" || parents == "both") "root" else null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 2L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "kind={0}, parents={1}")
        fun cases(): List<Array<Any>> {
            val roots = listOf(true, false, false, false)
            val all = listOf(true, true, true, true)
            val none = listOf(false, false, false, false)
            val replies = listOf(false, true, true, true)
            // Columns: shared Notes root, profile Notes, user-feed Notes, user-feed Replies.
            val expectations = mapOf(
                1 to listOf(roots, roots, roots, replies),
                6 to listOf(all, all, all, none),
                16 to listOf(all, all, all, none),
                1111 to listOf(roots, none, none, all),
                1068 to listOf(roots, roots, all, none),
                30023 to listOf(roots, roots, none, none),
                20 to listOf(roots, roots, none, none),
            )
            return expectations.flatMap { (kind, columns) ->
                listOf("none", "reply", "root", "both").mapIndexed { index, parents ->
                    arrayOf(kind, parents, columns[0][index], columns[1][index],
                        columns[2][index], columns[3][index])
                }
            }
        }
    }
}
