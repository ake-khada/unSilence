package com.unsilence.app.ui.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.unsilence.app.ui.feed.parseNip05
import com.unsilence.app.ui.theme.TextSecondary

internal enum class NostrAddressDisplay {
    FULL,
    DOMAIN,
}

internal data class NostrAddressPresentation(
    val visibleText: String,
    val accessibilityLabel: String,
)

/**
 * Formats a profile's self-declared NIP-05 value without implying that the
 * address has been resolved or matched to the profile pubkey.
 */
internal fun selfDeclaredNostrAddressPresentation(
    nip05: String?,
    display: NostrAddressDisplay,
): NostrAddressPresentation? {
    val trimmed = nip05?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val visibleText = when (display) {
        NostrAddressDisplay.FULL -> trimmed
        NostrAddressDisplay.DOMAIN -> parseNip05(trimmed)?.second ?: return null
    }
    return NostrAddressPresentation(
        visibleText = visibleText,
        accessibilityLabel = "Nostr address: $trimmed",
    )
}

/** Neutral presentation for a self-declared Nostr address. */
@Composable
internal fun SelfDeclaredNostrAddressText(
    presentation: NostrAddressPresentation,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = presentation.visibleText,
        color = TextSecondary,
        fontSize = fontSize,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = presentation.accessibilityLabel
        },
    )
}
