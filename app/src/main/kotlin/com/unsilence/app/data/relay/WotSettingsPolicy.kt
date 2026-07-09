package com.unsilence.app.data.relay

import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.DEFAULT_WOT_RELAY
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotProviderDescriptor
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class WotCoverage(
    val scored: Int,
    val total: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else scored.toFloat() / total.toFloat()
}

data class WotProviderOptionState(
    val source: WotProviderSource,
    val selected: Boolean,
    val enabled: Boolean,
    val subtitle: String,
    val actionHint: String? = null,
)

data class WotProviderRegistryDraft(
    val tags: List<List<String>>,
    val content: String,
)

fun computeWotCoverage(
    follows: Set<String>,
    assertions: Map<String, WotAssertionEntity>,
): WotCoverage {
    val normalizedFollows = follows.mapNotNull { normalizeWotPubkey(it) }.toSet()
    val scoredSubjects = assertions.keys.mapNotNull { normalizeWotPubkey(it) }.toSet()
    return WotCoverage(
        scored = normalizedFollows.count { it in scoredSubjects },
        total = normalizedFollows.size,
    )
}

fun deriveWotProviderOptions(
    prefs: WotProviderPrefs,
    ownProvider: WotProviderDescriptor?,
    encryptedOwnProviderAvailable: Boolean,
): List<WotProviderOptionState> {
    val ownSubtitle = when {
        ownProvider != null -> "Provider ${shortProviderPubkey(ownProvider.providerPubkey)} · ${ownProvider.relayHint}"
        encryptedOwnProviderAvailable -> "Encrypted provider list found"
        else -> "No provider list (kind 10040) found"
    }
    return listOf(
        WotProviderOptionState(
            source = WotProviderSource.DEFAULT,
            selected = prefs.source == WotProviderSource.DEFAULT,
            enabled = true,
            subtitle = "straycat's grapevine · default",
        ),
        WotProviderOptionState(
            source = WotProviderSource.OWN_10040,
            selected = prefs.source == WotProviderSource.OWN_10040,
            enabled = ownProvider != null,
            subtitle = ownSubtitle,
            actionHint = when {
                ownProvider != null -> null
                encryptedOwnProviderAvailable -> "Decrypt"
                else -> "Set up"
            },
        ),
        WotProviderOptionState(
            source = WotProviderSource.CUSTOM,
            selected = prefs.source == WotProviderSource.CUSTOM,
            enabled = true,
            subtitle = if (prefs.source == WotProviderSource.CUSTOM) {
                "${shortProviderPubkey(prefs.pubkey)} · ${prefs.relay}"
            } else {
                "Enter provider npub and relay"
            },
            actionHint = "Edit",
        ),
    )
}

fun wotProviderDescriptorFromTags(
    tags: List<List<String>>,
    updatedAt: Long = 0L,
): WotProviderDescriptor? {
    val tag = tags.firstOrNull { it.size >= 3 && it[0] == "30382:rank" } ?: return null
    val providerPubkey = normalizeWotPubkey(tag[1]) ?: return null
    val relayHint = normalizeRelayUrl(tag[2]) ?: return null
    return WotProviderDescriptor(
        providerPubkey = providerPubkey,
        relayHint = relayHint,
        updatedAt = updatedAt,
    )
}

fun parseEncryptedWotProviderTagsJson(
    plaintext: String,
    updatedAt: Long = 0L,
): WotProviderDescriptor? {
    val tags = parseWotTagArrayJson(plaintext) ?: return null
    return wotProviderDescriptorFromTags(tags, updatedAt)
}

fun mergeWotProviderRegistryTags(
    existingTags: List<List<String>>,
    provider: WotProviderDescriptor,
): List<List<String>> {
    val providerPubkey = normalizeWotPubkey(provider.providerPubkey) ?: provider.providerPubkey
    val relayHint = normalizeRelayUrl(provider.relayHint) ?: provider.relayHint
    val replacementRows = listOf(
        listOf("30382:rank", providerPubkey, relayHint),
        listOf("30382:followers", providerPubkey, relayHint),
    )
    val merged = ArrayList<List<String>>(existingTags.size + replacementRows.size)
    var inserted = false
    for (tag in existingTags) {
        if (tag.firstOrNull() == "30382:rank" || tag.firstOrNull() == "30382:followers") {
            if (!inserted) {
                merged.addAll(replacementRows)
                inserted = true
            }
        } else {
            merged.add(tag)
        }
    }
    if (!inserted) merged.addAll(replacementRows)
    return merged
}

fun mergeWotProviderRegistryDraft(
    existingTags: List<List<String>>,
    existingContent: String,
    provider: WotProviderDescriptor,
): WotProviderRegistryDraft =
    WotProviderRegistryDraft(
        tags = mergeWotProviderRegistryTags(existingTags, provider),
        content = existingContent,
    )

fun normalizeWotProviderPubkeyInput(input: String): String? {
    val trimmed = input.trim()
    normalizeWotPubkey(trimmed)?.let { return it }
    if (!trimmed.startsWith("npub1", ignoreCase = true) &&
        !trimmed.startsWith("nprofile1", ignoreCase = true)
    ) {
        return null
    }
    return runCatching {
        when (val entity = Nip19Parser.uriToRoute(trimmed)?.entity) {
            is NPub -> normalizeWotPubkey(entity.hex)
            is NProfile -> normalizeWotPubkey(entity.hex)
            else -> null
        }
    }.getOrNull()
}

fun defaultWotProviderDescriptor(): WotProviderDescriptor =
    WotProviderDescriptor(
        providerPubkey = DEFAULT_WOT_PROVIDER_PUBKEY,
        relayHint = DEFAULT_WOT_RELAY,
        updatedAt = 0L,
    )

private fun parseWotTagArrayJson(plaintext: String): List<List<String>>? {
    val element = runCatching { NostrJson.parseToJsonElement(plaintext.trim()) }.getOrNull()
        ?: return null
    return parseWotTagArrayElement(element)
}

private fun parseWotTagArrayElement(element: JsonElement): List<List<String>>? {
    if (element is JsonPrimitive && element.isString) {
        val nested = element.contentOrNull?.trim()?.takeIf { it.startsWith("[") } ?: return null
        return parseWotTagArrayJson(nested)
    }
    val array = element as? JsonArray ?: return null
    if (array.isEmpty()) return emptyList()

    val first = array.first()
    return if (first is JsonPrimitive) {
        listOf(array.mapNotNull { it.jsonPrimitive.contentOrNull })
    } else {
        array.mapNotNull { tagEl ->
            runCatching {
                tagEl.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull()
        }
    }
}

private fun shortProviderPubkey(pubkey: String): String =
    if (pubkey.length > 14) "${pubkey.take(8)}…${pubkey.takeLast(6)}" else pubkey
