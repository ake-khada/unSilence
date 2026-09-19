package com.unsilence.app.ui.shared

/** Delivered to the current editor, never a callback captured by a disposed composition. */
internal sealed interface EditorImageResult {
    data class Uploaded(val url: String, val isBanner: Boolean = false) : EditorImageResult
    data class Failed(val message: String) : EditorImageResult
}
