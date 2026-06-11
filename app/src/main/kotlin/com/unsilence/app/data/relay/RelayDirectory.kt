package com.unsilence.app.data.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Relay Directory (Phase 1, data layer). A bounded in-memory index merging NIP-66
 * monitor data (kind-30166), kind-30385 trust, our empirical reachability
 * ([RelayCapabilitiesStore]), and follow-circle popularity (kind-10002). No UI here.
 *
 * Design note: NOT persisted to snapshot in v1 — rebuilt lazily via
 * RelayPool.ensureDirectoryFresh(). Revisit only if rebuild cost is measured painful.
 */

/** Our empirical verdict for a relay, from this device's transport history. Our store is
 *  the trump card over any monitor consensus — the monitor isn't behind your censor. */
enum class Reachability { REACHABLE, AUTH_WALL, DNS_BLOCKED, TIMEOUT_PRONE, DEAD, UNKNOWN }

/** Who authored the NIP-11 fields. Phase 1 is always MONITOR. */
enum class Nip11Source { MONITOR, DEVICE }

enum class RelayNetwork { CLEARNET, TOR, UNKNOWN }

data class RelayDirectoryEntry(
    val url: String,                                 // normalized via normalizeRelayUrl — single source of truth
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val software: String? = null,
    val version: String? = null,
    val supportedNips: Set<Int> = emptySet(),
    val network: RelayNetwork = RelayNetwork.UNKNOWN,
    val auth: Boolean = false,                       // NIP-11 limitation.auth_required (fallback: R tag)
    val payment: Boolean = false,                    // NIP-11 limitation.payment_required (fallback: R tag)
    val restrictedWrites: Boolean = false,           // NIP-11 limitation.restricted_writes
    val monitorRttMs: Int? = null,                   // from 30166 rtt-* (median across monitors at merge)
    val monitorLastSeenAt: Long = 0L,                // newest 30166 createdAt; eviction key
    val trustScore: Int? = null,                     // 30385 if present
    val ourRttMs: Int? = null,                       // reserved — no per-relay device RTT today (RelayCapabilities has none)
    val ourLastReason: String? = null,               // RelayCapabilities.lastReason
    val reachability: Reachability = Reachability.UNKNOWN,
    val popularity: Int = 0,                         // count of url across all kind-10002 read+write lists
    /**
     * Perspective-dependent relays exist (subnet.relays.land serves a different identity
     * per accessing subnet) — device-fetched NIP-11 (Phase 2, on detail-open) overwrites
     * name/description/icon/limitations and sets DEVICE. Device wins; monitor data is a
     * discovery seed, never the authority.
     */
    val nip11Source: Nip11Source = Nip11Source.MONITOR,
    val monitorPubkeys: Set<String> = emptySet(),    // which monitors reported (for the 2-monitor-negative rule)
)

object RelayDirectory {

