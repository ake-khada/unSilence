package com.unsilence.app.data.model

/** Shared coordinate derivation for replaceable/addressable event actions. */
internal fun eventAddressableCoordinate(
    kind: Int,
    pubkey: String,
    dTag: String?,
): String? {
    if (kind !in 10000..39999) return null
    val parameter = if (kind in 30000..39999) dTag.orEmpty() else ""
    return "$kind:$pubkey:$parameter"
}
