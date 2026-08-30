package com.unsilence.app.ui.notifications

import app.cash.turbine.test
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.NotificationActor
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.ReactionContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMuteProjectionTest {

    @Test
    fun `resolved singles use full policy while an unmuted actor remains`() {
        val actorMuted = single(id = "actor-muted", actor = "muted-actor")
        val wordMuted = single(id = "word-muted", actor = "other-writer")
        val visible = single(id = "visible", actor = "visible-actor")
        val events = mapOf(
            actorMuted.id to event(actorMuted.id, actorMuted.actorPubkey, "ordinary reply"),
            wordMuted.id to event(wordMuted.id, wordMuted.actorPubkey, "contains blocked phrase"),
            visible.id to event(visible.id, visible.actorPubkey, "ordinary reply"),
        )

        val result = projectMutedNotifications(
            rows = listOf(actorMuted, wordMuted, visible),
            muteList = muteList(pubkeys = setOf("muted-actor"), words = setOf("blocked phrase")),
            eventProvider = events::get,
        )

        assertEquals(listOf("visible"), result.map { it.key })
    }

    @Test
    fun `missing single events fall back to actor mute in both directions`() {
        val muted = single(id = "missing-muted", actor = "muted-actor")
        val visible = single(id = "missing-visible", actor = "visible-actor")

        val result = projectMutedNotifications(
            rows = listOf(muted, visible),
            muteList = muteList(pubkeys = setOf("muted-actor")),
            eventProvider = { null },
        )

        assertEquals(listOf("missing-visible"), result.map { it.key })
    }

    @Test
    fun `group strips one muted actor and recomputes every named aggregate`() {
        val heart = ReactionContent.Standard("❤️")
        val thumbsUp = ReactionContent.Standard("👍")
        val row = group(
            notifType = "reaction",
            actors = listOf(
                actor("muted", sats = 300, reaction = heart, createdAt = 400),
                actor("visible-a", sats = 700, reaction = thumbsUp, createdAt = 200),
                actor("visible-b", sats = 50, reaction = thumbsUp, createdAt = 100),
            ),
            dominantReaction = heart,
        )

        val projected = projectMutedNotifications(
            rows = listOf(row),
            muteList = muteList(pubkeys = setOf("muted")),
            eventProvider = { null },
        ).single() as NotificationRow.Grouped

        assertEquals(listOf("visible-a", "visible-b"), projected.actors.map { it.pubkey })
        assertEquals(2, projected.people)
        assertEquals(750L, projected.sumSats)
        assertEquals(thumbsUp, projected.dominantReaction)
    }

    @Test
    fun `all named zappers muted still preserves anonymous zap aggregate`() {
        val row = group(
            actors = listOf(
                actor("muted-a", sats = 100, createdAt = 500),
                actor("muted-b", sats = 200, createdAt = 400),
            ),
            anonymousCount = 3,
            anonymousSats = 900,
            anonymousMostRecentAt = 300,
        )

        val projected = projectMutedNotifications(
            rows = listOf(row),
            muteList = muteList(pubkeys = setOf("muted-a", "muted-b")),
            eventProvider = { null },
        ).single() as NotificationRow.Grouped

        assertTrue(projected.actors.isEmpty())
        assertEquals(3, projected.people)
        assertEquals(3, projected.anonymousCount)
        assertEquals(900L, projected.anonymousSats)
        assertEquals(900L, projected.sumSats)
    }

    @Test
    fun `fully muted named group with no anonymous contributors is dropped`() {
        val row = group(actors = listOf(actor("muted", sats = 100, createdAt = 200)))

        val result = projectMutedNotifications(
            rows = listOf(row),
            muteList = muteList(pubkeys = setOf("muted")),
            eventProvider = { null },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `anonymous recency survives after every named actor is stripped`() {
        val row = group(
            actors = listOf(actor("muted", sats = 100, createdAt = 900)),
            anonymousCount = 1,
            anonymousSats = 50,
            anonymousMostRecentAt = 123,
        )

        val projected = projectMutedNotifications(
            rows = listOf(row),
            muteList = muteList(pubkeys = setOf("muted")),
            eventProvider = { null },
        ).single() as NotificationRow.Grouped

        assertEquals(123L, projected.mostRecentAt)
    }

    @Test
    fun `mute list emissions reproject notification rows`() = runTest {
        val row = single(id = "missing", actor = "actor")
        val rows = MutableStateFlow<List<NotificationRow>>(listOf(row))
        val muteLists = MutableStateFlow<MuteList?>(null)

        mutedNotificationsFlow(rows, muteLists, eventProvider = { null }).test {
            assertEquals(listOf("missing"), awaitItem().map { it.key })

            muteLists.value = muteList(pubkeys = setOf("actor"))

            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun single(id: String, actor: String) = NotificationRow.Single(
        id = id,
        notifType = "reply",
        actorPubkey = actor,
        actorName = null,
        actorDisplayName = null,
        actorPicture = null,
        targetNoteId = id,
        targetNoteContent = "reply",
        parentNoteContent = "parent",
        createdAt = 100,
    )

    private fun group(
        notifType: String = "zap",
        actors: List<NotificationActor>,
        anonymousCount: Int = 0,
        anonymousSats: Long = 0,
        anonymousMostRecentAt: Long = 0,
        dominantReaction: ReactionContent? = null,
    ) = NotificationRow.Grouped(
        notifType = notifType,
        targetNoteId = "target",
        targetNoteContent = "target note",
        actors = actors,
        people = actors.size + anonymousCount,
        sumSats = actors.sumOf { it.sats } + anonymousSats,
        dominantReaction = dominantReaction,
        anonymousCount = anonymousCount,
        anonymousSats = anonymousSats,
        anonymousMostRecentAt = anonymousMostRecentAt,
        mostRecentAt = maxOf(
            actors.maxOfOrNull { it.createdAt } ?: 0L,
            anonymousMostRecentAt,
        ),
    )

    private fun actor(
        pubkey: String,
        sats: Long = 0,
        reaction: ReactionContent? = null,
        createdAt: Long,
    ) = NotificationActor(
        pubkey = pubkey,
        name = null,
        displayName = null,
        picture = null,
        sats = sats,
        reaction = reaction,
        createdAt = createdAt,
    )

    private fun muteList(
        pubkeys: Set<String> = emptySet(),
        words: Set<String> = emptySet(),
    ) = MuteList(
        pubkeys = LinkedHashSet(pubkeys),
        hashtags = linkedSetOf(),
        words = LinkedHashSet(words),
        eventIds = linkedSetOf(),
    )

    private fun event(id: String, pubkey: String, content: String) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = 1,
        content = content,
        createdAt = 100,
        tags = emptyList(),
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = "parent",
        rootId = "parent",
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 100,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
