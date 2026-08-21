package com.unsilence.app.ui.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class AddressResolutionState { IDLE, RESOLVING, FAILED }

/** One resolver phase is bounded at 8s; this margin includes processor settle
 *  while preventing later ladder phases from extending the chip's pending UI. */
private const val ADDRESS_RESOLUTION_TIMEOUT_MS = 9_000L
private const val ADDRESS_FAILURE_VISIBLE_MS = 1_800L

/**
 * Tappable fallback card for an addressable event (naddr: kind + pubkey + d-tag).
 *
 * Resolves the author profile eagerly as before, but resolves the event itself
 * only after an explicit tap. Rendered for [Segment.QuoteAddress] entries.
 */
@Composable
internal fun AddressChip(
    segment: Segment.QuoteAddress,
    onNoteClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    modifier: Modifier = Modifier,
) {
    val actionsVm: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    )
    val coroutineScope = rememberCoroutineScope()
    val target = remember(segment.kind, segment.author, segment.dTag, segment.hints) {
        EventReferenceTarget(
            eventId = null,
            address = EventAddressReference(segment.kind, segment.author, segment.dTag),
            authorPubkey = segment.author,
            relayHints = segment.hints,
        )
    }
    var resolutionState by remember(target.lookupKey) {
        mutableStateOf(AddressResolutionState.IDLE)
    }
    LaunchedEffect(resolutionState) {
        if (resolutionState == AddressResolutionState.FAILED) {
            delay(ADDRESS_FAILURE_VISIBLE_MS)
            if (resolutionState == AddressResolutionState.FAILED) {
                resolutionState = AddressResolutionState.IDLE
            }
        }
    }

    val lookedUpAuthor by produceState<UserEntity?>(null, segment.author) {
        if (lookupProfile != null) value = lookupProfile(segment.author)
    }
    val liveAuthor = collectProfileAsState(segment.author, profileFlow)
    val author = liveAuthor ?: lookedUpAuthor

    val kindLabel = when (segment.kind) {
        30023 -> "Article"
        30024 -> "Draft"
        30078 -> "App Data"
        30311 -> "Live Event"
        else  -> "Event (kind ${segment.kind})"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                if (resolutionState == AddressResolutionState.RESOLVING) return@clickable
                resolutionState = AddressResolutionState.RESOLVING
                coroutineScope.launch {
                    val event = try {
                        withTimeoutOrNull(ADDRESS_RESOLUTION_TIMEOUT_MS) {
                            actionsVm.lookupEvent(target)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (event != null) {
                        resolutionState = AddressResolutionState.IDLE
                        onNoteClick(event.id)
                    } else {
                        resolutionState = AddressResolutionState.FAILED
                    }
                }
            }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(
                    pubkey   = segment.author,
                    picture  = author?.picture,
                    modifier = Modifier.size(24.dp),
                    sizeDp   = 24.dp,
                    lookupProfile = lookupProfile,
                    profileFlow = profileFlow,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text     = author?.displayName?.takeIf { it.isNotBlank() }
                        ?: author?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                        ?: "${segment.author.take(6)}…${segment.author.takeLast(4)}",
                    color    = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = kindLabel,
                    color      = Brand,
                    fontSize   = AppType.footnote,
                    fontWeight = FontWeight.Medium,
                )
                if (resolutionState == AddressResolutionState.RESOLVING) {
                    Spacer(Modifier.width(Spacing.small))
                    CircularProgressIndicator(
                        color = Brand,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            if (segment.dTag.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = segment.dTag.replace("-", " "),
                    color      = Color.White.copy(alpha = 0.7f),
                    fontSize   = AppType.body,
                    lineHeight = 18.sp,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
            when (resolutionState) {
                AddressResolutionState.RESOLVING -> {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Loading…",
                        color = TextSecondary,
                        fontSize = AppType.footnote,
                    )
                }
                AddressResolutionState.FAILED -> {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Couldn't load",
                        color = TextSecondary,
                        fontSize = AppType.footnote,
                        fontWeight = FontWeight.Medium,
                    )
                }
                AddressResolutionState.IDLE -> Unit
            }
        }
    }
}
