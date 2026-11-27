package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.WotScoreFilter
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.StateFlow

internal class WotReplyFilter(
    val policy: WotScoreFilter?,
    private val scores: Map<String, WotLookup>,
    private val revealed: Set<String>,
    val reveal: (String) -> Unit,
) {
    fun awaitingScore(row: FeedRow) = (scores[row.pubkey] ?: WotLookup.Pending) == WotLookup.Pending

    fun hides(row: FeedRow) = policy == null || (!policy.admits(scores[row.pubkey]) && row.id !in revealed)
}

@Composable
internal fun rememberWotReplyFilter(
    scope: String,
    settings: StateFlow<WotScoreFilter?>,
    scores: Map<String, WotLookup>,
): WotReplyFilter {
    val policy by settings.collectAsStateWithLifecycle()
    val lookups = if (policy?.enabled == true) scores else emptyMap()
    var revealed by rememberSaveable(LocalAppSessionKey.current, scope, policy) { mutableStateOf(emptySet<String>()) }
    return remember(policy, lookups, revealed) { WotReplyFilter(policy, lookups, revealed) { revealed += it } }
}

@Composable
internal fun WotHiddenReply(row: FeedRow, filter: WotReplyFilter) {
    val policy = filter.policy ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clip(RoundedCornerShape(8.dp)).background(SurfaceVariant)
            .clickable(role = Role.Button, onClickLabel = "Reveal this post") { filter.reveal(row.id) }
            .padding(Spacing.medium),
    ) {
        val reason = if (filter.awaitingScore(row)) "No current WoT score" else "Hidden by your WoT filter"
        Text("$reason · Minimum score ${policy.minimum}", style = AppTextStyles.footnote, color = TextSecondary)
        Text("Tap to reveal", style = AppTextStyles.footnote, color = Brand)
    }
}
