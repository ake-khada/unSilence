package com.unsilence.app.data.blossom

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/** Shared bounded image-upload path used by profile and relay-set metadata editors. */
@Singleton
class BlossomImageUploader @Inject constructor(
    private val blossomClient: BlossomClient,
    private val imageCompressor: ImageCompressor,
    private val blossomServersStore: BlossomServersStore,
) {
    suspend fun upload(
        uri: Uri,
        maxDimension: Int,
        quality: Int = 85,
    ): String {
        blossomServersStore.initialize()
        val server = blossomServersStore.selectedServer.value
            .takeIf { it.isNotBlank() }
            ?: error("No Blossom server configured")
        val prepared = imageCompressor.prepareImage(uri, "image/jpeg", maxDimension, quality)
        return try {
            blossomClient.upload(prepared.file, prepared.mimeType, server).getOrThrow().url
        } finally {
            prepared.file.delete()
        }
    }
}
