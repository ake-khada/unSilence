package com.unsilence.app.data.repository

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.ProfileMetadataRefreshResult
import com.unsilence.app.data.relay.hasValidCanonicalSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProfileTagPublishTest {
    @Test
    fun editingBioSignsPreservedTagsAndPersistsTheAcknowledgedEvent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName.endsWith(".dev"))
        val keys = KeyManager(context)
        assumeTrue(!keys.hasKey())
        val signer = SigningManager(keys, context.contentResolver)
        keys.generateNewKey()
        try {
            val owner = requireNotNull(keys.getPublicKeyHex())
            val identity = listOf("i", "github:fixture", "proof")
            val image = listOf("imeta", "url https://fixture.invalid/photo", "fallback https://fixture.invalid/copy")
            val warning = listOf("content-warning", "fixture")
            val base = MergedProfileMetadata(
                """{"about":"old","picture":"https://fixture.invalid/photo"}""",
                listOf(identity, listOf("client", "previous"), image, warning, listOf("about", "old")),
            )
            var stored = requireNotNull(signer.signProfileMetadata(owner, 1, base)).event
            var acknowledgedId: String? = null
            val expectedTags = listOf(identity, image, warning, listOf("about", "new"))
            val coordinator = ProfilePublishCoordinator(
                refreshProfile = { ProfileMetadataRefreshResult.SETTLED },
                loadProfile = { stored },
                sign = signer::signProfileMetadata,
                publishAndAwait = { _, signed ->
                    val event = NostrJson.decodeFromString<EventDto>(signed.eventJson)
                    assertTrue(event.hasValidCanonicalSignature())
                    assertEquals(owner, event.pubkey)
                    assertEquals(0, event.kind)
                    assertEquals(expectedTags, event.tags)
                    assertEquals(
                        NostrJson.parseToJsonElement("""{"about":"new","picture":"https://fixture.invalid/photo"}"""),
                        NostrJson.parseToJsonElement(event.content),
                    )
                    acknowledgedId = event.id
                    true
                },
                persistAccepted = { _, signed ->
                    assertEquals(acknowledgedId, signed.event.id)
                    stored = signed.event
                    true
                },
                nowSeconds = { 2 },
            )
            val form = EditableProfileMetadata("", "", "old", "https://fixture.invalid/photo", "", "", "", "")
            assertEquals(ProfilePublishResult.Success, coordinator.publish(owner, form, form.copy(about = "new")))
            assertEquals(expectedTags, stored.tags)
            assertTrue(stored.hasContentWarning)
        } finally {
            signer.clear()
            keys.clear()
        }
    }
}
