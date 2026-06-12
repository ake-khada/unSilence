package com.unsilence.app.ui.relays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3

/**
 * Relay avatar. Shows the operator's NIP-11 `icon` when present; otherwise a SINGLE preset
 * relay glyph — deliberately NOT an identicon (relays are servers, not people, so a
 * per-pubkey identicon misrepresents them). The glyph sits BEHIND the image, so a relay with
 * a genuinely-absent icon and one whose icon URL simply fails to load both degrade to the same
 * honest dummy rather than a blank/broken box.
 */
@Composable
internal fun RelayIcon(iconUrl: String?, modifier: Modifier) {
    Box(modifier = modifier.clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Dns, contentDescription = null, tint = Text3, modifier = Modifier.fillMaxSize(0.5f))
        if (!iconUrl.isNullOrBlank()) {
            AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
    }
}
