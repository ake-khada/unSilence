package com.unsilence.app.data.model

/**
 * Parse limits shared across content renderers (note tokenizer + native markdown).
 * Only the genuinely-universal ones live here; per-renderer caps (note MAX_SEGMENTS,
 * markdown per-block/total-block) stay with their renderer.
 */
internal object ParseLimits {
    /** Input character cap for long-form (kind-30023) before the O(content) parse pass. */
    const val MAX_ARTICLE_PARSE_CHARS = 200_000

    /** Tail marker appended when content/structure is capped (spam-post DoS bound). */
    const val TRUNCATION_MARKER = "… [content truncated]"
}
