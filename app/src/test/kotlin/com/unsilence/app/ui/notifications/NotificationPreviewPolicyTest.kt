package com.unsilence.app.ui.notifications

import app.cash.turbine.test
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.RepostInfo
import com.unsilence.app.data.model.RepostPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPreviewPolicyTest {
    @Test
    fun `replies and mentions show the new message not the parent`() {
        assertEquals("incoming", single("incoming", "reply", "parent").previewTargetId())
        assertEquals("incoming", single("incoming", "mention", "parent").previewTargetId())
    }

    @Test
    fun `votes preview the poll and grouped activity previews its target`() {
        assertEquals("poll", single("vote", "poll_vote", "poll").previewTargetId())
        for (kind in listOf("reaction", "repost", "zap")) {
            assertEquals("post", group(kind, "post").previewTargetId())
        }
    }

    @Test
    fun `profile zaps and missing poll targets do not invent content cards`() {
        assertNull(group("zap", null).previewTargetId())
        assertNull(single("vote", "poll_vote", null).previewTargetId())
        assertNull(group("zap", " ").previewTargetId())
    }

    @Test
    fun `warm window is bounded and repeated targets are resolved once`() {
        val rows = (0..99).map { single("post-$it") }
        assertEquals(emptyList<String>(), notificationPreviewTargets(rows, -1, -1))
        assertEquals(listOf("post-9", "post-10", "post-11", "post-12"), notificationPreviewTargets(rows, 10, 11))
        assertEquals(NOTIFICATION_PREVIEW_WINDOW, notificationPreviewTargets(rows, 0, 99).size)
        val repeated = listOf(group("reaction", "post"), group("zap", "post"), single("reply"))
        assertEquals(listOf("post", "reply"), notificationPreviewTargets(repeated, 0, 2))
    }

    @Test
    fun `late target arrival updates the preview without another notification`() = runTest {
        val events = mutableMapOf<String, NostrEvent>()
        val signals = MutableStateFlow(0L)
        val source = event("post", "# Heading\n- a list item\n#nostr")
        val model = model(source)
        notificationPreviews(
            MutableStateFlow(listOf("post", "post")), signals, MutableStateFlow(null),
            events::get, { if (it in events) model else null },
        ).test {
            assertEquals(mapOf("post" to NotificationPreview.Unavailable), awaitItem())
            events[source.id] = source
            signals.value++
            val ready = awaitItem()["post"] as NotificationPreview.Ready
            assertSame(model, ready.model) // shared parser/model, no notification string renderer
            assertEquals(source.id, ready.event.id)
            signals.value++
            expectNoEvents() // unrelated store activity does not change this preview
            events.clear()
            signals.value++
            assertEquals(NotificationPreview.Unavailable, awaitItem()["post"])
        }
    }

    @Test
    fun `target content mutes apply live without dropping the activity group`() = runTest {
        val source = event("post", "a blocked phrase")
        val muteLists = MutableStateFlow<MuteList?>(null)
        notificationPreviews(
            MutableStateFlow(listOf("post")), MutableStateFlow(0L), muteLists,
            { source }, { model(source) },
        ).test {
            assertTrue(awaitItem()["post"] is NotificationPreview.Ready)
            muteLists.value = mutes(words = setOf("blocked phrase"))
            assertEquals(mapOf("post" to NotificationPreview.Muted), awaitItem())
            muteLists.value = null
            assertTrue(awaitItem()["post"] is NotificationPreview.Ready)
        }
    }

    @Test
    fun `muted previews never parse the hidden content`() = runTest {
        val source = event("post")
        notificationPreviews(
            MutableStateFlow(listOf("post")), MutableStateFlow(0L), MutableStateFlow(mutes(pubkeys = setOf(source.pubkey))),
            { source }, { error("must not parse muted content") },
        ).test {
            assertEquals(NotificationPreview.Muted, awaitItem()["post"])
        }
    }

    @Test
    fun `target warning and reason reach the existing embedded gate unchanged`() = runTest {
        val source = event("post").copy(hasContentWarning = true, contentWarningReason = "target warning")
        notificationPreviews(
            MutableStateFlow(listOf("post")), MutableStateFlow(0L), MutableStateFlow(null),
            { source }, { model(source) },
        ).test {
            val ready = awaitItem()["post"] as NotificationPreview.Ready
            assertTrue(ready.event.hasContentWarning)
            assertTrue(ready.model.warnings.hasContentWarning)
            assertEquals("target warning", ready.model.warnings.reason)
        }
    }

    @Test
    fun `reference-only repost waits for the verified target instead of showing JSON`() = runTest {
        val wrapper = event("repost", "{untrusted envelope}").copy(kind = 6, repostInfo = reference("target"))
        val target = event("target", "verified message")
        val events = mutableMapOf(wrapper.id to wrapper)
        val signals = MutableStateFlow(0L)
        notificationPreviews(
            MutableStateFlow(listOf(wrapper.id)), signals, MutableStateFlow(null), events::get,
            { id -> events[id]?.let(::model) },
        ).test {
            assertEquals(NotificationPreview.Unavailable, awaitItem()[wrapper.id])
            events[target.id] = target
            signals.value++
            val ready = awaitItem()[wrapper.id] as NotificationPreview.Ready
            assertEquals(target.id, ready.model.navigateId)
            assertEquals("verified message", ready.model.displayContent)
        }
    }

    @Test
    fun `leaving the warm window releases projected models`() = runTest {
        val targets = MutableStateFlow(listOf("post"))
        val source = event("post")
        notificationPreviews(targets, MutableStateFlow(0L), MutableStateFlow(null), { source }, { model(source) }).test {
            assertEquals(setOf("post"), awaitItem().keys)
            targets.value = emptyList()
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `cached targets do not cause relay lookups`() = runTest {
        val finished = mutableListOf<String>()
        val requested = mutableListOf<String>()
        resolveNotificationPreviews(
            listOf("post", "post"), { event(it) }, { null },
            { requested.add(it.lookupKey); null }, finished::add,
        )
        assertTrue(requested.isEmpty())
        assertEquals(listOf("post"), finished)
    }

    @Test
    fun `muted reference-only repost does not fetch its target`() = runTest {
        val wrapper = event("repost").copy(kind = 16, repostInfo = reference("target"))
        val requested = mutableListOf<String>()
        resolveNotificationPreviews(
            listOf(wrapper.id), { id -> wrapper.takeIf { it.id == id } },
            { mutes(pubkeys = setOf(wrapper.pubkey)) },
            { requested.add(it.lookupKey); null }, {},
        )
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `missing target requests are bounded deduplicated and all admitted targets finish`() = runTest {
        val release = CompletableDeferred<Unit>()
        val requested = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val job = launch {
            resolveNotificationPreviews(
                (0..99).flatMap { listOf("post-$it", "post-$it") }, { null }, { null },
                { requested.add(it.eventId!!); release.await(); null }, finished::add,
            )
        }
        runCurrent()
        assertEquals(NOTIFICATION_PREVIEW_CONCURRENCY, requested.size)
        release.complete(Unit)
        job.join()
        assertEquals(NOTIFICATION_PREVIEW_WINDOW, requested.size)
        assertEquals(requested.toSet(), finished.toSet())
    }

    @Test
    fun `failed or timed out lookup leaves a finished unavailable preview`() = runTest {
        val finished = mutableListOf<String>()
        launch {
            resolveNotificationPreviews(
                listOf("failure", "timeout"), { null }, { null },
                { if (it.eventId == "failure") error("offline") else awaitCancellation() }, finished::add,
            )
        }
        advanceUntilIdle()
        assertEquals(setOf("failure", "timeout"), finished.toSet())
        assertEquals(NOTIFICATION_PREVIEW_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun `leaving the screen cancels resolution including queued work`() = runTest {
        val requested = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val ids = (0 until NOTIFICATION_PREVIEW_WINDOW).map(Int::toString)
        val job = launch {
            resolveNotificationPreviews(ids, { null }, { null }, { requested.add(it.eventId!!); awaitCancellation() }, finished::add)
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(NOTIFICATION_PREVIEW_CONCURRENCY, requested.size)
        assertEquals(ids.toSet(), finished.toSet())
    }

    private fun single(id: String, type: String = "reply", target: String? = id) = NotificationRow.Single(
        id, type, "actor", null, null, null, target, 100,
    )

    private fun group(type: String, target: String?) = NotificationRow.Grouped(
        notifType = type, targetNoteId = target,
        actors = emptyList(), people = 1, sumSats = 21, dominantReaction = null,
        anonymousCount = 1, anonymousSats = 21, anonymousMostRecentAt = 100, mostRecentAt = 100,
    )

    private fun event(id: String, content: String = "body") = NostrEvent(
        id = id, pubkey = "a".repeat(64), kind = 1, content = content, createdAt = 100,
        tags = emptyList(), sig = "sig", relayUrl = "wss://relay.example", replyToId = null,
        rootId = null, hasContentWarning = false, contentWarningReason = null, firstSeenAt = 100,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )

    private fun model(event: NostrEvent): EventModel = ContentParser.parse(
        id = event.id, pubkey = event.pubkey, kind = event.kind, content = event.content,
        tags = event.tags, createdAt = event.createdAt,
        relayUrl = event.relayUrl, replyToId = event.replyToId, rootId = event.rootId,
        hasContentWarning = event.hasContentWarning, contentWarningReason = event.contentWarningReason,
        preparsedRepost = event.repostInfo,
    )

    private fun reference(id: String) = RepostInfo(
        targetId = id, relayHint = null, addressCoordinate = null, addressRelayHint = null,
        targetAuthorHint = null, proxyUrl = null, payload = RepostPayload.ReferenceOnly,
    )

    private fun mutes(pubkeys: Set<String> = emptySet(), words: Set<String> = emptySet()) =
        MuteList(LinkedHashSet(pubkeys), linkedSetOf(), LinkedHashSet(words), linkedSetOf())
}
