package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.PollInfo
import com.unsilence.app.ui.shared.PollActionCallbacks
import com.unsilence.app.ui.shared.wotTierColor
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Text3
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
    val latestResponses: List<PollVoterResponse>,
)

internal data class PollVoterResponse(
    val response: NostrEvent,
    val choices: Set<String>,
)

internal data class PollVoterGroup(
    val optionId: String,
    val optionLabel: String,
    val percentage: Int,
    val voters: List<NostrEvent>,
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
        .mapNotNull { response ->
            choices(response).takeIf { it.isNotEmpty() }?.let { PollVoterResponse(response, it) }
        }
        .sortedWith(compareByDescending<PollVoterResponse> { it.response.createdAt }.thenByDescending { it.response.id })
        .toList()
    val counts = poll.options.associate { it.id to 0 }.toMutableMap()
    latest.forEach { (_, selected) ->
        selected.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
    }
    val own = latest.firstOrNull { (response, _) -> response.pubkey == ownPubkey }
    return PollTally(
        counts = counts,
        totalVotes = latest.size,
        ownChoices = own?.choices.orEmpty(),
        ownResponseId = own?.response?.id,
        latestResponses = latest,
    )
}

internal fun pollVoterGroups(poll: PollInfo, tally: PollTally): List<PollVoterGroup> =
    poll.options.map { option ->
        val voters = tally.latestResponses
            .filter { option.id in it.choices }
            .map { it.response }
        PollVoterGroup(
            optionId = option.id,
            optionLabel = option.label,
            percentage = if (tally.totalVotes == 0) 0 else
                ((voters.size.toFloat() / tally.totalVotes) * 100).roundToInt(),
            voters = voters,
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
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    onVoterClick: (String) -> Unit = {},
    onWotSubjectsVisible: (Collection<String>) -> Unit = {},
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
    var showVoters by remember(pollId) { mutableStateOf(false) }
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
    LaunchedEffect(tally.latestResponses) {
        onWotSubjectsVisible(tally.latestResponses.map { it.response.pubkey }.toSet())
    }

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
                    append(if (totalVotes == 1) " voter" else " voters")
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
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = totalVotes > 0 && showResults) { showVoters = true }
                    .padding(vertical = 8.dp),
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

    if (showVoters) {
        PollVotersSheet(
            groups = pollVoterGroups(poll, tally),
            totalVoters = tally.totalVotes,
            currentPubkey = callbacks?.currentPubkey,
            lookupProfile = lookupProfile,
            profileFlow = profileFlow,
            wotLookup = wotLookup,
            onVoterClick = onVoterClick,
            onDismiss = { showVoters = false },
        )
    }
}

/** Kind-1018 votes are public events; this sheet exposes only their public authors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PollVotersSheet(
    groups: List<PollVoterGroup>,
    totalVoters: Int,
    currentPubkey: String?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    wotLookup: ((String) -> WotLookup?)?,
    onVoterClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "$totalVoters ${if (totalVoters == 1) "voter" else "voters"}",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = BorderFaint)
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                groups.forEach { group ->
                    item(key = "option-${group.optionId}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceVariant)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group.optionLabel,
                                color = Color.White,
                                fontSize = AppType.body,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${group.voters.size} · ${group.percentage}%",
                                color = TextSecondary,
                                fontSize = AppType.caption,
                            )
                        }
                    }
                    items(group.voters, key = { "${group.optionId}-${it.pubkey}" }) { response ->
                        PollVoterRow(
                            pubkey = response.pubkey,
                            isCurrentUser = response.pubkey == currentPubkey,
                            lookupProfile = lookupProfile,
                            profileFlow = profileFlow,
                            wotLookup = wotLookup,
                            onClick = {
                                onVoterClick(response.pubkey)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PollVoterRow(
    pubkey: String,
    isCurrentUser: Boolean,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    wotLookup: ((String) -> WotLookup?)?,
    onClick: () -> Unit,
) {
    val profile = collectProfileAsState(pubkey, profileFlow)
    val label = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: "${pubkey.take(8)}…${pubkey.takeLast(6)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            pubkey = pubkey,
            picture = profile?.picture,
            modifier = Modifier.size(36.dp),
            sizeDp = 36.dp,
            lookupProfile = lookupProfile,
            profileFlow = profileFlow,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = AppType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrentUser) {
            Text(
                text = "you",
                color = BrandDeep,
                fontSize = AppType.caption,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        PollVoterWotNumeral(lookup = wotLookup?.invoke(pubkey))
    }
}

@Composable
private fun PollVoterWotNumeral(lookup: WotLookup?) {
    when (lookup) {
        is WotLookup.Scored -> Text(
            text = lookup.assertion.rank.toString(),
            color = wotTierColor(lookup.assertion.rank),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        WotLookup.Absent -> Text(
            text = "-",
            color = Text3,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        WotLookup.Pending, null -> Unit
    }
}
