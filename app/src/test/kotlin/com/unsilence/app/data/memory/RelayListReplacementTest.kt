package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelayListReplacementTest {
    @Test fun `same timestamp signed lists choose lowest lexical id in either arrival order`() = runBlocking {
        val crypto = ZapCryptoTestFixture()
        for (kind in listOf(10002, 10006, 10007)) {
            val tag = if (kind == 10002) "r" else "relay"
            val events = listOf("a", "b").map { host ->
                crypto.signedEvent(crypto.sender, kind, listOf(listOf(tag, "wss://$host.test")), "", createdAt = 100)
            }.sortedBy { it.id }
            for (order in listOf(events, events.reversed())) {
                val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
                order.forEach { store.insert(it.toNostrEvent("wss://fixture.test")) }
                val actual = when (kind) {
                    10002 -> store.writeRelaysFor(crypto.sender.pubkey)
                    10006 -> store.getBlockedRelayUrls(crypto.sender.pubkey)
                    else -> store.getSearchRelayUrls(crypto.sender.pubkey)
                }
                assertEquals("kind $kind must obey NIP-01 lowest ID", listOf(events.first().tags.first()[1]), actual)
            }
        }
    }
    @Test fun `snapshot and live replacement share ordering and older lower id never wins`() = runBlocking {
        val crypto = ZapCryptoTestFixture()
        for (kind in listOf(10002, 10006, 10007)) {
            val tag = if (kind == 10002) "r" else "relay"
            val atSameTime = listOf("a", "b").map { host ->
                crypto.signedEvent(crypto.sender, kind, listOf(listOf(tag, "wss://$host.test")), "", createdAt = 100)
            }.sortedBy { it.id }
            for (snapshotFirst in listOf(true, false)) {
                val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
                val first = atSameTime[if (snapshotFirst) 1 else 0].toNostrEvent("wss://fixture.test")
                val second = atSameTime[if (snapshotFirst) 0 else 1].toNostrEvent("wss://fixture.test")
                store.insertFromSnapshot(first)
                store.insert(second)
                fun actual() = when (kind) {
                    10002 -> store.writeRelaysFor(crypto.sender.pubkey)
                    10006 -> store.getBlockedRelayUrls(crypto.sender.pubkey)
                    else -> store.getSearchRelayUrls(crypto.sender.pubkey)
                }
                assertEquals(listOf(atSameTime.first().tags.first()[1]), actual())
                val newer = crypto.signedEvent(crypto.sender, kind, listOf(listOf(tag, "wss://new.test")), "", createdAt = 101)
                store.insert(newer.toNostrEvent("wss://fixture.test"))
                // Trusted snapshot fixture isolates timestamp precedence even for the lowest ID.
                store.insertFromSnapshot(first.copy(id = "0".repeat(64), createdAt = 99))
                assertEquals(listOf("wss://new.test"), actual())
            }
        }
    }

}
