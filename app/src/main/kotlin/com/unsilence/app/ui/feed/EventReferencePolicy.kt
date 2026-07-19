package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.RelayResolutionTargets
import com.unsilence.app.data.relay.bridgeFallbackRelayTargets
import com.unsilence.app.data.relay.boundedSeenRelayHints
import com.unsilence.app.data.relay.relayResolutionTargets
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class EventAddressReference(
    val kind: Int,
    val authorPubkey: String,
    val dTag: String,
) {
    val coordinate: String get() = "$kind:$authorPubkey:$dTag"
}

data class EventReferenceTarget(
    val eventId: String?,
    val address: EventAddressReference?,
    val authorPubkey: String?,
    val relayHints: List<String>,
) {
    val lookupKey: String get() = eventId ?: address?.coordinate.orEmpty()
}

internal enum class EventReferenceLookupStep { EVENT_ID, ADDRESS }

internal data class EventReferenceRelayLadder(
    val eventId: RelayResolutionTargets?,
    val address: RelayResolutionTargets?,
    val eventIdBridgeFallback: List<String>,
    val addressBridgeFallback: List<String>,
)

internal enum class EventReferenceRelayPhase {
    EVENT_ID_STANDARD,
    ADDRESS_STANDARD,
    EVENT_ID_BRIDGE,
    ADDRESS_BRIDGE,
}

/** Execution order: every established phase completes before either bridge lookup. */
internal fun EventReferenceRelayLadder.orderedPhases(): List<EventReferenceRelayPhase> = buildList {
    if (eventId != null) add(EventReferenceRelayPhase.EVENT_ID_STANDARD)
    if (address != null) add(EventReferenceRelayPhase.ADDRESS_STANDARD)
    if (eventIdBridgeFallback.isNotEmpty()) add(EventReferenceRelayPhase.EVENT_ID_BRIDGE)
    if (addressBridgeFallback.isNotEmpty()) add(EventReferenceRelayPhase.ADDRESS_BRIDGE)
}

internal fun eventReferenceLookupSteps(target: EventReferenceTarget): List<EventReferenceLookupStep> =
    buildList {
        if (!target.eventId.isNullOrBlank()) add(EventReferenceLookupStep.EVENT_ID)
        if (target.address != null) add(EventReferenceLookupStep.ADDRESS)
    }

/** Relay targets for both logical phases; locality hints lead each phase. */
internal fun eventReferenceRelayLadder(
    target: EventReferenceTarget,
    browseRelayHints: Collection<String>,
    idFallbackRelays: Collection<String>,
    addressFallbackRelays: Collection<String> = idFallbackRelays,
): EventReferenceRelayLadder {
    fun targets(fallback: Collection<String>) = relayResolutionTargets(
        seenRelays = target.relayHints,
        browseRelays = browseRelayHints,
        fallbackRelays = fallback,
    )
    val eventIdTargets = target.eventId?.takeIf { it.isNotBlank() }?.let { targets(idFallbackRelays) }
    val addressTargets = target.address?.let { targets(addressFallbackRelays) }
    return EventReferenceRelayLadder(
        eventId = eventIdTargets,
        address = addressTargets,
        eventIdBridgeFallback = eventIdTargets
            ?.let { bridgeFallbackRelayTargets(it.all) }
            .orEmpty(),
        addressBridgeFallback = addressTargets
            ?.let { bridgeFallbackRelayTargets(it.all) }
            .orEmpty(),
    )
}

internal fun parseEventAddressReference(raw: String?): EventAddressReference? {
    if (raw.isNullOrBlank()) return null
    val firstSeparator = raw.indexOf(':')
    val secondSeparator = raw.indexOf(':', firstSeparator + 1)
    if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1) return null
    val kind = raw.substring(0, firstSeparator).toIntOrNull() ?: return null
    if (kind !in 10000..39999) return null
    val author = raw.substring(firstSeparator + 1, secondSeparator)
    if (author.isBlank()) return null
    return EventAddressReference(
        kind = kind,
        authorPubkey = author,
        dTag = raw.substring(secondSeparator + 1),
    )
}

/** Build the direct-parent reference carried by NIP-22 comment tags. */
internal fun buildReplyParentReference(
    eventId: String?,
    tagsJson: String,
    sourceRelay: String?,
    sourceRelayHints: List<String> = emptyList(),
): EventReferenceTarget? {
    val tags = runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray.map { element ->
            element.jsonArray.map { it.jsonPrimitive.content }
        }
    }.getOrDefault(emptyList())

    val address = sequenceOf("a", "A")
        .mapNotNull { name -> tags.firstOrNull { it.getOrNull(0) == name }?.getOrNull(1) }
        .mapNotNull(::parseEventAddressReference)
        .firstOrNull()
    val author = sequenceOf("p", "P")
        .mapNotNull { name -> tags.firstOrNull { it.getOrNull(0) == name }?.getOrNull(1) }
        .firstOrNull { it.isNotBlank() }
        ?: address?.authorPubkey
    val hints = buildList {
        sourceRelay?.let(::add)
        addAll(sourceRelayHints)
        tags.asSequence()
            .filter { it.getOrNull(0) in setOf("e", "E", "a", "A", "p", "P") }
            .mapNotNull { it.getOrNull(2) }
            .forEach(::add)
    }.let { boundedSeenRelayHints(it) }

    val normalizedId = eventId?.takeIf { it.isNotBlank() }
    if (normalizedId == null && address == null) return null
    return EventReferenceTarget(normalizedId, address, author, hints)
}

/** Empty reposts and reply parents deliberately share the same lookup contract. */
internal fun buildRepostTargetReference(
    eventId: String?,
    addressCoordinate: String?,
    authorPubkey: String?,
    relayHints: List<String>,
): EventReferenceTarget? {
    val address = parseEventAddressReference(addressCoordinate)
    val normalizedId = eventId?.takeIf { it.isNotBlank() }
    if (normalizedId == null && address == null) return null
    return EventReferenceTarget(
        eventId = normalizedId,
        address = address,
        authorPubkey = authorPubkey?.takeIf { it.isNotBlank() } ?: address?.authorPubkey,
        relayHints = boundedSeenRelayHints(relayHints),
    )
}
