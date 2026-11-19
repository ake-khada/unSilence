package com.unsilence.app.ui.feed

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.data.model.VideoRenderModel
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoThumbnailGeometryTest {
    @get:Rule val compose = createComposeRule()

    // One synthetic 3840×2160 H.264 test-pattern frame in the instrumentation APK.
    private fun withVideo(block: (File, VideoThumbnailCache) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("thumbnail-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("thumbnail-4k.mp4").use { input ->
                file.outputStream().use(input::copyTo)
            }
            block(file, VideoThumbnailCache(context, OkHttpClient()))
        } finally {
            file.delete()
        }
    }

    @Test fun fourKPosterFitsCardAndExpandsForViewer() = withVideo { file, cache ->
        val model = VideoRenderModel(file.path, 16f / 9, null, 3840, 2160)
        val surface = mutableStateOf(0)
        val measuredSize = AtomicReference(IntSize.Zero)
        compose.setContent {
            val measured = Modifier.onSizeChanged(measuredSize::set)
            when (surface.value) {
                0, 1 -> Box(Modifier.fillMaxWidth(if (surface.value == 0) 0.40f else 0.85f)) {
                    VideoPreviewCard(model, {}, measured, thumbnailCache = cache)
                }
                else -> Box(Modifier.fillMaxSize()) {
                    VideoThumbnailImage(model, cache, Modifier.fillMaxSize().then(measured))
                }
            }
        }
        val widths = mutableListOf<Pair<Int, Int>>()
        for (step in 0..2) {
            val previous = widths.lastOrNull()?.first ?: 0
            compose.runOnIdle { surface.value = step }
            compose.waitUntil(15_000) { measuredSize.get().width > previous && cache.getCached(file.path) != null }
            // A smaller cached frame may remain visible until the larger decode completes.
            compose.waitUntil(15_000) { cache.getCached(file.path)!!.bitmap.width >= minOf(measuredSize.get().width, 1920) - 2 }
            val size = measuredSize.get()
            val frame = cache.getCached(file.path)!!.bitmap
            Log.i("ThumbnailGeometry", "surface=$step box=${size.width}x${size.height} frame=${frame.width}x${frame.height} bytes=${frame.allocationByteCount}")
            widths += size.width to frame.width
            assertEquals(16f / 9, cache.resolvedAspectRatios[file.path]!!, 0.02f)
            assertEquals(1, cache.entryCount)
        }
        for ((box, frame) in widths) {
            assertTrue("Frame width $frame exceeds displayed width $box", frame <= box + 2)
        }
    }

    @Test fun warmFrameIsReusedAndLargerViewerReplacesOnlyThatEntry() = withVideo { file, cache ->
        runBlocking {
            cache.warmThumbnail(file.path)
            val warm = checkNotNull(cache.getCached(file.path))
            val small = ThumbnailTarget(IntSize(250, 180))
            assertSame(warm, cache.getThumbnail(file.path, small))
            val large = checkNotNull(cache.getThumbnail(file.path, ThumbnailTarget(IntSize(3840, 2160))))
            assertEquals(1920, large.bitmap.width)
            assertEquals(1080, large.bitmap.height)
            assertSame(large, cache.getThumbnail(file.path, small))
            assertEquals(large.bitmap.allocationByteCount.toLong(), cache.bitmapBytes)
            assertEquals(1, cache.entryCount)
        }
    }

    @Test fun rotatedPortraitUsesTheDisplayedOrientation() = withVideo { file, cache ->
        val portrait = File.createTempFile("thumbnail-rotated-", ".mp4", file.parentFile)
        try {
            writeRotatedCopy(file, portrait)
            val frame = runBlocking {
                checkNotNull(cache.getThumbnail(portrait.path, ThumbnailTarget(IntSize(360, 900))))
            }
            assertEquals(360, frame.bitmap.width)
            assertEquals(640, frame.bitmap.height)
            assertEquals(9f / 16, frame.aspectRatio, 0.01f)
        } finally {
            portrait.delete()
        }
    }

    @Test fun viewerJoinsSmallInFlightRequestAndUpgradesOnce() = runBlocking {
        val small = ThumbnailTarget(IntSize(320, 180))
        val viewer = ThumbnailTarget(IntSize(960, 540))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = mutableListOf<ThumbnailTarget>()
        val source = IntSize(3840, 2160)
        val cache = VideoThumbnailCache(VideoThumbnailFrameExtractor { _, target ->
            synchronized(requests) { requests.add(target) }
            if (target == small) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val size = target.frameSize(source)
            VideoThumbnail(Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888), sourceSize = source, decodedFor = size)
        }, { small })
        val url = "/local/fixture"
        val warm = async(start = CoroutineStart.UNDISPATCHED) { cache.warmThumbnail(url) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.getThumbnail(url, viewer) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.getThumbnail(url, viewer) }
            release.countDown()
            val upgraded = checkNotNull(first.await())
            assertSame(upgraded, second.await())
            assertEquals(960, upgraded.bitmap.width)
            warm.await()
            assertEquals(listOf(small, viewer), requests)
            assertEquals(1, cache.entryCount)
        } finally {
            release.countDown()
        }
    }

    @Test fun prefetchJoiningAVisibleSmallDecodeDoesNotRequestMorePixels() = runBlocking {
        val small = ThumbnailTarget(IntSize(160, 90))
        val window = ThumbnailTarget(IntSize(1080, 2400))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = mutableListOf<ThumbnailTarget>()
        val source = IntSize(3840, 2160)
        val cache = VideoThumbnailCache(VideoThumbnailFrameExtractor { _, target ->
            requests.add(target)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            val size = target.frameSize(source)
            VideoThumbnail(Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888), sourceSize = source, decodedFor = size)
        }, { window })
        val url = "/local/small"
        val visible = async(start = CoroutineStart.UNDISPATCHED) { cache.getThumbnail(url, small) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val warm = async(start = CoroutineStart.UNDISPATCHED) { cache.warmThumbnail(url) }
            release.countDown()
            val initial = visible.await()
            warm.await()
            assertSame(initial, cache.getCached(url))
            assertEquals(listOf(small), requests)
        } finally {
            release.countDown()
        }
    }

    private fun writeRotatedCopy(input: File, output: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(input.path)
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            val track = muxer.addTrack(format)
            muxer.setOrientationHint(90)
            muxer.start()
            val buffer = ByteBuffer.allocate(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            val count = extractor.readSampleData(buffer, 0)
            val info = MediaCodec.BufferInfo().apply { set(0, count, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
            muxer.writeSampleData(track, buffer, info)
            muxer.stop()
        } finally {
            muxer.release()
            extractor.release()
        }
    }
}
