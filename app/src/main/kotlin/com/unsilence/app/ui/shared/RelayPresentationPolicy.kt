package com.unsilence.app.ui.shared

import com.unsilence.app.data.relay.normalizeRelayUrl
import java.util.Locale

data class RelayProvenanceItem(
    val url: String,
    val host: String,
    val iconUrl: String? = null,
)

fun relayDisplayHost(url: String): String =
    url.removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")

fun relayProvenanceItems(
    relays: Collection<String>,
    iconUrlFor: (String) -> String? = { null },
): List<RelayProvenanceItem> =
    relays.asSequence()
        .mapNotNull(::normalizeRelayUrl)
        .distinct()
        .map { url ->
            RelayProvenanceItem(
                url = url,
                host = relayDisplayHost(url),
                iconUrl = iconUrlFor(url)?.takeIf(String::isNotBlank),
            )
        }
        .sortedWith(
            compareBy<RelayProvenanceItem> { it.host.lowercase(Locale.ROOT) }
                .thenBy { it.url },
        )
        .toList()

fun resolveRelayIconUrls(
    relayUrls: Collection<String>,
    monitorIcons: Map<String, String>,
    deviceIcons: Map<String, String>,
): Map<String, String> = relayUrls.asSequence()
    .distinct()
    .mapNotNull { url ->
        (deviceIcons[url]?.takeIf(String::isNotBlank)
            ?: monitorIcons[url]?.takeIf(String::isNotBlank))
            ?.let { icon -> url to icon }
    }
    .toMap()
