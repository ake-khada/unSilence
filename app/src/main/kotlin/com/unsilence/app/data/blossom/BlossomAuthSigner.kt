package com.unsilence.app.data.blossom

import android.util.Base64
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs BUD-01 auth events (kind 24242) for Blossom server requests.
 * Tags: t (verb), x (payload SHA-256), expiration.
 */
@Singleton
class BlossomAuthSigner @Inject constructor(
    private val signingManager: SigningManager,
) {
    /**
     * Builds the BUD-01 Authorization header value for a Blossom request.
     *
     * @param sha256Hex SHA-256 of the request body, lowercase hex.
     * @param verb BUD-01 action tag: "upload", "media", "list", "delete", "get".
     * @param expirationSeconds Lifetime of this auth event from now, in seconds.
     *        Defaults to 300 (5 minutes). Strict Blossom servers REQUIRE this tag.
     * @return The full header value "Nostr <base64-event-json>" or null if
     *         signing failed.
     */
    suspend fun authHeader(
        sha256Hex: String,
        verb: String = "upload",
        expirationSeconds: Long = 300L,
    ): String? {
        val now = System.currentTimeMillis() / 1000L
        val expiration = now + expirationSeconds

        val template = EventTemplate<Event>(
            createdAt = now,
            kind = 24242,
            tags = arrayOf(
                arrayOf("t", verb),
                arrayOf("x", sha256Hex),
                arrayOf("expiration", expiration.toString()),
            ),
            content = "",
        )
        val signed = signingManager.sign(template) ?: return null
        val json = toEventJson(signed)
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Nostr $b64"
    }
}
