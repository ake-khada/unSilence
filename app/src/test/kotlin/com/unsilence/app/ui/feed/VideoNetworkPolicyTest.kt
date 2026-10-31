package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.NetworkConditions
import com.unsilence.app.data.relay.NetworkState
import org.junit.Assert.*
import org.junit.Test

class VideoNetworkPolicyTest {
    private class PlayerFixture {
        var playing = true
        var buffering = true
        var preload = true
        var position = 1234L
        val policy = VideoNetworkPolicy(
            stopUnrequested = { playing = false; buffering = false },
            clearPreloads = { preload = false },
        )
    }

    @Test fun `unmetered to offline preserves automatic playback buffer and preload`() {
        val player = PlayerFixture()
        player.policy.claimed(false)
        player.policy.networkChanged(NetworkConditions(NetworkState.ONLINE, isMetered = false).blocksAutoplay)
        val offline = NetworkConditions(NetworkState.OFFLINE, isMetered = true)
        player.policy.networkChanged(offline.blocksAutoplay)
        assertTrue("Offline must not stop buffered autoplay", player.playing)
        assertTrue(player.buffering)
        assertTrue(player.preload)
        assertEquals(1234L, player.position)
        assertTrue(allowVideoPlayback(offline.blocksAutoplay, false))
    }

    @Test fun `offline to metered cellular stops automatic playback and preload`() {
        val player = PlayerFixture()
        player.policy.claimed(false)
        player.policy.networkChanged(NetworkConditions(NetworkState.OFFLINE, isMetered = true).blocksAutoplay)
        assertTrue("Offline must preserve the existing buffer", player.buffering)
        val cellular = NetworkConditions(NetworkState.ONLINE, isMetered = true, isCellular = true)
        player.policy.networkChanged(cellular.blocksAutoplay)
        assertFalse(player.playing)
        assertFalse(player.buffering)
        assertFalse(player.preload)
        assertEquals(1234L, player.position)
        assertFalse(allowVideoPlayback(cellular.blocksAutoplay, false))
        assertTrue(allowVideoPlayback(cellular.blocksAutoplay, true))
    }

    @Test fun `metered blocks autoplay but preserves explicit tap eligibility`() {
        assertFalse(allowVideoPlayback(true, false))
        assertTrue(allowVideoPlayback(true, true))
        assertTrue(allowVideoPlayback(false, false))
        assertTrue(allowVideoPlayback(false, true))
    }

    @Test fun `switching to metered stops automatic playback and preload without moving position`() {
        val player = PlayerFixture()
        player.policy.claimed(false)
        player.policy.networkChanged(false)
        assertTrue(player.playing)
        player.policy.networkChanged(true)
        assertFalse(player.playing)
        assertFalse(player.buffering)
        assertFalse(player.preload)
        assertEquals(1234L, player.position)
    }

    @Test fun `network changes never interrupt explicitly requested playback`() {
        val player = PlayerFixture()
        player.policy.claimed(true)
        player.policy.networkChanged(true)
        assertTrue(player.playing)
        assertTrue(player.buffering)
        assertFalse(player.preload)
        player.policy.networkChanged(false)
        assertTrue(player.playing)
        assertTrue(allowVideoPlayback(false, false))
    }

    @Test fun `releasing explicit ownership stops metered buffering and does not authorize next item`() {
        val player = PlayerFixture()
        player.policy.claimed(true)
        player.policy.networkChanged(true)
        player.policy.released()
        assertFalse(player.playing)
        assertFalse(player.buffering)
        assertFalse(allowVideoPlayback(true, false))
    }

    @Test fun `unmetered release leaves existing codec and buffering policy alone`() {
        val player = PlayerFixture()
        player.policy.claimed(false)
        player.policy.networkChanged(false)
        player.policy.released()
        assertTrue(player.playing)
        assertTrue(player.buffering)
        assertTrue(player.preload)
    }
}
