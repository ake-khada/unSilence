package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.NetworkConditions
import com.unsilence.app.data.relay.NetworkState
import com.unsilence.app.domain.model.AutomaticMediaMode
import org.junit.Assert.*
import org.junit.Test

class VideoAutoplayPolicyTest {
    @Test fun `always preserves autoplay on every network including offline and unknown`() {
        allConditions().forEach {
            assertFalse("Always must allow autoplay for $it", videoAutoplayBlocked(AutomaticMediaMode.ALWAYS, it))
        }
    }

    @Test fun `never blocks automatic playback on every network`() {
        allConditions().forEach {
            assertTrue("Never must block autoplay for $it", videoAutoplayBlocked(AutomaticMediaMode.NEVER, it))
        }
    }

    @Test fun `unmetered only uses actual metering but preserves buffered playback offline`() {
        val blocked = listOf(
            NetworkConditions(NetworkState.ONLINE, isMetered = true),
            NetworkConditions(NetworkState.ONLINE, isMetered = true, isCellular = true),
            NetworkConditions(NetworkState.UNKNOWN, isMetered = true),
        )
        val allowed = listOf(
            NetworkConditions(NetworkState.ONLINE, isMetered = false),
            NetworkConditions(NetworkState.ONLINE, isMetered = false, isCellular = true),
            NetworkConditions(NetworkState.UNKNOWN, isMetered = false),
            NetworkConditions(NetworkState.OFFLINE, isMetered = true),
            NetworkConditions(NetworkState.OFFLINE, isMetered = false),
        )
        blocked.forEach { assertTrue(videoAutoplayBlocked(AutomaticMediaMode.UNMETERED_ONLY, it)) }
        allowed.forEach { assertFalse(videoAutoplayBlocked(AutomaticMediaMode.UNMETERED_ONLY, it)) }
    }

    @Test fun `explicit tap is allowed in every mode on every network`() {
        AutomaticMediaMode.entries.forEach { mode ->
            allConditions().forEach { conditions ->
                assertTrue(allowVideoPlayback(videoAutoplayBlocked(mode, conditions), true))
            }
        }
    }

    @Test fun `persisted names round trip and missing or unknown values default to always`() {
        AutomaticMediaMode.entries.forEach { assertEquals(it, AutomaticMediaMode.fromStoredName(it.name)) }
        listOf(null, "", "FUTURE_MODE", "1").forEach {
            assertEquals(AutomaticMediaMode.ALWAYS, AutomaticMediaMode.fromStoredName(it))
        }
    }

    @Test fun `same URL player error does not retry but a policy stop can recover`() {
        val url = "https://example.com/video.mp4"
        assertFalse(shouldPrepareAfterPolicyStop(true, null, url))
        assertTrue(shouldPrepareAfterPolicyStop(true, url, url))
        assertFalse(shouldPrepareAfterPolicyStop(false, url, url))
        assertFalse(shouldPrepareAfterPolicyStop(true, url, "https://example.com/other.mp4"))
    }

    private fun allConditions() = NetworkState.entries.flatMap { state ->
        listOf(false, true).flatMap { metered ->
            listOf(false, true).map { cellular -> NetworkConditions(state, metered, cellular) }
        }
    }
}
