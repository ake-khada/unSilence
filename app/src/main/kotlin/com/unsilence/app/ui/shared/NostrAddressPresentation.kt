package com.unsilence.app.ui.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.network.Nip05VerificationStatus
import com.unsilence.app.ui.common.LocalNip05VerificationController
import com.unsilence.app.ui.feed.parseNip05
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.TextSecondary

internal enum class NostrAddressDisplay {
    FULL,
    DOMAIN,
}

internal data class NostrAddressPresentation(
    val claimedAddress: String,
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
    val parsed = parseNip05(trimmed)
    val visibleText = when (display) {
        NostrAddressDisplay.FULL -> {
            if (parsed?.first == "_") parsed.second else trimmed
        }
        NostrAddressDisplay.DOMAIN -> parsed?.second ?: return null
    }
    return NostrAddressPresentation(
        claimedAddress = trimmed,
        visibleText = visibleText,
        accessibilityLabel = "Nostr address: $trimmed",
    )
}

internal fun shouldShowNip05VerifiedBadge(status: Nip05VerificationStatus): Boolean =
    status == Nip05VerificationStatus.VERIFIED

internal fun nip05AccessibilityLabel(
    presentation: NostrAddressPresentation,
    status: Nip05VerificationStatus,
): String = if (shouldShowNip05VerifiedBadge(status)) {
    "Verified Nostr address: ${presentation.claimedAddress}"
} else {
    presentation.accessibilityLabel
}

/** The sole rendering seam for a self-declared Nostr address and its verified state. */
@Composable
internal fun SelfDeclaredNostrAddressText(
    pubkey: String,
    presentation: NostrAddressPresentation,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val verifier = LocalNip05VerificationController.current
    val statusFlow = remember(verifier, pubkey, presentation.claimedAddress) {
        verifier.verificationFlow(pubkey, presentation.claimedAddress)
    }
    val status by statusFlow.collectAsStateWithLifecycle(
        initialValue = Nip05VerificationStatus.UNKNOWN,
    )
    LaunchedEffect(verifier, pubkey, presentation.claimedAddress) {
        verifier.requestIfEligible(pubkey, presentation.claimedAddress)
    }
    val verified = shouldShowNip05VerifiedBadge(status)

    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = nip05AccessibilityLabel(presentation, status)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (verified) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
                tint = Brand,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text = presentation.visibleText,
            color = TextSecondary,
            fontSize = fontSize,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}
