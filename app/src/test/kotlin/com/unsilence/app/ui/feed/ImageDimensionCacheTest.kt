package com.unsilence.app.ui.feed

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ImageDimensionCacheTest {

    private lateinit var cache: ImageDimensionCache

    @Before
    fun setUp() {
        cache = ImageDimensionCache(OkHttpClient())
    }

    // ── Normal ratios preserved ───────────────────────────────────────────

    @Test
    fun `normal landscape ratio is preserved`() {
        cache.put("https://example.com/landscape.jpg", 1.78f) // ~16:9
        assertEquals(1.78f, cache.getCached("https://example.com/landscape.jpg")!!, 0.001f)
    }

    @Test
    fun `normal portrait ratio is preserved`() {
        cache.put("https://example.com/portrait.jpg", 0.75f) // 3:4
        assertEquals(0.75f, cache.getCached("https://example.com/portrait.jpg")!!, 0.001f)
    }

    @Test
    fun `square ratio is preserved`() {
        cache.put("https://example.com/square.jpg", 1.0f)
        assertEquals(1.0f, cache.getCached("https://example.com/square.jpg")!!, 0.001f)
    }

    // ── Extreme ratios clamped ────────────────────────────────────────────

    @Test
    fun `extreme tall ratio is clamped to 0_2`() {
        // 1:100 → width/height = 0.01, should clamp to 0.2
        cache.put("https://example.com/tall.jpg", 0.01f)
        assertEquals(0.2f, cache.getCached("https://example.com/tall.jpg")!!, 0.001f)
    }

    @Test
    fun `extreme wide ratio is clamped to 5_0`() {
        // 100:1 → width/height = 100.0, should clamp to 5.0
        cache.put("https://example.com/wide.jpg", 100.0f)
        assertEquals(5.0f, cache.getCached("https://example.com/wide.jpg")!!, 0.001f)
    }

    // ── Boundary values ──────────────────────────────────────────────────

    @Test
    fun `ratio at lower boundary is preserved`() {
        cache.put("https://example.com/at-min.jpg", 0.2f)
        assertEquals(0.2f, cache.getCached("https://example.com/at-min.jpg")!!, 0.001f)
    }

    @Test
    fun `ratio at upper boundary is preserved`() {
        cache.put("https://example.com/at-max.jpg", 5.0f)
        assertEquals(5.0f, cache.getCached("https://example.com/at-max.jpg")!!, 0.001f)
    }

    @Test
    fun `ratio just below lower boundary is clamped`() {
        cache.put("https://example.com/below-min.jpg", 0.19f)
        assertEquals(0.2f, cache.getCached("https://example.com/below-min.jpg")!!, 0.001f)
    }

    @Test
    fun `ratio just above upper boundary is clamped`() {
        cache.put("https://example.com/above-max.jpg", 5.01f)
        assertEquals(5.0f, cache.getCached("https://example.com/above-max.jpg")!!, 0.001f)
    }

    // ── Zero and negative ratios rejected ────────────────────────────────

    @Test
    fun `zero ratio is not stored`() {
        cache.put("https://example.com/zero.jpg", 0.0f)
        assertNull(cache.getCached("https://example.com/zero.jpg"))
    }

    @Test
    fun `negative ratio is not stored`() {
        cache.put("https://example.com/negative.jpg", -1.0f)
        assertNull(cache.getCached("https://example.com/negative.jpg"))
    }

    @Test
    fun `URL fragments share one dimension entry`() {
        cache.put("https://example.com/image.jpg#first", 1.5f)
        assertEquals(1.5f, cache.getCached("https://example.com/image.jpg#second")!!, 0.001f)
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun `dimension cache stays bounded`() {
        repeat(513) { index -> cache.put("https://example.com/$index.jpg", 1f) }
        assertEquals(512, cache.entryCount)
        assertNull(cache.getCached("https://example.com/0.jpg"))
        assertEquals(1f, cache.getCached("https://example.com/512.jpg")!!, 0.001f)
    }
}
