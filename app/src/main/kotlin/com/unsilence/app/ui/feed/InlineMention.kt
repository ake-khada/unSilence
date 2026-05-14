package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Surface1
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

/**
 * Standalone inline mention chip for a nostr pubkey.
 *
 * Shows display name when available, npub fallback otherwise.
 * Used for rendering mentions outside text context (e.g. notifications).
 */
@Composable
internal fun InlineMention(
    pubkeyHex: String,
    onAuthorClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    modifier: Modifier = Modifier,
) {
    val profile by produceState<UserEntity?>(null, pubkeyHex) {
        if (lookupProfile != null) value = lookupProfile(pubkeyHex)
    }

    val npubFallback = remember(pubkeyHex) {
        val npub = runCatching { NPub.create(pubkeyHex) }.getOrNull()
        if (npub != null) "@${npub.take(16)}…" else "@${pubkeyHex.take(8)}…"
    }
    val displayText = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
        ?: npubFallback

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .clickable { onAuthorClick(pubkeyHex) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text     = if (displayText.startsWith("@")) displayText else "@$displayText",
            color    = Brand,
            fontSize = AppType.footnote,
        )
    }
}
