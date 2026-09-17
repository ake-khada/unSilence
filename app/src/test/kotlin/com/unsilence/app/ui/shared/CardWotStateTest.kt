package com.unsilence.app.ui.shared

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.unsilence.app.data.memory.WotLookup
import org.junit.Assert.*
import org.junit.Test

class CardWotStateTest {
    @Test fun `unrelated author hydration does not invalidate a card`() {
        val map = mutableStateOf(mapOf<String, WotLookup>("alice" to WotLookup.Pending))
        val lookup: (String) -> WotLookup? = { map.value[it] }
        val card = cardWotState("alice", lookup)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { invalidations++ }) { assertEquals(WotLookup.Pending, card.value) }
            Snapshot.withMutableSnapshot { map.value = map.value + ("bob" to WotLookup.Absent) }
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            Snapshot.withMutableSnapshot { map.value = map.value + ("alice" to WotLookup.Absent) }
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            assertEquals(WotLookup.Absent, card.value)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun `provider reads latest map including removal not captured snapshot`() {
        val map = mutableStateOf(emptyMap<String, WotLookup>())
        val card = cardWotState("alice") { map.value[it] }
        assertNull(card.value)
        map.value = mapOf("alice" to WotLookup.Pending)
        assertEquals(WotLookup.Pending, card.value)
        map.value = emptyMap()
        assertNull(card.value)
    }

    @Test fun `absent provider remains supported`() {
        assertNull(cardWotState("alice", null).value)
    }
}
