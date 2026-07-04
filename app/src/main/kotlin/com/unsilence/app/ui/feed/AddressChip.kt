package com.unsilence.app.ui.feed

import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import kotlinx.coroutines.flow.StateFlow

/**
 * Tappable inline card for an addressable event (naddr: kind + pubkey + d-tag).
 *
 * Resolves the author profile and shows kind label + d-tag title.
 * Rendered for [Segment.QuoteAddress] entries in the segment list.
 */
@Composable
internal fun AddressChip(
    segment: Segment.QuoteAddress,
    onNoteClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    modifier: Modifier = Modifier,
) {
    val lookedUpAuthor by produceState<UserEntity?>(null, segment.author) {
        if (lookupProfile != null) value = lookupProfile(segment.author)
    }
    val liveAuthor = profileFlow?.invoke(segment.author)
        ?.collectAsStateWithLifecycle()?.value
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
            Text(
                text       = kindLabel,
                color      = Brand,
                fontSize   = AppType.footnote,
                fontWeight = FontWeight.Medium,
            )
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
        }
    }
}
