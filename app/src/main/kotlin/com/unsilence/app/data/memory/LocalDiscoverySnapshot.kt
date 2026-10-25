package com.unsilence.app.data.memory

internal const val DISCOVERY_INDEX_SCAN_LIMIT = 1_000
internal const val DISCOVERY_NOTE_SCAN_LIMIT = 500

/** Ephemeral, bounded input for Search; never an account-independent ranking cache. */
internal data class LocalDiscoverySnapshot(
    val hashtags: List<Pair<String, Int>> = emptyList(),
    val users: List<UserEntity> = emptyList(),
    val indexEntriesExamined: Int = 0,
    val notesExamined: Int = 0,
)
