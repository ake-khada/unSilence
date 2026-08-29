package com.unsilence.app.data.media

import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options

/**
 * Keeps animated decodes off Android's file-backed [android.graphics.ImageDecoder.Source].
 *
 * Coil normally prefers the disk-cache file directly. Android can retain the resulting
 * FileInputStream until finalization when an animated decode is cancelled, producing sustained
 * closeable leaks while scrolling. Re-wrapping the same compressed stream without file metadata
 * makes [AnimatedImageDecoder] use its direct-byte-buffer path instead. The decoder still owns and
 * closes the stream, while GIF, animated WebP, and animated HEIF support remain intact.
 */
@RequiresApi(28)
internal class DirectBufferAnimatedImageDecoderFactory : Decoder.Factory {
    private val formatDetector = AnimatedImageDecoder.Factory()

    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        // Keep Coil's canonical header sniffing. The returned stock decoder is deliberately
        // replaced before any decode begins, so it never opens the file-backed source.
        if (formatDetector.create(result, options, imageLoader) == null) return null

        return AnimatedImageDecoder(
            source = directBufferAnimatedSource(result.source),
            options = options,
        )
    }
}

/** Strip file/URI metadata so Coil cannot choose ImageDecoder's leaking file-source path. */
internal fun directBufferAnimatedSource(source: ImageSource): ImageSource =
    ImageSource(
        source = source.source(),
        fileSystem = source.fileSystem,
    )
