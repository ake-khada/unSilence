package com.unsilence.app.domain.model

/** Device-wide automatic-media preference; names are persisted, not ordinal positions. */
enum class AutomaticMediaMode {
    ALWAYS,
    UNMETERED_ONLY,
    NEVER;

    companion object {
        fun fromStoredName(value: String?): AutomaticMediaMode =
            entries.firstOrNull { it.name == value } ?: ALWAYS
    }
}

/** A single DataStore emission keeps all three device choices consistent. */
data class AutomaticMediaPreferences(
    val video: AutomaticMediaMode = AutomaticMediaMode.ALWAYS,
    val images: AutomaticMediaMode = AutomaticMediaMode.ALWAYS,
    val previews: AutomaticMediaMode = AutomaticMediaMode.ALWAYS,
)
