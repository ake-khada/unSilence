package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exact actor/contributor ownership, with raw payload reclamation through the production sweep. */
@RunWith(Parameterized::class)
class EvictedReactionDeletionTest(private val tag: String) {
    private val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private val target = event("target").copy(kind = if (tag == "e") 1 else 30023,
        tags = if (tag == "e") emptyList() else listOf(listOf("d", "fixture")))
    private val key = if (tag == "e") target.id else "30023:${target.pubkey}:fixture"
    private val reaction = reaction("first")
    private fun seed(vararg more: NostrEvent) { store.insertBatch(listOf(target, reaction) + more) }
    private fun evict() {
        store.evictOldContentEventsForTest(mapOf(7 to 0))
        assertNull(store.getNostrEvent(reaction.id))
    }
    private fun delete(event: NostrEvent, actor: String = event.pubkey) {
        store.insert(event("delete-${event.id}-$actor").copy(kind = 5, pubkey = actor,
            tags = listOf(listOf("e", event.id))))
    }
    private fun contents() = store.reactionsForEvent(target.id).map { it.content }.toSet()

    @Test fun `deleting final evicted contribution removes count actor state and lookup ids`() {
        seed()
        evict()
        store.ownPubkey = reaction.pubkey // Own state is queried only after ordinary payload eviction.
        assertEquals(1, store.reactionCount(target.id))
        assertTrue(store.isOwnEngaged(target.id))
        delete(reaction)
        assertEquals(0, store.reactionCount(target.id))
        assertFalse(store.isOwnEngaged(target.id))
        assertTrue(store.reactionEventIdsForTarget(reaction.pubkey, key).isEmpty())
        assertFalse(store.insert(reaction))
        delete(reaction)
        assertEquals(0, store.reactionCount(target.id))
    }

    @Test fun `evicted same-content siblings retain one summary until the last exact deletion`() {
        val sibling = reaction("sibling")
        seed(sibling)
        evict()
        delete(reaction)
        assertEquals(1, store.reactionCount(target.id))
        assertEquals(setOf(sibling.id), store.reactionEventIdsForTarget(reaction.pubkey, key))
        assertFalse(store.insert(reaction))
        delete(sibling)
        assertEquals(0, store.reactionCount(target.id))
    }

    @Test fun `retained same-content sibling survives deleting another retained contribution`() {
        val sibling = reaction("sibling")
        seed(sibling)
        delete(reaction)
        assertNotNull(store.getNostrEvent(sibling.id))
        assertEquals(setOf(ReactionContent.Standard("+")), contents())
        delete(sibling)
        assertEquals(0, store.reactionCount(target.id))
    }

    @Test fun `wrong author or unrelated id cannot erase an evicted reaction`() {
        seed()
        evict()
        delete(reaction, "forger")
        delete(reaction("not-this-event"))
        assertEquals(setOf(ReactionContent.Standard("+")), contents())
        assertTrue(store.insert(reaction))
        assertEquals(1, store.reactionCount(target.id))
    }

    @Test fun `known remaining emoji removes only the deleted evicted contribution`() {
        val sibling = reaction("sibling").copy(content = "heart")
        seed(sibling)
        store.retainRequestedEvent(sibling.id).use {
            // The reference lease raises the tier; it is not an exemption from a zero cap.
            store.evictOldContentEventsForTest(mapOf(7 to 1))
            assertNull(store.getNostrEvent(reaction.id))
            assertNotNull(store.getNostrEvent(sibling.id))
            delete(reaction)
            assertEquals(setOf(ReactionContent.Standard("heart")), contents())
            assertEquals(setOf(sibling.id), store.reactionEventIdsForTarget(reaction.pubkey, key))
        }
    }

    @Test fun `all retained different emoji are independently revocable`() {
        val sibling = reaction("sibling").copy(content = "heart")
        seed(sibling)
        delete(reaction)
        assertEquals(setOf(ReactionContent.Standard("heart")), contents())
        assertTrue(store.insert(reaction.copy(id = "replacement")))
        assertEquals(2, store.reactionCount(target.id))
        delete(sibling)
        assertEquals(setOf(ReactionContent.Standard("+")), contents())
    }

    @Test fun `exact deletion preserves another actor and another target`() {
        val other = reaction("other-actor").copy(pubkey = "other")
        val otherTarget = event("other-target")
        val elsewhere = reaction("elsewhere").copy(tags = listOf(listOf("e", otherTarget.id)))
        seed(other, otherTarget, elsewhere)
        evict()
        delete(reaction)
        assertEquals(setOf("other"), store.reactionsForEvent(target.id).map { it.pubkey }.toSet())
        assertEquals(1, store.reactionCount(otherTarget.id))
        assertTrue(store.insert(elsewhere))
        assertEquals(1, store.reactionCount(otherTarget.id))
    }

    @Test fun `account clear drops retained summaries indexes and tombstones`() {
        seed()
        evict()
        delete(reaction)
        store.clear()
        assertEquals(0, store.reactionCount(target.id))
        assertTrue(store.reactionEventIdsForTarget(reaction.pubkey, key).isEmpty())
        seed()
        assertEquals(1, store.reactionCount(target.id))
    }

    private fun reaction(id: String) = event(id).copy(pubkey = "reactor", kind = 7,
        content = "+", tags = listOf(listOf(tag, key)))
    private fun event(id: String) = NostrEvent(id, "author", 1, "fixture", 1L,
        emptyList(), "", "wss://relay.example", null, null, false, null, 1L, mutableSetOf("wss://relay.example"))
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "target={0}")
        fun cases() = listOf(arrayOf("e"), arrayOf("a"), arrayOf("A"))
    }
}
