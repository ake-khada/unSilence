package com.unsilence.app.ui.feed

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.exceedsHashtagCap
import com.unsilence.app.data.memory.isMuted
import com.unsilence.app.data.model.RepostInfo
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.VerifiedRepostEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FeedModerationCacheTest {
    private val noTarget: (String) -> NostrEvent? = { null }
    private val emptyMutes = MuteList(emptySet(), emptySet(), emptySet(), emptySet())

    @Test fun `larger verdict capacity does not widen reference exclusion bound`() {
        val policy = FeedModerationCache(100, maxReferenceExclusions = 2)
            .forPolicy(emptyMutes.copy(words = setOf("spam")), null)
        val wrappers = List(3) { repost(6, "target-$it").copy(id = "wrapper-$it") }
        wrappers.forEach { assertTrue(policy.excludes(it, { id -> event(id, "spam") })) }
        assertFalse(policy.excludes(wrappers[0], noTarget))
        assertTrue(policy.excludes(wrappers[1], noTarget))
        assertTrue(policy.excludes(wrappers[2], noTarget))
    }

    @Test fun `expanded reading and pending windows reuse weak verdicts without scanning content again`() {
        val cache = FeedModerationCache(3500, maxReferenceExclusions = 2000)
        val tags = CountingTags()
        val rows = List(2500) { event("feed-$it", "Reading #topic", tags) }
        val pending = List(1000) { event("pending-$it", "Reading #topic", tags) }
        repeat(3) {
            val policy = cache.forPolicy(emptyMutes, 5)
            (rows + pending).forEach { assertFalse(policy.excludes(it, noTarget)) }
        }
        assertEquals(3500, tags.reads.get())
    }

    @Test
    fun `unchanged feed and pending passes reuse verdicts without rescanning tags`() {
        val cache = FeedModerationCache(2000)
        val mutes = emptyMutes.copy(words = setOf("blocked"))
        val tags = CountingTags()
        val feed = List(1000) { event("feed-$it", "Reading Σ İ #topic", tags) }
        val pending = List(1000) { event("pending-$it", "Reading #topic", tags) }
        val restartedCollectorMutes = mutes.copy(words = LinkedHashSet(mutes.words))
        repeat(20) {
            for (events in listOf(feed, pending)) {
                val verdicts = cache.forPolicy(if (events === pending) restartedCollectorMutes else mutes, 5)
                events.forEach { assertFalse(verdicts.excludes(it, noTarget)) }
            }
        }
        // Actual policy tag reads, not a mock evaluator or elapsed-time assertion:
        // 40,000 checks require only the initial 2,000 hashtag scans.
        assertEquals(2000, tags.reads.get())
    }

    @Test
    fun `policy changes invalidate while old concurrent passes keep their own semantics`() {
        val cache = FeedModerationCache()
        val note = event(content = "SPAM #one #two")
        val original = cache.forPolicy(null, null)
        assertFalse(original.excludes(note, noTarget))
        assertSame(original, cache.forPolicy(null, null))
        val publicMutes = emptyMutes.copy(words = setOf("spam"))
        val muted = cache.forPolicy(publicMutes, null)
        assertTrue(muted.excludes(note, noTarget))
        assertSame(muted, cache.forPolicy(publicMutes, null))
        assertSame(muted, cache.forPolicy(publicMutes.copy(), null))
        assertTrue(cache.forPolicy(emptyMutes.copy(privateWords = setOf("spam")), null).excludes(note, noTarget))
        assertTrue(cache.forPolicy(null, 1).excludes(note, noTarget))
        assertFalse(cache.forPolicy(null, 2).excludes(note, noTarget))
        assertFalse(cache.forPolicy(null, null).excludes(note, noTarget))
        assertTrue(muted.excludes(note, noTarget))
        assertFalse(original.excludes(note, noTarget))
    }

    @Test
    fun `same id replacement is rechecked but new relay provenance is not`() {
        val tags = CountingTags()
        val note = event(tags = tags)
        val policy = FeedModerationCache().forPolicy(emptyMutes.copy(words = setOf("spam")), 5)
        assertFalse(policy.excludes(note, noTarget))
        val reads = tags.reads.get()
        note.relaysSeen.add("wss://other.example")
        assertFalse(policy.excludes(note, noTarget))
        assertEquals(reads, tags.reads.get())
        assertTrue(policy.excludes(note.copy(content = "spam"), noTarget))
        assertFalse(policy.excludes(note, noTarget))
        assertTrue(tags.reads.get() > reads)
    }

    @Test
    fun `cache remains bounded and recently used verdict survives eviction`() {
        val tags = CountingTags()
        val notes = List(3) { event("$it", tags = tags) }
        val policy = FeedModerationCache(2).forPolicy(null, 5)
        policy.excludes(notes[0], noTarget)
        policy.excludes(notes[1], noTarget)
        policy.excludes(notes[0], noTarget)
        policy.excludes(notes[2], noTarget)
        assertEquals(3, tags.reads.get())
        policy.excludes(notes[0], noTarget)
        assertEquals(3, tags.reads.get())
        policy.excludes(notes[1], noTarget)
        assertEquals(4, tags.reads.get())
    }

    @Test
    fun `uncached pagination tail does not evict hot verdicts and still applies safety`() {
        val tags = CountingTags()
        val hot = List(2) { event("hot-$it", tags = tags) }
        val tail = List(10) { event("tail-$it", "#one #two") }
        val policy = FeedModerationCache(2).forPolicy(null, 1)
        repeat(10) {
            hot.forEach { assertFalse(policy.excludes(it, noTarget)) }
            tail.forEach { assertTrue(policy.excludes(it, noTarget, cacheResult = false)) }
        }
        assertEquals(2, tags.reads.get())
    }

    @Test
    fun `clearing or replacing the account cache cannot reuse old entries`() {
        val tags = CountingTags()
        val note = event(tags = tags)
        val cache = FeedModerationCache()
        cache.forPolicy(null, 5).excludes(note, noTarget)
        cache.clear()
        cache.forPolicy(null, 5).excludes(note, noTarget)
        FeedModerationCache().forPolicy(null, 5).excludes(note, noTarget)
        assertEquals(3, tags.reads.get())
    }

    @Test
    fun `collected weak event reference is a miss without relying on GC timing`() {
        val tags = CountingTags()
        val note = event(tags = tags)
        val policy = FeedModerationCache().forPolicy(null, 5)
        policy.excludes(note, noTarget)
        val entries = policy.javaClass.getDeclaredField("entries").apply { isAccessible = true }
            .get(policy) as Map<*, *>
        val entry = entries.values.single()!!
        val ref = entry.javaClass.getDeclaredField("event").apply { isAccessible = true }
            .get(entry) as WeakReference<*>
        assertSame(note, ref.get())
        ref.clear()
        policy.excludes(note, noTarget)
        assertEquals(2, tags.reads.get())
    }

    @Test
    fun `reference repost exclusion survives target eviction until policy changes`() {
        for (kind in listOf(6, 16)) {
            val targets = mutableMapOf<String, NostrEvent>()
            val wrapper = repost(kind, "target").copy(content = "untrusted SPAM #one #two")
            val cache = FeedModerationCache()
            val policy = cache.forPolicy(emptyMutes.copy(words = setOf("spam")), 1)
            val resolver: (String) -> NostrEvent? = targets::get
            assertFalse(policy.excludes(wrapper, resolver))
            targets["target"] = event("target", "SPAM")
            assertTrue(policy.excludes(wrapper, resolver))
            targets.clear()
            assertTrue(policy.excludes(wrapper, resolver))
            assertTrue(policy.excludes(wrapper.copy(), resolver))
            assertTrue(policy.excludes(wrapper, resolver, cacheResult = false))
            targets["target"] = event("target", "clean")
            assertTrue("Exclusion is one-way within this policy", policy.excludes(wrapper, resolver))
            assertFalse(cache.forPolicy(emptyMutes, 1).excludes(wrapper, resolver))
            targets["target"] = event("target", "#one #two")
            assertTrue(cache.forPolicy(emptyMutes, 1).excludes(wrapper, resolver))
            targets.clear()
            assertTrue(cache.forPolicy(emptyMutes, 1).excludes(wrapper, resolver))
            assertFalse("A new cap resets the exclusion", cache.forPolicy(emptyMutes, 2).excludes(wrapper, resolver))
            targets["target"] = event("target", "#one #two")
            assertFalse(cache.forPolicy(emptyMutes, 2).excludes(wrapper, resolver))
        }
    }

    @Test
    fun `nested and legacy reference exclusions remain sticky without trusting wrapper content`() {
        for (kind in listOf(6, 16)) {
            val targets = mutableMapOf<String, NostrEvent>()
            val wrapper = repost(kind, "target").copy(content = "untrusted SPAM")
            val policy = FeedModerationCache().forPolicy(emptyMutes.copy(words = setOf("spam")), null)
            val resolver: (String) -> NostrEvent? = targets::get
            assertFalse(policy.excludes(wrapper, resolver))
            targets["target"] = repost(kind, "nested").copy(id = "target")
            assertFalse(policy.excludes(wrapper, resolver))
            targets["nested"] = event("nested", "SPAM")
            assertTrue(policy.excludes(wrapper, resolver))
            targets.clear()
            assertTrue(policy.excludes(wrapper, resolver))
            val legacyWrapper = wrapper.copy(id = "legacy", repostInfo = null, rootId = "target")
            assertFalse(policy.excludes(legacyWrapper, resolver))
            targets["target"] = event("target", "SPAM")
            assertTrue(policy.excludes(legacyWrapper, resolver))
            targets.clear()
            assertTrue(policy.excludes(legacyWrapper, resolver))
            val cycleWrapper = wrapper.copy(id = "cycle")
            targets["target"] = cycleWrapper
            assertFalse("An unresolved cycle stays bounded", policy.excludes(cycleWrapper, resolver))
        }
    }

    @Test
    fun `reference exclusion memory is bounded and ordinary scans cannot flush it`() {
        val cache = FeedModerationCache(2)
        val policy = cache.forPolicy(emptyMutes.copy(words = setOf("spam")), null)
        val wrappers = List(3) { repost(6, "target-$it").copy(id = "wrapper-$it") }
        val muted: (String) -> NostrEvent? = { event(it, "spam") }
        assertTrue(policy.excludes(wrappers[0], muted))
        assertTrue(policy.excludes(wrappers[1], muted))
        repeat(20) { assertFalse(policy.excludes(event("ordinary-$it"), noTarget)) }
        assertTrue(policy.excludes(wrappers[0], noTarget))
        assertTrue(policy.excludes(wrappers[2], muted))
        assertFalse("The least-recent reference fact was evicted", policy.excludes(wrappers[1], noTarget))
        assertTrue(policy.excludes(wrappers[0], noTarget))
        assertTrue(policy.excludes(wrappers[2], noTarget))
        repeat(20) {
            assertTrue(policy.excludes(repost(16, "tail-$it").copy(id = "tail-wrapper-$it"), muted, cacheResult = false))
        }
        assertTrue("Uncached pagination does not flush hot facts", policy.excludes(wrappers[0], noTarget))
        cache.clear()
        assertFalse(cache.forPolicy(emptyMutes.copy(words = setOf("spam")), null).excludes(wrappers[0], noTarget))
    }

    @Test
    fun `concurrent missing-target pass cannot reverse another pass exclusion`() {
        val policy = FeedModerationCache().forPolicy(emptyMutes.copy(words = setOf("spam")), null)
        val wrapper = repost(6, "target")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stale = executor.submit<Boolean> {
                policy.excludes(wrapper, {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    null
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(policy.excludes(wrapper, { event(it, "spam") }))
            release.countDown()
            assertTrue(stale.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `verified embedded reposts are cached and keep target and wrapper moderation`() {
        for (kind in listOf(6, 16)) {
            val tags = CountingTags()
            val verified = VerifiedRepostEvent("target", "author", 1, "clean", 1L, tags)
            val wrapper = repost(kind, "target").let {
                it.copy(repostInfo = it.repostInfo!!.copy(payload = RepostPayload.VerifiedEmbedded(verified)))
            }
            val cache = FeedModerationCache()
            val policy = cache.forPolicy(null, 5)
            repeat(10) {
                assertFalse(policy.excludes(wrapper, targetResolver = { error("must not resolve verified payload") }))
            }
            assertEquals(1, tags.reads.get())
            for (mutes in listOf(
                emptyMutes.copy(pubkeys = setOf(wrapper.pubkey)),
                emptyMutes.copy(privateEventIds = setOf(wrapper.id)),
                emptyMutes.copy(privatePubkeys = setOf(verified.pubkey)),
                emptyMutes.copy(eventIds = setOf(verified.id)),
                emptyMutes.copy(privateHashtags = setOf("topic")),
                emptyMutes.copy(words = setOf("clean")),
            )) assertTrue(cache.forPolicy(mutes, 5).excludes(wrapper, noTarget))
        }
    }

    @Test
    fun `Unicode lowercase and hashtag verdicts match original policy on cold and warm reads`() {
        val cache = FeedModerationCache()
        val notes = listOf(
            event("dotted", "İ"), event("sigma", "ΟΣ"), event("sharp", "Straße"),
            event("tags", "#one #one #two #1", listOf(listOf("t", "ONE"), listOf("t", "one"))),
        )
        val mutes = listOf(null, emptyMutes, emptyMutes.copy(words = setOf("i\u0307")),
            emptyMutes.copy(privateWords = setOf("ος")), emptyMutes.copy(words = setOf("strasse")),
            emptyMutes.copy(privateHashtags = setOf("one")))
        for (mute in mutes) for (cap in listOf(null, 0, 1, 2, 5)) {
            val policy = cache.forPolicy(mute, cap)
            repeat(2) {
                notes.forEach { note ->
                    assertEquals(isMuted(note, mute, noTarget) || exceedsHashtagCap(note, cap, noTarget),
                        policy.excludes(note, noTarget))
                }
            }
        }
        assertTrue(cache.forPolicy(mutes[2], null).excludes(notes[0], noTarget))
        assertTrue(cache.forPolicy(mutes[3], null).excludes(notes[1], noTarget))
        assertFalse(cache.forPolicy(mutes[4], null).excludes(notes[2], noTarget))
    }

    @Test
    fun `in flight old policy cannot overwrite new policy verdict`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val tags = object : AbstractList<List<String>>() {
            override val size = 1
            override fun get(index: Int): List<String> {
                if (reads.getAndIncrement() == 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return listOf("t", "topic")
            }
        }
        val note = event(tags = tags)
        val cache = FeedModerationCache()
        val old = cache.forPolicy(null, 0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { old.excludes(note, noTarget) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val current = cache.forPolicy(null, 1)
            assertFalse(current.excludes(note, noTarget))
            release.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertSame(current, cache.forPolicy(null, 1))
            assertFalse(current.excludes(note, noTarget))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `parallel same id replacements never return another event verdict`() {
        val policy = FeedModerationCache().forPolicy(emptyMutes.copy(words = setOf("spam")), null)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(false, true).map { excluded ->
                executor.submit {
                    val note = event(content = if (excluded) "SPAM" else "clean")
                    repeat(2000) { assertEquals(excluded, policy.excludes(note, noTarget)) }
                }
            }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `deletion remains live after a successful moderation cache hit`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val note = event(content = "clean")
        store.insert(note)
        val policy = FeedModerationCache().forPolicy(null, 5)
        fun visible() = !store.isDeleted(note) && !policy.excludes(note, store::getNostrEvent)
        assertTrue(visible())
        assertTrue(visible())
        val signal = store.feedSignalFlow.value
        store.insert(event("delete").copy(kind = 5, tags = listOf(listOf("e", note.id))))
        assertNotEquals(signal, store.feedSignalFlow.value)
        assertFalse(visible())
        assertFalse(policy.excludes(note, store::getNostrEvent))
    }

    private class CountingTags : AbstractList<List<String>>() {
        val reads = AtomicInteger()
        override val size = 1
        override fun get(index: Int): List<String> {
            reads.incrementAndGet()
            return listOf("t", "topic")
        }
    }

    private fun event(
        id: String = "note",
        content: String = "clean",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(id, "author", 1, content, 1L, tags, "sig", "wss://relay.example",
        null, null, false, null, 1L, ConcurrentHashMap.newKeySet())

    private fun repost(kind: Int, targetId: String) = event("wrapper").copy(
        kind = kind,
        repostInfo = RepostInfo(targetId, null, null, null, null, null, RepostPayload.ReferenceOnly),
    )
}
