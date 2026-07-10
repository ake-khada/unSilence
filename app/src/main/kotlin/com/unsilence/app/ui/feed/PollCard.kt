package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.model.PollInfo
import com.unsilence.app.ui.shared.PollActionCallbacks
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@androidx.compose.runtime.Immutable
data class PollVoteRequest(
    val pollId: String,
    val pollAuthorPubkey: String,
    val selectedOptionIds: Set<String>,
    val validOptionIds: Set<String>,
    val multipleChoice: Boolean,
    val responseRelays: List<String>,
    val sourceRelay: String,
    val endsAt: Long?,
)

internal data class PollTally(
    val counts: Map<String, Int>,
    val totalVotes: Int,
    val ownChoices: Set<String>,
    val ownResponseId: String?,
)

internal fun tallyPollVotes(
    responses: List<NostrEvent>,
    poll: PollInfo,
    pollCreatedAt: Long,
    ownPubkey: String?,
): PollTally {
    val optionIds = poll.options.mapTo(linkedSetOf()) { it.id }
    fun choices(event: NostrEvent): Set<String> {
        val ids = event.tags.asSequence()
            .filter { it.size >= 2 && it[0] == "response" && it[1] in optionIds }
            .map { it[1] }
            .distinct()
            .toList()
        return (if (poll.multipleChoice) ids else ids.take(1)).toSet()
    }

    val latest = responses.asSequence()
        .filter { it.createdAt >= pollCreatedAt && (poll.endsAt == null || it.createdAt <= poll.endsAt) }
        .groupBy { it.pubkey }
        .mapValues { (_, events) -> events.maxWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id }) }
        .values
        .mapNotNull { response -> choices(response).takeIf { it.isNotEmpty() }?.let { response to it } }
        .toList()
    val counts = poll.options.associate { it.id to 0 }.toMutableMap()
    latest.forEach { (_, selected) ->
        selected.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
    }
    val own = latest.firstOrNull { (response, _) -> response.pubkey == ownPubkey }
    return PollTally(
        counts = counts,
        totalVotes = latest.size,
        ownChoices = own?.second.orEmpty(),
        ownResponseId = own?.first?.id,
    )
}

@Composable
fun PollCard(
    pollId: String,
    pollAuthorPubkey: String,
    pollCreatedAt: Long,
    poll: PollInfo,
    sourceRelay: String,
    callbacks: PollActionCallbacks?,
    modifier: Modifier = Modifier,
) {
    val responseRelays = remember(poll.responseRelays, sourceRelay) {
        poll.responseRelays.ifEmpty { listOf(sourceRelay) }.filter { it.isNotBlank() }.distinct().take(6)
    }
    val responses by produceState<List<NostrEvent>>(emptyList(), pollId, callbacks) {
        callbacks?.responses?.invoke(pollId)?.collectLatest { value = it }
    }
    LaunchedEffect(pollId, responseRelays, poll.endsAt, callbacks) {
        callbacks?.load?.invoke(pollId, responseRelays, poll.endsAt)
    }

    val optionIds = remember(poll.options) { poll.options.mapTo(linkedSetOf()) { it.id } }
    val tally = remember(responses, poll, pollCreatedAt, callbacks?.currentPubkey) {
        tallyPollVotes(responses, poll, pollCreatedAt, callbacks?.currentPubkey)
    }
    var selected by remember(pollId) { mutableStateOf(emptySet<String>()) }
    var resultsRevealed by remember(pollId) { mutableStateOf(false) }
    LaunchedEffect(tally.ownResponseId) {
        if (tally.ownResponseId != null) selected = tally.ownChoices
    }
    val counts = tally.counts
    val totalVotes = tally.totalVotes
    val nowSeconds by produceState(System.currentTimeMillis() / 1000L, poll.endsAt) {
        while (poll.endsAt != null && value <= poll.endsAt) {
            delay(60_000L)
            value = System.currentTimeMillis() / 1000L
        }
    }
    val ended = poll.endsAt?.let { nowSeconds > it } == true
    val interactive = callbacks?.currentPubkey != null && !ended
    val showResults = resultsRevealed || tally.ownResponseId != null || ended || !interactive

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        poll.options.forEach { option ->
            val checked = option.id in selected
            val count = counts[option.id] ?: 0
            val fraction = if (totalVotes == 0) 0f else count.toFloat() / totalVotes
            val shape = RoundedCornerShape(6.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (checked) BrandDeep.copy(alpha = 0.12f) else SurfaceVariant)
                    .border(1.dp, if (checked) BrandDeep.copy(alpha = 0.75f) else BorderFaint, shape)
                    .toggleable(
                        value = checked,
                        enabled = interactive,
                        role = if (poll.multipleChoice) Role.Checkbox else Role.RadioButton,
                        onValueChange = { value ->
                            selected = if (poll.multipleChoice) {
                                if (value) selected + option.id else selected - option.id
                            } else {
                                if (value) setOf(option.id) else emptySet()
                            }
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(1.5.dp, if (checked) BrandDeep else TextSecondary, if (poll.multipleChoice) RoundedCornerShape(4.dp) else CircleShape)
                            .background(if (checked) BrandDeep else Color.Transparent, if (poll.multipleChoice) RoundedCornerShape(4.dp) else CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) {
                            Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(13.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = option.label,
                        color = Color.White,
                        fontSize = AppType.body,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showResults) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${(fraction * 100).roundToInt()}%",
                            color = TextSecondary,
                            fontSize = AppType.caption,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (showResults) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(BorderFaint, CircleShape)) {
                        Box(
                            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp)
                                .background(BrandDeep, CircleShape),
                        )
                    }
                }
            }
        }

        if (interactive && tally.ownResponseId == null && !resultsRevealed) {
            TextButton(
                onClick = { resultsRevealed = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = "Show results",
                    color = BrandDeep,
                    fontSize = AppType.caption,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(totalVotes)
                    append(if (totalVotes == 1) " vote" else " votes")
                    if (poll.multipleChoice) append("  Multiple choice")
                    if (ended) append("  Ended")
                    else poll.endsAt?.let {
                        val remaining = (it - nowSeconds).coerceAtLeast(0L)
                        append("  Ends in ")
                        append(when {
                            remaining < 3_600L -> "${(remaining / 60L).coerceAtLeast(1L)}m"
                            remaining < 86_400L -> "${(remaining / 3_600L).coerceAtLeast(1L)}h"
                            else -> "${(remaining / 86_400L).coerceAtLeast(1L)}d"
                        })
                    }
                },
                color = TextSecondary,
                fontSize = AppType.caption,
                modifier = Modifier.weight(1f),
            )
            if (interactive) {
                Button(
                    onClick = {
                        callbacks.vote(PollVoteRequest(
                            pollId = pollId,
                            pollAuthorPubkey = pollAuthorPubkey,
                            selectedOptionIds = selected,
                            validOptionIds = optionIds,
                            multipleChoice = poll.multipleChoice,
                            responseRelays = poll.responseRelays,
                            sourceRelay = sourceRelay,
                            endsAt = poll.endsAt,
                        ))
                    },
                    enabled = selected.isNotEmpty() && selected != tally.ownChoices,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandDeep,
                        contentColor = Color.Black,
                        disabledContainerColor = SurfaceVariant,
                        disabledContentColor = TextSecondary,
                    ),
                ) {
                    Text(if (tally.ownResponseId == null) "Vote" else "Update", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
