package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.ProfileMetadataRefreshResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ProfilePublishCoordinatorTest {
    @Test
    fun `absent own profile refuses without signing publishing or persistence`() = runTest {
        val harness = Harness(initial = null)

        val result = harness.coordinator().publish(OWN, fields(), fields())

        assertEquals(ProfilePublishResult.ProfileUnavailable, result)
        assertEquals(0, harness.signCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.persistCalls)
        assertEquals(null, harness.profile)
    }

    @Test
    fun `invalid retained profile refuses without destructive rebuild`() = runTest {
        val original = profileEvent("original", 10L, "not-json")
        val harness = Harness(original)

        val result = harness.coordinator().publish(OWN, fields(), fields())

        assertEquals(ProfilePublishResult.InvalidExistingProfile, result)
        assertEquals(0, harness.signCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.persistCalls)
        assertEquals(original, harness.profile)
    }

    @Test
    fun `profile replacement during signing aborts stale publish`() = runTest {
        val original = profileEvent("original", 10L, "{\"name\":\"old\"}")
        val newer = profileEvent("newer", 20L, "{\"name\":\"other client\"}")
        val harness = Harness(original).apply { afterSign = { profile = newer } }

        val result = harness.coordinator().publish(OWN, fields(name = "old"), fields(name = "mine"))

        assertEquals(ProfilePublishResult.ChangedWhileSigning, result)
        assertEquals(1, harness.signCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(0, harness.persistCalls)
        assertEquals(newer, harness.profile)
    }

    @Test
    fun `zero relay acknowledgements leave MES unchanged`() = runTest {
        val original = profileEvent("original", 30L, "{\"name\":\"old\"}")
        val harness = Harness(original).apply { relayAccepted = false }

        val result = harness.coordinator(nowSeconds = 30L)
            .publish(OWN, fields(name = "old"), fields(name = "new"))

        assertEquals(ProfilePublishResult.NoRelayAccepted, result)
        assertEquals(listOf(31L), harness.signedCreatedAts)
        assertEquals(1, harness.publishCalls)
        assertEquals(0, harness.persistCalls)
        assertEquals(original, harness.profile)
    }

    @Test
    fun `one relay acknowledgement commits the signed profile`() = runTest {
        val harness = Harness(profileEvent("original", 40L, "{\"name\":\"old\"}"))

        val result = harness.coordinator(nowSeconds = 40L)
            .publish(OWN, fields(name = "old"), fields(name = "new"))

        assertEquals(ProfilePublishResult.Success, result)
        assertEquals(1, harness.publishCalls)
        assertEquals(1, harness.persistCalls)
        assertEquals("signed-1", harness.profile?.id)
        assertEquals(41L, harness.profile?.createdAt)
    }

    @Test
    fun `relay acceptance precedes local persistence`() = runTest {
        val harness = Harness(profileEvent("original", 40L, "{}"))

        harness.coordinator().publish(OWN, fields(), fields(name = "new"))

        assertEquals(listOf("sign", "publish", "persist"), harness.callOrder)
    }

    @Test
    fun `accepted event superseded before persistence does not report success`() = runTest {
        val harness = Harness(profileEvent("original", 40L, "{}"))
            .apply { persistRetained = false }

        val result = harness.coordinator().publish(OWN, fields(), fields(name = "new"))

        assertEquals(ProfilePublishResult.SupersededAfterAcceptance, result)
        assertEquals(1, harness.persistCalls)
    }

    @Test
    fun `failed freshness preflight refuses before signing or publishing`() = runTest {
        val original = profileEvent("original", 40L, "{\"name\":\"old\"}")
        val harness = Harness(original).apply {
            refreshResult = ProfileMetadataRefreshResult.UNAVAILABLE
        }

        val result = harness.coordinator().publish(
            OWN,
            fields(name = "old"),
            fields(name = "new"),
        )

        assertEquals(ProfilePublishResult.FreshnessUnavailable, result)
        assertEquals(1, harness.refreshCalls)
        assertEquals(0, harness.signCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(original, harness.profile)
    }

    @Test
    fun `EOSE confirmed absence permits a first profile publish`() = runTest {
        val harness = Harness(initial = null).apply {
            refreshResult = ProfileMetadataRefreshResult.CONFIRMED_ABSENT
        }

        val result = harness.coordinator(nowSeconds = 123L).publish(
            OWN,
            fields(name = ""),
            fields(name = "First profile"),
        )

        assertEquals(ProfilePublishResult.Success, result)
        assertEquals(1, harness.signCalls)
        assertEquals(1, harness.publishCalls)
        assertEquals(123L, harness.profile?.createdAt)
        assertEquals(
            "First profile",
            requireNotNull(harness.profile).let(::publishedContent)["name"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `empty relay response cannot discard a retained local profile`() = runTest {
        val original = profileEvent(
            "local-snapshot",
            100L,
            "{\"name\":\"old\",\"custom\":\"preserve\"}",
        )
        val harness = Harness(original).apply {
            refreshResult = ProfileMetadataRefreshResult.CONFIRMED_ABSENT
        }

        val result = harness.coordinator().publish(
            OWN,
            fields(name = "old"),
            fields(name = "new"),
        )

        assertEquals(ProfilePublishResult.FreshnessUnavailable, result)
        assertEquals(0, harness.signCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(original, harness.profile)
    }

    @Test
    fun `first profile arriving during external signing aborts the stale draft`() = runTest {
        val remote = profileEvent(
            "arrived-while-signing",
            200L,
            "{\"name\":\"published elsewhere\",\"custom\":\"preserve\"}",
        )
        val harness = Harness(initial = null).apply {
            refreshResult = ProfileMetadataRefreshResult.CONFIRMED_ABSENT
            afterSign = { profile = remote }
        }

        val result = harness.coordinator().publish(
            OWN,
            fields(name = ""),
            fields(name = "local draft"),
        )

        assertEquals(ProfilePublishResult.ChangedWhileSigning, result)
        assertEquals(1, harness.signCalls)
        assertEquals(0, harness.publishCalls)
        assertEquals(remote, harness.profile)
    }

    @Test
    fun `relay refresh happens before the merge source is loaded`() = runTest {
        val stale = profileEvent("stale", 40L, "{\"name\":\"old\"}")
        val fresh = profileEvent(
            "fresh",
            50L,
            "{\"name\":\"old\",\"pronouns\":\"preserve test\"}",
        )
        val harness = Harness(stale).apply { afterRefresh = { profile = fresh } }

        val result = harness.coordinator().publish(
            OWN,
            fields(name = "old"),
            fields(name = "new"),
        )

        assertEquals(ProfilePublishResult.Success, result)
        val published = requireNotNull(harness.profile)
        assertEquals("preserve test", publishedContent(published)["pronouns"]?.jsonPrimitive?.content)
        assertEquals("new", publishedContent(published)["name"]?.jsonPrimitive?.content)
    }

    private class Harness(initial: NostrEvent?) {
        var profile = initial
        var signCalls = 0
        var publishCalls = 0
        var persistCalls = 0
        var relayAccepted = true
        var persistRetained = true
        var refreshResult = ProfileMetadataRefreshResult.SETTLED
        var refreshCalls = 0
        var afterRefresh: () -> Unit = {}
        var afterSign: () -> Unit = {}
        val signedCreatedAts = mutableListOf<Long>()
        val callOrder = mutableListOf<String>()

        fun coordinator(nowSeconds: Long = 1_000L) = ProfilePublishCoordinator(
            refreshProfile = {
                refreshCalls++
                afterRefresh()
                refreshResult
            },
            loadProfile = { profile },
            sign = { createdAt, content ->
                callOrder += "sign"
                signCalls++
                signedCreatedAts += createdAt
                afterSign()
                val event = profileEvent("signed-$signCalls", createdAt, content)
                SignedProfileMetadata(event.id, "{}", event)
            },
            publishAndAwait = { _, _ ->
                callOrder += "publish"
                publishCalls++
                relayAccepted
            },
            persistAccepted = { _, signed ->
                callOrder += "persist"
                persistCalls++
                if (persistRetained) profile = signed.event
                persistRetained
            },
            nowSeconds = { nowSeconds },
        )
    }

    companion object {
        private const val OWN = "owner"

        private fun fields(name: String = "new") = EditableProfileMetadata(
            name = name,
            displayName = "",
            about = "",
            picture = "",
            banner = "",
            nip05 = "",
            lud16 = "",
            website = "",
        )

        private fun profileEvent(id: String, createdAt: Long, content: String) = NostrEvent(
            id = id,
            pubkey = OWN,
            kind = 0,
            content = content,
            createdAt = createdAt,
            tags = emptyList(),
            tagsJson = "[]",
            sig = "sig",
            relayUrl = "wss://relay.example",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = 0L,
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )

        private fun publishedContent(event: NostrEvent) =
            com.unsilence.app.data.relay.NostrJson.parseToJsonElement(event.content).jsonObject
    }
}
