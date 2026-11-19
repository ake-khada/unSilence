package com.unsilence.app.ui.feed

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.UnsilenceApp
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SharedPlayerLoadingTest {
    @Test
    fun releasedItemStopsReadingAndResumesOnlyAfterAClaim() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val holder = ApplicationProvider.getApplicationContext<UnsilenceApp>().sharedPlayerHolder
        val ready = CountDownLatch(1)
        val initialRead = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val readAhead = CountDownLatch(1)
        val audio = silentWav()
        val source = ProgressiveMediaSource.Factory {
            val input = ByteArrayDataSource(audio)
            object : DataSource by input {
                private var bytesRead = 0

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = input.read(buffer, offset, length)
                    if (count > 0) bytesRead += count
                    if (bytesRead >= 1536 * 1024 && initialRead.count > 0) {
                        initialRead.countDown()
                        if (!allowRead.await(10, SECONDS)) throw IOException("Read barrier timed out")
                    }
                    // A progressive load can finish its current 1 MiB interval after release.
                    if (bytesRead >= 3 * 1024 * 1024) readAhead.countDown()
                    return count
                }
            }
        }.createMediaSource(MediaItem.fromUri("memory:///fixture.wav"))

        try {
            instrumentation.runOnMainSync {
                holder.claim("profile", explicitlyRequested = true).apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) ready.countDown()
                        }
                    })
                    setMediaSource(source)
                    prepare()
                }
            }
            assertTrue("Player never started reading", initialRead.await(10, SECONDS))
            assertTrue("Prepared item never became playable", ready.await(10, SECONDS))
            instrumentation.runOnMainSync {
                assertTrue("Test requires allowed automatic playback", holder.canAutoplay)
                holder.releaseOwnership("profile")
                assertTrue(holder.isRetained)
            }
            allowRead.countDown()
            assertFalse("Released player kept reading", readAhead.await(2, SECONDS))
            instrumentation.runOnMainSync {
                val player = holder.claim("thread")
                holder.releaseOwnership("profile")
                assertTrue(holder.isOwner("thread"))
                assertEquals(source.mediaItem, player.currentMediaItem)
                assertFalse("A warm reclaim should not prepare again", holder.recoverPolicyStop())
                player.play()
            }
            assertTrue("Claimed player never resumed loading", readAhead.await(10, SECONDS))
        } finally {
            allowRead.countDown()
            instrumentation.runOnMainSync { holder.release() }
        }
    }

    @Test
    fun leavingDuringInitialPreparePreservesTheItemForOneAuthorizedRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val holder = ApplicationProvider.getApplicationContext<UnsilenceApp>().sharedPlayerHolder
        val item = MediaItem.fromUri("memory:///starting.wav")
        val source = ProgressiveMediaSource.Factory { ByteArrayDataSource(silentWav()) }.createMediaSource(item)
        instrumentation.runOnMainSync {
            try {
                val player = holder.claim("profile", explicitlyRequested = true)
                player.setMediaSource(source)
                player.prepare()
                assertEquals(Player.STATE_BUFFERING, player.playbackState)
                holder.releaseOwnership("profile")
                assertEquals(Player.STATE_IDLE, player.playbackState)
                assertEquals(item, player.currentMediaItem)
                holder.claim("profile", explicitlyRequested = true)
                assertTrue(holder.recoverPolicyStop())
                assertFalse(holder.recoverPolicyStop())
            } finally {
                holder.release()
            }
        }
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 48_000
        val bytesPerFrame = 4 // stereo, 16-bit PCM
        val byteRate = sampleRate * bytesPerFrame
        val dataSize = 40 * byteRate
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            putInt(sampleRate).putInt(byteRate).putShort(bytesPerFrame.toShort()).putShort(16)
            put("data".toByteArray()).putInt(dataSize)
        }.array()
    }
}