    /**
     * Parse one kind-30166 event (tags + embedded NIP-11 content) into a monitor-sourced
     * [RelayDirectoryEntry]. Grounded in REAL captured schemas, not the NIP-66 draft:
     *  - rich monitor (nostr.watch): rtt-open/read/write (partial), `n` network, N× NIPs,
     *    R× requirements, full NIP-11 in content.
     *  - light/bot monitor: d + N + R + `bot`, NO rtt, NO `n`, EMPTY content.
     * Tolerates empty/sparse/malformed content (fields → null, never throws). Returns null
     * only when the d-tag (relay url) is missing or unnormalizable.
     */
    fun parseMonitorEvent(
        tags: List<List<String>>,
        content: String,
        monitorPubkey: String,
        createdAt: Long,
    ): RelayDirectoryEntry? {
        fun firstTag(name: String): String? =
            tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

        val rawUrl = firstTag("d") ?: return null
        val url = normalizeRelayUrl(rawUrl) ?: return null

        // RTT: rtt-write is rare, rtt-read sometimes absent, light monitor has none. Take
        // the first present in open→read→write order (open is the connect-latency signal).
        val rtt = firstTag("rtt-open")?.toIntOrNull()
            ?: firstTag("rtt-read")?.toIntOrNull()
            ?: firstTag("rtt-write")?.toIntOrNull()

        // Network tag is `n` ("clearnet"/"tor") — NOT "network" (the legacy handler's bug).
        val network = when (firstTag("n")?.lowercase()) {
            "clearnet" -> RelayNetwork.CLEARNET
            "tor" -> RelayNetwork.TOR
            else -> RelayNetwork.UNKNOWN
        }

        // N tags = monitor-observed supported NIPs (present in both schemas).
        val nipsFromTags = tags
            .filter { it.size >= 2 && it[0] == "N" }
            .mapNotNull { it[1].toIntOrNull() }
            .toSet()

        // R tags use `!`-negation: "auth" = required, "!auth" = NOT required (same for
        // "payment", "writes"). The NIP-11 limitation block (rich schema) overrides these
        // when present. Monitor NEGATIVES (a monitor's failure/down records) are ignored by
        // design in Phase 1: if ever used, require ≥2 independent monitor pubkeys agreeing,
        // and NEVER override this device's own transport evidence — monitors aren't behind
        // this user's censor (A5; pairs with computeReachability's device-empirical trump).
        val reqs = tags.filter { it.size >= 2 && it[0] == "R" }.map { it[1] }
        fun requiredByTag(token: String): Boolean = reqs.contains(token) && !reqs.contains("!$token")
        var auth = requiredByTag("auth")
        var payment = requiredByTag("payment")
        var restrictedWrites = requiredByTag("writes")

        // Embedded NIP-11. Empty (light monitor) / sparse / null-laden — tolerate all.
        var name: String? = null
        var description: String? = null
        var icon: String? = null
        var software: String? = null
        var version: String? = null
        var nipsFromContent: Set<Int> = emptySet()
        if (content.isNotBlank()) {
            runCatching {
                val obj = NostrJson.parseToJsonElement(content).jsonObject
                fun str(k: String): String? =
                    (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                name = str("name")
                description = str("description")
                icon = str("icon")
                software = str("software")
                version = str("version")
                nipsFromContent = (obj["supported_nips"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }?.toSet()
                    ?: emptySet()
                // limitation is the NIP-11 authority for auth/payment/restricted (overrides R tags).
                (obj["limitation"] as? JsonObject)?.let { lim ->
                    (lim["auth_required"] as? JsonPrimitive)?.booleanOrNull?.let { auth = it }
                    (lim["payment_required"] as? JsonPrimitive)?.booleanOrNull?.let { payment = it }
                    (lim["restricted_writes"] as? JsonPrimitive)?.booleanOrNull?.let { restrictedWrites = it }
                }
            }
        }

        return RelayDirectoryEntry(
            url = url,
            name = name,
            description = description,
            icon = icon,
            software = software,
            version = version,
            supportedNips = nipsFromTags + nipsFromContent,
            network = network,
            auth = auth,
            payment = payment,
            restrictedWrites = restrictedWrites,
            monitorRttMs = rtt,
            monitorLastSeenAt = createdAt,
            nip11Source = Nip11Source.MONITOR,
            monitorPubkeys = setOf(monitorPubkey),
        )
    }

    /**
     * Merge same-url entries from multiple monitors (the dual-monitor set). MEDIAN RTT where
     * monitors overlap (NIP-66 trust guidance); union NIPs + monitor pubkeys; richest NIP-11
     * wins (prefer non-null name/description); newest monitorLastSeenAt. Monitor data only
     * ever contributes POSITIVE signal — a negative reachability verdict needs ≥2 monitors
     * or our own transport evidence, enforced structurally because monitors never emit a
     * "down" record here (they report relays they SEE). Negatives come from computeReachability.
     */
    fun mergeMonitorEntries(entries: List<RelayDirectoryEntry>): RelayDirectoryEntry {
        require(entries.isNotEmpty())
        if (entries.size == 1) return entries.first()
        val rtts = entries.mapNotNull { it.monitorRttMs }.sorted()
        val medianRtt = if (rtts.isEmpty()) null else rtts[rtts.size / 2]
        // Richest NIP-11: the entry with a name (then description) wins for descriptive fields.
        val richest = entries.firstOrNull { it.name != null }
            ?: entries.firstOrNull { it.description != null }
            ?: entries.first()
        val newest = entries.maxBy { it.monitorLastSeenAt }
        return richest.copy(
            supportedNips = entries.flatMap { it.supportedNips }.toSet(),
            monitorPubkeys = entries.flatMap { it.monitorPubkeys }.toSet(),
            monitorRttMs = medianRtt,
            monitorLastSeenAt = newest.monitorLastSeenAt,
            network = entries.firstOrNull { it.network != RelayNetwork.UNKNOWN }?.network ?: RelayNetwork.UNKNOWN,
            auth = entries.any { it.auth },
            payment = entries.any { it.payment },
            restrictedWrites = entries.any { it.restrictedWrites },
        )
    }

    /**
     * Our empirical reachability verdict — pure, testable directly (computeRetryCooldownMs
     * pattern). Device evidence is the trump card: a monitor consensus saying "healthy" loses
     * to our DNS_BLOCKED, because the monitor isn't behind your censor. Negatives derive ONLY
     * from our own store (never from monitor data) — that IS the two-monitor-negative rule.
     */
    fun computeReachability(
        hasCapsEntry: Boolean,
        deadFailCount: Int,
        authRequired: Boolean,
        restricted: Boolean,
        strikes: Int,
        consecutiveFailures: Int,
        lastReason: String?,
        monitorRttMs: Int?,
    ): Reachability = when {
        // deadFailCount is DNS-only (CONNECT_TIMEOUT excluded, H18.4) — a dead relay is DNS-blocked here.
        deadFailCount >= DEAD_RELAY_THRESHOLD -> Reachability.DNS_BLOCKED
        authRequired || restricted -> Reachability.AUTH_WALL
        lastReason == SkipReason.DNS_RESOLUTION.name && consecutiveFailures > 0 -> Reachability.DNS_BLOCKED
        (lastReason == SkipReason.CONNECT_TIMEOUT.name && consecutiveFailures > 0) ||
            strikes >= MAX_CAPABILITY_STRIKES -> Reachability.TIMEOUT_PRONE
        // No negative device evidence: reachable if we have a clean caps entry or a monitor RTT.
        hasCapsEntry || monitorRttMs != null -> Reachability.REACHABLE
        else -> Reachability.UNKNOWN
    }

    /**
     * Directory ranking score — pure. Reachability dominates (10k bands), then trust, then
     * monitor RTT, then popularity. "Reachable-for-you beats globally-famous" by construction:
     * a REACHABLE unknown relay (≥10000) outranks a DNS_BLOCKED famous one (≤3500).
     */
    fun computeDirectoryScore(
        reachability: Reachability,
        trustScore: Int?,
        monitorRttMs: Int?,
        popularity: Int,
    ): Int {
        val reach = when (reachability) {
            Reachability.REACHABLE -> 10_000
            Reachability.UNKNOWN -> 5_000
            Reachability.AUTH_WALL -> 3_000
            Reachability.TIMEOUT_PRONE -> 1_000
            Reachability.DNS_BLOCKED -> 0
            Reachability.DEAD -> -10_000
        }
        val trust = (trustScore ?: 0).coerceIn(0, 100) * 20          // 0..2000
        val rtt = monitorRttMs?.let { ((2_000 - it).coerceIn(0, 2_000)) / 2 } ?: 0  // faster → higher, 0..1000
        val pop = popularity.coerceAtMost(500)                       // 0..500
        return reach + trust + rtt + pop
    }

    /**
     * Enforce the directory cap (pure, testable). Keeps ≤ [MAX_DIRECTORY_ENTRIES], evicting
     * OLDEST monitorLastSeenAt first — but NEVER evicts relays in [protectedUrls] (the user's
     * own kind-10002 lists), even if that pushes the result over the cap.
     */
    fun enforceBound(
        entries: Map<String, RelayDirectoryEntry>,
        protectedUrls: Set<String>,
    ): Map<String, RelayDirectoryEntry> {
        if (entries.size <= MAX_DIRECTORY_ENTRIES) return entries
        val protectedEntries = entries.filterKeys { it in protectedUrls }
        val keepCount = (MAX_DIRECTORY_ENTRIES - protectedEntries.size).coerceAtLeast(0)
        val kept = entries.asSequence()
            .filter { it.key !in protectedUrls }
            .sortedByDescending { it.value.monitorLastSeenAt }   // keep newest, evict oldest
            .take(keepCount)
            .associate { it.key to it.value }
        return protectedEntries + kept
    }

    const val MAX_DIRECTORY_ENTRIES = 3_000
}
