package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.model.PollInfo
import com.unsilence.app.data.model.PollOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollProtocolTest {
    private val poll = PollInfo(
        options = listOf(PollOption("a", "Alpha"), PollOption("b", "Beta")),
        multipleChoice = false,
        endsAt = 200,
        responseRelays = emptyList(),
    )

    private fun response(
        id: String,
        pubkey: String,
        createdAt: Long,
        vararg choices: String,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = 1018,
        content = "",
        createdAt = createdAt,
        tags = listOf(listOf("e", "poll")) + choices.map { listOf("response", it) },

        sig = "sig",
        relayUrl = "wss://relay.test",
        replyToId = null,
        rootId = "poll",
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 0,
        relaysSeen = mutableSetOf("wss://relay.test"),
    )

    @Test
    fun `single choice response emits only one valid option`() {
        val tags = buildPollResponseTags(
            pollId = "poll-id",
            selectedOptionIds = setOf("b", "a", "injected"),
            validOptionIds = setOf("a", "b"),
            multipleChoice = false,
        )

        assertEquals(2, tags.size)
        assertArrayEquals(arrayOf("e", "poll-id"), tags[0])
        assertArrayEquals(arrayOf("response", "a"), tags[1])
    }

    @Test
    fun `multiple choice response emits each valid option once`() {
        val tags = buildPollResponseTags(
            pollId = "poll-id",
            selectedOptionIds = setOf("c", "a"),
            validOptionIds = setOf("a", "b", "c"),
            multipleChoice = true,
        )

        assertEquals(listOf("a", "c"), tags.drop(1).map { it[1] })
    }

    @Test
    fun `tally uses latest response per pubkey with deterministic tie break`() {
        val tally = tallyPollVotes(
            responses = listOf(
                response("old", "alice", 110, "a"),
                response("tie-a", "alice", 120, "a"),
                response("tie-b", "alice", 120, "b"),
                response("bob", "bob", 115, "a"),
            ),
            poll = poll,
            pollCreatedAt = 100,
            ownPubkey = "alice",
        )

        assertEquals(mapOf("a" to 1, "b" to 1), tally.counts)
        assertEquals(setOf("b"), tally.ownChoices)
        assertEquals("tie-b", tally.ownResponseId)
        assertEquals(2, tally.totalVotes)
    }

    @Test
    fun `tally rejects responses outside poll time bounds`() {
        val tally = tallyPollVotes(
            responses = listOf(
                response("early", "alice", 99, "a"),
                response("valid", "bob", 150, "b"),
                response("late", "carol", 201, "a"),
            ),
            poll = poll,
            pollCreatedAt = 100,
            ownPubkey = null,
        )

        assertEquals(1, tally.totalVotes)
        assertEquals(mapOf("a" to 0, "b" to 1), tally.counts)
    }

    @Test
    fun `tally drops invalid options and invalid-only voters`() {
        val tally = tallyPollVotes(
            responses = listOf(
                response("invalid", "alice", 120, "injected"),
                response("mixed", "bob", 120, "injected", "a"),
            ),
            poll = poll.copy(multipleChoice = true),
            pollCreatedAt = 100,
            ownPubkey = "alice",
        )

        assertEquals(1, tally.totalVotes)
        assertEquals(mapOf("a" to 1, "b" to 0), tally.counts)
        assertTrue(tally.ownChoices.isEmpty())
    }

    @Test
    fun `single choice tally counts only first valid response tag`() {
        val tally = tallyPollVotes(
            responses = listOf(response("vote", "alice", 120, "b", "a")),
            poll = poll,
            pollCreatedAt = 100,
            ownPubkey = "alice",
        )

        assertEquals(mapOf("a" to 0, "b" to 1), tally.counts)
        assertEquals(setOf("b"), tally.ownChoices)
    }

    @Test
    fun `voter groups reuse latest-wins tally responses`() {
        val tally = tallyPollVotes(
            responses = listOf(
                response("alice-old", "alice", 110, "a"),
                response("alice-new", "alice", 130, "b"),
                response("bob", "bob", 120, "a"),
            ),
            poll = poll,
            pollCreatedAt = 100,
            ownPubkey = null,
        )

        val groups = pollVoterGroups(poll, tally)

        assertEquals(listOf("bob"), groups[0].voters.map { it.pubkey })
        assertEquals(listOf("alice"), groups[1].voters.map { it.pubkey })
        assertEquals(50, groups[0].percentage)
        assertEquals(50, groups[1].percentage)
    }

    @Test
    fun `multiple-choice voter appears under every selected option`() {
        val multi = poll.copy(multipleChoice = true)
        val tally = tallyPollVotes(
            responses = listOf(response("both", "alice", 120, "a", "b")),
            poll = multi,
            pollCreatedAt = 100,
            ownPubkey = "alice",
        )

        val groups = pollVoterGroups(multi, tally)

        assertTrue(groups.all { it.voters.single().pubkey == "alice" })
        assertTrue(groups.all { it.percentage == 100 })
    }
}
