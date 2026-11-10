package com.unsilence.app.data.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleCardSchedulingTest {
    @Test fun `engagement is accumulated while the media probe is still suspended`() = runTest {
        val media = CompletableDeferred<Unit>()
        val stages = mutableListOf<String>()
        val job = launch {
            hydrateVisibleCardWork(
                hydrateMedia = { stages += "probe"; media.await(); stages += "media-done" },
                accumulateOwn = { stages += "own" },
                accumulatePublic = { stages += "public" },
            )
        }
        runCurrent()
        try {
            assertEquals(listOf("own", "public", "probe"), stages)
        } finally {
            media.complete(Unit)
            job.join()
        }
        assertEquals(listOf("own", "public", "probe", "media-done"), stages)
    }
}
