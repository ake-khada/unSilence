package com.unsilence.app.data.media

import com.unsilence.app.data.relay.NetworkConditions
import com.unsilence.app.data.relay.NetworkState
import com.unsilence.app.domain.model.AutomaticMediaMode
import com.unsilence.app.domain.model.AutomaticMediaPreferences
import org.junit.Assert.*
import org.junit.Test

class MediaLoadingPolicyTest {
    @Test fun `unloaded preference blocks requests until a real snapshot is available`() {
        assertEquals(MediaLoadPermissions(false, false), mediaLoadPermissions(null, NetworkConditions(NetworkState.ONLINE, false)))
        assertEquals(MediaLoadPermissions(), mediaLoadPermissions(AutomaticMediaPreferences(), NetworkConditions()))
    }

    @Test fun `image and preview policies are independent across every network`() {
        for (state in NetworkState.entries) for (metered in listOf(false, true)) {
            val conditions = NetworkConditions(state, metered)
            assertEquals(MediaLoadPermissions(false, true), mediaLoadPermissions(AutomaticMediaPreferences(images = AutomaticMediaMode.NEVER), conditions))
            assertEquals(MediaLoadPermissions(true, false), mediaLoadPermissions(AutomaticMediaPreferences(previews = AutomaticMediaMode.NEVER), conditions))
            val expected = !metered || state == NetworkState.OFFLINE
            assertEquals(MediaLoadPermissions(expected, expected), mediaLoadPermissions(AutomaticMediaPreferences(images = AutomaticMediaMode.UNMETERED_ONLY, previews = AutomaticMediaMode.UNMETERED_ONLY), conditions))
        }
    }
}
