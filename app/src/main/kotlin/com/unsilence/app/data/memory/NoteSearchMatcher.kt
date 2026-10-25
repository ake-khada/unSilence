package com.unsilence.app.data.memory

/**
 * Literal, character-case-insensitive substring matching without copying either
 * body. Whole-string lowercase on every store signal creates body-sized JVM and
 * ICU/native temporaries even for non-matches. Keep matching on the originals;
 * do not add a second store of normalized article/note bodies.
 *
 * Uses the standard ignore-case comparison, not Unicode normalization or full
 * case-fold expansion (e.g. "ß" does not become "ss").
 */
internal fun containsNoteSearchText(content: String, query: String): Boolean =
    content.contains(query, ignoreCase = true)
