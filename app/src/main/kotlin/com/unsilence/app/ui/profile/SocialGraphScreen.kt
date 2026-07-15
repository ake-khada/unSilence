package com.unsilence.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.WotProviderDescriptor
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.WotProviderOptionState
import com.unsilence.app.data.relay.WotProviderSource
import com.unsilence.app.data.relay.isDefaultWotProvider
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Text4
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import java.util.Locale

@Composable
fun SocialGraphScreen(
    onDismiss: () -> Unit,
    viewModel: SocialGraphViewModel = hiltViewModel(
        key = "social-graph-${LocalAppSessionKey.current}",
    ),
) {
    BackHandler(onBack = onDismiss)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = remember(state.lastWotFetchAt, state.refreshing) { System.currentTimeMillis() }
    var confirmPublishProviderList by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Social graph",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                item {
                    Spacer(Modifier.height(Spacing.small))
                    GrapevineSelectorCard(
                        state = state,
                        onDefault = viewModel::selectDefault,
                        onOwn = viewModel::selectOwnGrapevine,
                        onCustom = viewModel::expandCustomProvider,
                        onCustomPubkey = viewModel::setCustomPubkeyInput,
                        onCustomRelay = viewModel::setCustomRelayInput,
                        onApplyCustom = viewModel::applyCustomProvider,
                    )
                }
                if (state.publishProviderListNeeded) {
                    item {
                        PublishProviderListCard(
                            state = state,
                            onClick = { confirmPublishProviderList = true },
                        )
                    }
                }
                item { ProvenanceBanner(state) }
                item { StandingCard(state.ownStanding) }
                item {
                    CoverageCard(
                        state = state,
                        now = now,
                        onRefresh = viewModel::refresh,
                    )
                }
                item {
                    FeedScoreModeCard(
                        mode = state.feedWotDisplayMode,
                        onChange = viewModel::setFeedWotDisplayMode,
                    )
                }
                item { HowScoresWorkCard() }
                item { Spacer(Modifier.height(Spacing.xl)) }
            }
        }
    }

    if (confirmPublishProviderList) {
        AlertDialog(
            onDismissRequest = { confirmPublishProviderList = false },
            containerColor = Surface1,
            title = {
                Text("Publish provider list", color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(
                        text = "This publishes exactly this provider in your public kind 10040 list.",
                        color = TextSecondary,
                        fontSize = AppType.bodySmall,
                    )
                    MetricRow("Provider", shortNpub(state.activeProvider.providerPubkey))
                    MetricRow("Relay", state.activeProvider.relayHint)
                    MetricRow(
                        "Default provider",
                        if (isDefaultWotProvider(state.activeProvider)) "Yes · NosFabrica" else "No",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmPublishProviderList = false
                        viewModel.publishProviderList()
                    },
                    enabled = !state.publishingProviderList,
                ) {
                    Text("Publish", color = Brand, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPublishProviderList = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun PublishProviderListCard(
    state: SocialGraphUiState,
    onClick: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Publish provider list",
                    color = Color.White,
                    fontSize = AppType.body,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${shortNpub(state.activeProvider.providerPubkey)} · ${state.activeProvider.relayHint}",
                    color = Text3,
                    fontSize = AppType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onClick,
                enabled = !state.publishingProviderList,
                colors = ButtonDefaults.buttonColors(containerColor = Zap, contentColor = Black),
            ) {
                if (state.publishingProviderList) {
                    CircularProgressIndicator(
                        color = Black,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text("Publish", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FeedScoreModeCard(
    mode: FeedWotDisplayMode,
    onChange: (FeedWotDisplayMode) -> Unit,
) {
    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(
                text = "Feed scores",
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeedScoreModeSegment(
                    label = "Numbers",
                    selected = mode == FeedWotDisplayMode.NUMBERS,
                    onClick = { onChange(FeedWotDisplayMode.NUMBERS) },
                    modifier = Modifier.weight(1f),
                )
                FeedScoreModeSegment(
                    label = "Exceptions",
                    selected = mode == FeedWotDisplayMode.EXCEPTIONS_ONLY,
                    onClick = { onChange(FeedWotDisplayMode.EXCEPTIONS_ONLY) },
                    modifier = Modifier.weight(1f),
                )
                FeedScoreModeSegment(
                    label = "Off",
                    selected = mode == FeedWotDisplayMode.OFF,
                    onClick = { onChange(FeedWotDisplayMode.OFF) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeedScoreModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Brand.copy(alpha = 0.16f) else Surface2)
            .border(
                width = 1.dp,
                color = if (selected) Brand.copy(alpha = 0.55f) else BorderSubtle,
                shape = RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Brand else TextSecondary,
            fontSize = AppType.caption,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GrapevineSelectorCard(
    state: SocialGraphUiState,
    onDefault: () -> Unit,
    onOwn: () -> Unit,
    onCustom: () -> Unit,
    onCustomPubkey: (String) -> Unit,
    onCustomRelay: (String) -> Unit,
    onApplyCustom: () -> Unit,
) {
    SettingsCard {
        SectionLabel("Grapevine — whose lens ranks people")
        ProviderIdentityRow(
            provider = state.activeProvider,
            profile = state.activeProviderProfile,
        )
        Spacer(Modifier.height(Spacing.small))
        state.selectorOptions.forEach { option ->
            ProviderOptionRow(
                option = option,
                onClick = when (option.source) {
                    WotProviderSource.DEFAULT -> onDefault
                    WotProviderSource.OWN_10040 -> onOwn
                    WotProviderSource.CUSTOM -> onCustom
                },
            )
        }
        if (state.customExpanded) {
            Spacer(Modifier.height(Spacing.small))
            CustomProviderFields(
                pubkey = state.customPubkeyInput,
                relay = state.customRelayInput,
                error = state.customError,
                onPubkey = onCustomPubkey,
                onRelay = onCustomRelay,
                onApply = onApplyCustom,
            )
        }
    }
}

@Composable
private fun ProviderIdentityRow(
    provider: WotProviderDescriptor,
    profile: UserEntity?,
) {
    val name = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: if (provider.providerPubkey.equals(DEFAULT_WOT_PROVIDER_PUBKEY, ignoreCase = true)) {
            "NosFabrica"
        } else {
            null
        }
        ?: "Provider ${shortKey(provider.providerPubkey)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            pubkey = provider.providerPubkey,
            picture = profile?.picture,
            modifier = Modifier.size(34.dp),
            sizeDp = 34.dp,
        )
        Spacer(Modifier.width(Spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile?.nip05?.takeIf { it.isNotBlank() } ?: provider.relayHint,
                color = Text3,
                fontSize = AppType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderOptionRow(
    option: WotProviderOptionState,
    onClick: () -> Unit,
) {
    val dim = option.source == WotProviderSource.OWN_10040 && !option.enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .alpha(if (dim) 0.62f else 1f)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = option.selected,
            enabled = !dim,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Brand,
                unselectedColor = TextSecondary,
                disabledSelectedColor = Text4,
                disabledUnselectedColor = Text4,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = providerOptionTitle(option.source),
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = option.subtitle,
                color = Text3,
                fontSize = AppType.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        option.actionHint?.let {
            Text(
                text = it,
                color = if (dim) Zap else Brand,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = Spacing.small),
            )
        }
        if (option.source == WotProviderSource.CUSTOM) {
            Icon(Icons.Filled.ChevronRight, null, tint = Text3, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CustomProviderFields(
    pubkey: String,
    relay: String,
    error: String?,
    onPubkey: (String) -> Unit,
    onRelay: (String) -> Unit,
    onApply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SocialGraphTextField(
            value = pubkey,
            onValueChange = onPubkey,
            label = "Provider npub or hex",
        )
        SocialGraphTextField(
            value = relay,
            onValueChange = onRelay,
            label = "Relay URL",
        )
        if (error != null) {
            Text(error, color = Like, fontSize = AppType.caption)
        }
        Button(
            onClick = onApply,
            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Black),
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Use custom provider", fontSize = AppType.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SocialGraphTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = AppType.bodySmall),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Brand,
            unfocusedBorderColor = BorderSubtle,
            focusedContainerColor = Surface2,
            unfocusedContainerColor = Surface2,
            cursorColor = Brand,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProvenanceBanner(state: SocialGraphUiState) {
    val selectedExplanation = state.selectorOptions
        .firstOrNull { it.selected }
        ?.selectionExplanation
    val text = selectedExplanation ?: when (state.prefs.source) {
        WotProviderSource.DEFAULT ->
            "Ranks are seen from the NosFabrica seed's vantage point, not yours. Switch to your grapevine for personal scores."
        WotProviderSource.OWN_10040 ->
            "Ranks are personal: computed from your own grapevine."
        WotProviderSource.CUSTOM ->
            "Ranks come from your selected custom provider. Verify the provider before relying on scores."
    }
    Row(
        modifier = Modifier
            .padding(horizontal = Spacing.medium)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Zap.copy(alpha = 0.11f))
            .border(1.dp, Zap.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(Spacing.medium),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Icon(Icons.Filled.Visibility, null, tint = Zap, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = AppType.bodySmall,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StandingCard(lookup: WotLookup) {
    SettingsCard {
        SectionLabel("Your standing in this grapevine")
        when (lookup) {
            is WotLookup.Scored -> ScoredStanding(lookup.assertion)
            WotLookup.Absent -> NeutralStanding("Not scored in this grapevine")
            WotLookup.Pending -> NeutralStanding("Checking this grapevine")
        }
    }
}

@Composable
internal fun ScoredStanding(assertion: WotAssertionEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        RankRing(assertion.rank)
        Column(modifier = Modifier.weight(1f)) {
            assertion.hops?.let { MetricRow("Hops", it.toString()) }
            assertion.verifiedFollowers?.let { MetricRow("Verified followers", formatCount(it)) }
            assertion.influence?.let { MetricRow("Influence", formatDecimal(it)) }
            assertion.confidence?.let { MetricRow("Confidence", formatDecimal(it)) }
            if (assertion.verifiedMuters != null || assertion.verifiedReporters != null) {
                val muterReporterValue =
                    "${assertion.verifiedMuters?.let(::formatCount) ?: "0"} · ${assertion.verifiedReporters?.let(::formatCount) ?: "0"}"
                MetricRow(
                    "Muted · reported by",
                    muterReporterValue,
                    color = if ((assertion.verifiedMuters ?: 0L) > 0L || (assertion.verifiedReporters ?: 0L) > 0L) {
                        Like
                    } else {
                        TextSecondary
                    },
                )
            }
        }
    }
}

@Composable
private fun NeutralStanding(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, Text4, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.AccountTree, null, tint = Text3, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Spacing.medium))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = AppType.body,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RankRing(rank: Int) {
    val color = wotTierColor(rank)
    Box(modifier = Modifier.size(78.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = BorderSubtle,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (rank.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rank.toString(), color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("WoT", color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CoverageCard(
    state: SocialGraphUiState,
    now: Long,
    onRefresh: () -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Coverage", modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRefresh,
                enabled = !state.refreshing,
                modifier = Modifier.size(34.dp),
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(color = Brand, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Brand, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(
            text = "${state.coverage.scored} of ${state.coverage.total} follows scored",
            color = Color.White,
            fontSize = AppType.body,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.small))
        LinearProgressIndicator(
            progress = { state.coverage.fraction.coerceIn(0f, 1f) },
            color = Brand,
            trackColor = Surface2,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp)),
        )
        Spacer(Modifier.height(Spacing.small))
        Text(
            text = "${formatSyncedAgo(state.lastWotFetchAt, now)} · ${state.activeProvider.relayHint}",
            color = Text3,
            fontSize = AppType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.statusMessage?.let {
            Spacer(Modifier.height(Spacing.micro))
            Text(it, color = TextSecondary, fontSize = AppType.caption, maxLines = 2)
        }
    }
}

@Composable
private fun HowScoresWorkCard() {
    val uriHandler = LocalUriHandler.current
    SettingsCard {
        SectionLabel("How scores work")
        ExplainerLine("Influence = average rating × confidence")
        ExplainerLine("Confidence grows with evidence, shrinks with hops")
        ExplainerLine("Follows raise scores · mutes and reports lower them")
        Spacer(Modifier.height(Spacing.small))
        Text(
            text = "brainstorm.world",
            color = Brand,
            fontSize = AppType.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { uriHandler.openUri("https://brainstorm.world") },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = Spacing.medium)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface1)
            .border(1.dp, BorderFaint, RoundedCornerShape(10.dp))
            .padding(Spacing.medium),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = Spacing.small),
    )
}

@Composable
private fun MetricRow(label: String, value: String, color: Color = TextSecondary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Text3, fontSize = AppType.caption, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = AppType.caption, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun ExplainerLine(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = AppType.bodySmall,
        lineHeight = 18.sp,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun providerOptionTitle(source: WotProviderSource): String = when (source) {
    WotProviderSource.DEFAULT -> "NosFabrica community"
    WotProviderSource.OWN_10040 -> "Your grapevine"
    WotProviderSource.CUSTOM -> "Custom provider"
}

private fun wotTierColor(score: Int): Color = when {
    score >= 70 -> Mint
    score >= 40 -> Zap
    else -> Like
}

private fun shortKey(pubkey: String): String =
    if (pubkey.length > 14) "${pubkey.take(8)}…${pubkey.takeLast(6)}" else pubkey

private fun shortNpub(pubkey: String): String =
    runCatching { pubkey.hexToByteArray().toNpub() }
        .getOrNull()
        ?.let { npub -> "${npub.take(10)}…${npub.takeLast(6)}" }
        ?: shortKey(pubkey)

private fun formatDecimal(value: Double): String =
    String.format(Locale.US, "%.3f", value)

private fun formatCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun formatSyncedAgo(timestamp: Long, now: Long): String {
    if (timestamp <= 0L) return "Never synced"
    val ageMs = (now - timestamp).coerceAtLeast(0L)
    val minutes = ageMs / 60_000L
    val hours = ageMs / 3_600_000L
    val days = ageMs / 86_400_000L
    return when {
        minutes < 1L -> "Synced just now"
        hours < 1L -> "Synced ${minutes}m ago"
        days < 1L -> "Synced ${hours}h ago"
        else -> "Synced ${days}d ago"
    }
}
