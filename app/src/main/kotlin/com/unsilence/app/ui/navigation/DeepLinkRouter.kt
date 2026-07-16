package com.unsilence.app.ui.navigation

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val HEX_PUBKEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

@Immutable
sealed interface DeepLinkTarget {
    @Immutable
    data class Profile(
        val pubkey: String,
        val relayHints: List<String> = emptyList(),
    ) : DeepLinkTarget

    @Immutable
    data class Note(
        val eventId: String,
        val relayHints: List<String> = emptyList(),
    ) : DeepLinkTarget

    @Immutable
    data class Address(
        val kind: Int,
        val pubkey: String,
        val dTag: String,
        val relayHints: List<String> = emptyList(),
    ) : DeepLinkTarget {
        val coordinate: String get() = "$kind:$pubkey:$dTag"
    }
}

/** Pure external-link parser. Secret keys and unsupported entities are rejected. */
internal fun resolveDeepLink(
    uri: String,
    decodePayload: (String) -> DeepLinkTarget? = ::decodeNip19Payload,
): DeepLinkTarget? {
    val trimmed = uri.trim()
    val separator = trimmed.indexOf(':')
    if (separator <= 0 || !trimmed.substring(0, separator).equals("nostr", ignoreCase = true)) {
        return null
    }
    val payload = trimmed.substring(separator + 1)
    if (payload.isBlank()) return null
    if (payload.startsWith("nsec1", ignoreCase = true)) return null
    if (HEX_PUBKEY_REGEX.matches(payload)) {
        return DeepLinkTarget.Profile(payload.lowercase())
    }

    return decodePayload(payload)
}

private fun decodeNip19Payload(payload: String): DeepLinkTarget? {
    val entity = runCatching { Nip19Parser.uriToRoute("nostr:$payload")?.entity }.getOrNull()
    return when (entity) {
        is NPub -> DeepLinkTarget.Profile(entity.hex)
        is NProfile -> DeepLinkTarget.Profile(
            pubkey = entity.hex,
            relayHints = entity.relay.map { it.url }.distinct(),
        )
        is NNote -> DeepLinkTarget.Note(entity.hex)
        is NEvent -> DeepLinkTarget.Note(
            eventId = entity.hex,
            relayHints = entity.relay.map { it.url }.distinct(),
        )
        is NAddress -> DeepLinkTarget.Address(
            kind = entity.kind,
            pubkey = entity.author,
            dTag = entity.dTag,
            relayHints = entity.relay.map { it.url }.distinct(),
        )
        else -> null
    }
}

/**
 * Process-local, single-slot handoff between Android intents and the logged-in UI.
 * A newer external intent deliberately replaces an older unconsumed one.
 */
@Singleton
class DeepLinkRouter @Inject constructor() {
    private val _pendingTarget = MutableStateFlow<DeepLinkTarget?>(null)
    val pendingTarget: StateFlow<DeepLinkTarget?> = _pendingTarget.asStateFlow()

    private val _pendingFailure = MutableStateFlow(false)
    val pendingFailure: StateFlow<Boolean> = _pendingFailure.asStateFlow()

    fun submit(uri: String) {
        val target = resolveDeepLink(uri)
        if (target != null) {
            submit(target)
        } else {
            _pendingTarget.value = null
            _pendingFailure.value = true
        }
    }

    internal fun submit(target: DeepLinkTarget) {
        _pendingFailure.value = false
        _pendingTarget.value = target
    }

    fun consume(target: DeepLinkTarget): Boolean =
        _pendingTarget.compareAndSet(target, null)

    fun consumeFailure(): Boolean =
        _pendingFailure.compareAndSet(expect = true, update = false)
}
