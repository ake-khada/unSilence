package com.unsilence.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unsilence.app.data.relay.formatFollowerCount
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LineToWaveLoading
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.shared.WotInlineLabel
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary

private val GraphCardShape = RoundedCornerShape(8.dp)

@Composable
internal fun StartYourGraphScreen(
    state: StartGraphUiState,
    onTogglePack: (String) -> Unit,
    onTogglePerson: (String) -> Unit,
    onPersonVisible: (String) -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onDone)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Start your graph",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone, enabled = !state.publishing) {
                    Text("Skip", color = TextSecondary, fontSize = AppType.body)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    state.loading && state.packs.isEmpty() && state.notablePeople.isEmpty() -> {
                        StartGraphProgress(
                            message = "Finding your people…",
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center),
                        )
                    }
                    state.error != null && state.packs.isEmpty() && state.notablePeople.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(state.error, color = TextSecondary, fontSize = AppType.body)
                            TextButton(onClick = onRetry) { Text("Retry", color = Brand) }
                        }
                    }
                    else -> GraphChoices(
                        state = state,
                        onTogglePack = onTogglePack,
                        onTogglePerson = onTogglePerson,
                        onPersonVisible = onPersonVisible,
                    )
                }
            }

            state.error?.takeIf { state.packs.isNotEmpty() || state.notablePeople.isNotEmpty() }?.let {
                Text(
                    text = it,
                    color = TextSecondary,
                    fontSize = AppType.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.large, vertical = Spacing.micro),
                )
            }
            Button(
                onClick = onDone,
                enabled = !state.publishing,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand,
                    contentColor = Color(0xFF001012),
                    disabledContainerColor = Brand.copy(alpha = 0.45f),
                    disabledContentColor = Color.Black.copy(alpha = 0.65f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.large, vertical = Spacing.small)
                    .height(48.dp),
            ) {
                Text(
                    text = "Done  ·  ${state.selectedFollowCount.coerceAtMost(5)} of 5",
                    fontSize = AppType.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }

        if (state.publishing) {
            StartGraphPublishingOverlay()
        }
    }
}

@Composable
private fun StartGraphPublishingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black.copy(alpha = 0.96f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        StartGraphProgress(
            message = "Building your graph…",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StartGraphProgress(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LineToWaveLoading(
            modifier = Modifier.fillMaxWidth(0.62f),
            height = 48.dp,
            appearDelayMs = 0L,
        )
        Spacer(Modifier.height(Spacing.medium))
        Text(
            text = message,
            color = TextSecondary,
            fontSize = AppType.bodyLarge,
        )
    }
}

@Composable
private fun GraphChoices(
    state: StartGraphUiState,
    onTogglePack: (String) -> Unit,
    onTogglePerson: (String) -> Unit,
    onPersonVisible: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.medium),
    ) {
        item("intro") {
            Text(
                text = "Follow a few people to bring your Following feed to life.",
                color = TextSecondary,
                fontSize = AppType.bodyLarge,
                lineHeight = 21.sp,
                modifier = Modifier.padding(
                    start = Spacing.large,
                    end = Spacing.large,
                    top = Spacing.small,
                    bottom = Spacing.large,
                ),
            )
        }
        if (state.packs.isNotEmpty()) {
            item("packs-label") { GraphSectionLabel("STARTER PACKS") }
            item("packs") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.large),
                ) {
                    items(state.packs, key = StartGraphPackUi::coordinate) { pack ->
                        FollowPackCard(
                            pack = pack,
                            selected = pack.coordinate in state.selectedPackCoordinates,
                            onToggle = { onTogglePack(pack.coordinate) },
                        )
                    }
                }
            }
            item("pack-gap") { Spacer(Modifier.height(Spacing.xl)) }
        }
        if (state.notablePeople.isNotEmpty()) {
            item("people-label") {
                GraphSectionLabel(
                    if (state.notableFromGrapevine) {
                        "NOTABLE IN YOUR GRAPEVINE"
                    } else {
                        "NOTABLE PEOPLE"
                    },
                )
            }
            items(state.notablePeople, key = StartGraphPersonUi::pubkey) { person ->
                LaunchedEffect(person.pubkey) { onPersonVisible(person.pubkey) }
                NotablePersonRow(
                    person = person,
                    selected = person.pubkey in state.selectedPeople,
                    onToggle = { onTogglePerson(person.pubkey) },
                )
            }
        }
    }
}

@Composable
private fun GraphSectionLabel(text: String) {
    Text(
        text = text,
        color = Text3,
        fontSize = AppType.footnote,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(
            start = Spacing.large,
            end = Spacing.large,
            bottom = Spacing.small,
        ),
    )
}

@Composable
private fun FollowPackCard(
    pack: StartGraphPackUi,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(228.dp)
            .heightIn(min = 148.dp)
            .clip(GraphCardShape)
            .background(Surface1)
            .border(
                width = 1.dp,
                color = if (selected) Brand.copy(alpha = 0.55f) else BorderSubtle,
                shape = GraphCardShape,
            )
            .padding(Spacing.medium),
    ) {
        Text(
            text = pack.title,
            color = Color.White,
            fontSize = AppType.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        Spacer(Modifier.height(Spacing.micro))
        Text(
            text = "${pack.memberCount} people",
            color = TextSecondary,
            fontSize = AppType.caption,
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlappingAvatars(pack.topMembers)
            Spacer(Modifier.weight(1f))
            Text(
                text = "Follow all",
                color = if (selected) Brand else TextSecondary,
                fontSize = AppType.caption,
            )
            Spacer(Modifier.width(Spacing.micro))
            GraphSwitch(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun OverlappingAvatars(people: List<StartGraphPersonUi>) {
    Box(modifier = Modifier.width(64.dp).height(28.dp)) {
        people.take(3).forEachIndexed { index, person ->
            GraphAvatar(
                person = person,
                size = 28,
                modifier = Modifier.offset(x = (index * 18).dp),
            )
        }
    }
}

@Composable
private fun NotablePersonRow(
    person: StartGraphPersonUi,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .padding(horizontal = Spacing.large, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GraphAvatar(person = person, size = 44)
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName(person),
                    color = Color.White,
                    fontSize = AppType.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                person.wot?.let { assertion ->
                    Spacer(Modifier.width(Spacing.small))
                    WotInlineLabel(assertion = assertion)
                }
            }
            person.followerCount?.let { count ->
                Text(
                    text = "${formatFollowerCount(count)} followers",
                    color = Text3,
                    fontSize = AppType.caption,
                )
            }
        }
        Spacer(Modifier.width(Spacing.small))
        GraphSwitch(checked = selected, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun GraphSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Black,
            checkedTrackColor = Brand,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = Surface1,
            uncheckedBorderColor = BorderSubtle,
        ),
        modifier = Modifier.size(width = 44.dp, height = 28.dp),
    )
}

@Composable
private fun GraphAvatar(
    person: StartGraphPersonUi,
    size: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Black)
            .border(1.dp, Black, CircleShape),
    ) {
        IdentIcon(pubkey = person.pubkey, modifier = Modifier.fillMaxSize())
        if (!person.user.picture.isNullOrBlank()) {
            AsyncImage(
                model = rememberAvatarImageRequest(person.user.picture, size.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun displayName(person: StartGraphPersonUi): String =
    person.user.displayName?.takeIf(String::isNotBlank)
        ?: person.user.name?.takeIf(String::isNotBlank)
        ?: "npub ${person.pubkey.take(8)}"
