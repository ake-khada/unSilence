package com.unsilence.app.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.unsilence.app.ui.common.LogoMark
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.ui.notifications.NotifFilter
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

internal fun globalLensAccent(lens: GlobalFeedLens): Color = when (lens) {
    GlobalFeedLens.TRUSTED -> Mint
    GlobalFeedLens.RAW -> Zap
}

internal fun globalLensDescription(lens: GlobalFeedLens): String = when (lens) {
    GlobalFeedLens.TRUSTED -> "WoT filtered"
    GlobalFeedLens.RAW -> "Raw, no WoT filter"
}

/** One fixed center line for both tabs, independent of their labels and hint visibility. */
@Composable
internal fun HeaderFrame(
    accent: Color,
    isRefreshing: Boolean,
    motionEnabled: Boolean,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(Sizing.feedTopBarHeight), contentAlignment = Alignment.Center) {
        FeedHeaderHairline(accent, isRefreshing, motionEnabled)
        Layout(
            content = {
                Box(contentAlignment = Alignment.Center) { start() }
                Box(contentAlignment = Alignment.Center) { center() }
                Box(contentAlignment = Alignment.Center) { end() }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val sideConstraints = loose.copy(maxWidth = constraints.maxWidth / 2)
            val leading = measurables[0].measure(sideConstraints)
            val trailing = measurables[2].measure(sideConstraints)
            // Symmetric clearance keeps the capsule at the screen center even when
            // the side actions differ in width. One measure pass.
            val clearance = maxOf(leading.width, trailing.width) + Spacing.small.roundToPx()
            val middle = measurables[1].measure(
                loose.copy(maxWidth = (constraints.maxWidth - 2 * clearance).coerceAtLeast(0)),
            )
            val height = maxOf(leading.height, middle.height, trailing.height)
            layout(constraints.maxWidth, height) {
                leading.placeRelative(0, (height - leading.height) / 2)
                middle.placeRelative((constraints.maxWidth - middle.width) / 2, (height - middle.height) / 2)
                trailing.placeRelative(constraints.maxWidth - trailing.width, (height - trailing.height) / 2)
            }
        }
    }
}

@Composable
internal fun FeedSourcePill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lens: GlobalFeedLens? = null,
    expanded: Boolean = false,
    motionEnabled: Boolean = false,
) {
    var previousLens by remember { mutableStateOf(lens) }
    val tintSpec = remember(lens, motionEnabled) {
        if (shouldAnimateLensTransition(previousLens, lens, motionEnabled)) {
            tween<Color>(LENS_TINT_TRANSITION_MS)
        } else snap<Color>()
    }
    SideEffect { previousLens = lens }
    val shieldColor by animateColorAsState(
        targetValue = lens?.let(::globalLensAccent) ?: TextSecondary,
        animationSpec = tintSpec,
        label = "shieldTint",
    )
    HeaderControl(
        description = "Feed source",
        selection = lens?.let { "$label, ${globalLensDescription(it)}" } ?: label,
        actionLabel = "Choose feed or relay",
        onClick = onClick,
        modifier = modifier,
    ) {
        HeaderContents(
            label = label,
            junction = HeaderJunctionKind.SELECTOR,
            leadingIcon = when (lens) {
                GlobalFeedLens.TRUSTED -> Icons.Outlined.GppGood
                GlobalFeedLens.RAW -> Icons.Outlined.GppBad
                null -> null
            },
            accent = shieldColor,
            maxLines = 2,
            expanded = expanded,
            motionEnabled = motionEnabled,
        )
    }
}

@Composable
internal fun NotificationHeader(
    current: NotifFilter,
    showHint: Boolean,
    motionEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeaderFrame(
        accent = Brand,
        isRefreshing = false,
        motionEnabled = motionEnabled,
        start = { LogoMark(sizeDp = Spacing.xxl, static = !motionEnabled) },
        center = { NotificationFilterPill(current, showHint, motionEnabled, onToggle) },
        end = {},
        modifier = modifier,
    )
}

@Composable
internal fun NotificationFilterPill(
    current: NotifFilter,
    showHint: Boolean,
    motionEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hide immediately on the first tap, without waiting for the durable preference write.
    var switched by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(modifier, contentAlignment = Alignment.Center) {
        HeaderControl(
            description = "Notification filter",
            selection = current.name,
            actionLabel = "Switch to ${current.next().name}",
            onClick = {
                switched = true
                onToggle()
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Stable touch bounds; each complete visible group stays centered.
                HeaderContents(
                    NotifFilter.Following.name, HeaderJunctionKind.SWITCH,
                    modifier = Modifier.alpha(0f),
                )
                Crossfade(
                    targetState = current,
                    modifier = Modifier.matchParentSize(),
                    animationSpec = tween(if (motionEnabled) 120 else 0),
                    label = "notificationFilter",
                ) { selection ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        HeaderContents(selection.name, HeaderJunctionKind.SWITCH, fillWidth = true)
                    }
                }
            }
        }
        if (showHint && !switched) NotificationFilterHint()
    }
}

@Composable
private fun HeaderControl(
    description: String,
    selection: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics {
                contentDescription = description
                stateDescription = selection
            }
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = actionLabel,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // Invisible backing masks the line behind the label. Only the
                // junction's connector crosses the trailing padding.
                .clipToBounds()
                .background(Color.Black)
                .drawWithCache {
                    val start = size.width - 10.dp.toPx()
                    val connector = Brush.horizontalGradient(
                        listOf(TextSecondary.copy(alpha = .55f), Color.White.copy(alpha = .08f)),
                        startX = start, endX = size.width,
                    )
                    onDrawWithContent {
                        drawContent()
                        // Outside the label crossfade: the connection never blinks
                        // or moves when switching the notification filter.
                        drawLine(connector, Offset(start, size.height / 2), Offset(size.width, size.height / 2),
                            strokeWidth = 1.dp.toPx())
                    }
                }
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** One-pass placement joins the trailing icon to the line, including reserved label space. */
@Composable
private fun HeaderContents(
    label: String,
    junction: HeaderJunctionKind,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    accent: Color? = null,
    maxLines: Int = 1,
    expanded: Boolean = false,
    motionEnabled: Boolean = false,
    fillWidth: Boolean = false,
) {
    Layout(
        modifier = modifier,
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    // Shield path x=4..20 in a 24-unit viewport; reserve only ink width.
                    Box(Modifier.size(width = (17f * 2f / 3f).dp, height = 17.dp), contentAlignment = Alignment.Center) {
                        Icon(leadingIcon, contentDescription = null, tint = accent ?: TextSecondary,
                            modifier = Modifier.requiredSize(17.dp))
                    }
                }
                Text(
                    text = label,
                    color = Color.White,
                    style = AppTextStyles.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                HeaderJunction(junction, expanded, motionEnabled)
            }
            Box(Modifier.background(TextSecondary.copy(alpha = .55f)))
        },
    ) { measurables, constraints ->
        val group = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (fillWidth && constraints.hasBoundedWidth) constraints.maxWidth else group.width
        val left = (width - group.width) / 2
        // Connect through any reserved notification-label space. The control
        // draws the final 10dp segment independently of its label crossfade.
        val connector = measurables[1].measure(Constraints.fixed(
            width = width - left - group.width,
            height = 1.dp.roundToPx(),
        ))
        layout(width, group.height) {
            group.placeRelative(left, 0)
            connector.placeRelative(left + group.width, (group.height - connector.height) / 2)
        }
    }
}

internal enum class HeaderJunctionKind { SELECTOR, SWITCH }

/** The junction stays on the center line; only the selector point changes direction. */
@Composable
internal fun HeaderJunction(
    kind: HeaderJunctionKind,
    expanded: Boolean = false,
    motionEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val open = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (motionEnabled) tween(160) else snap(),
        label = "selectorJunction",
    )
    Box(modifier.size(width = if (kind == HeaderJunctionKind.SELECTOR) 8.dp else 12.dp, height = 16.dp)
        .drawWithCache {
            val y = size.height / 2f
            val stroke = 1.25.dp.toPx()
            val depth = 3.dp.toPx()
            // Round caps stay inside the measured ink slot, including at the join.
            val left = stroke / 2f
            val right = size.width - stroke / 2f
            onDrawBehind {
                fun segment(from: Offset, to: Offset) = drawLine(
                    TextSecondary, from, to, strokeWidth = stroke, cap = StrokeCap.Round,
                )
                if (kind == HeaderJunctionKind.SELECTOR) {
                    // Read animation only while drawing, never in header composition.
                    val tip = Offset(size.width / 2, y + depth * (1f - 2f * open.value))
                    segment(Offset(left, y), tip)
                    segment(tip, Offset(right, y))
                } else {
                    segment(Offset(left, y), Offset(right, y))
                    segment(Offset(left + depth, y - depth), Offset(left, y))
                    segment(Offset(left + depth, y + depth), Offset(left, y))
                    segment(Offset(right - depth, y - depth), Offset(right, y))
                    segment(Offset(right - depth, y + depth), Offset(right, y))
                }
            }
        })
}

@Composable
private fun NotificationFilterHint() {
    val gap = with(LocalDensity.current) { Spacing.small.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(
                x = (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                y = (anchorBounds.bottom + gap)
                    .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
            )
        }
    }
    // A non-modal overlay: appearing/dismissing it never remeasures the header or list.
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(color = Surface2, shape = RoundedCornerShape(8.dp)) {
            Text(
                text = "Tap to switch Global / Following",
                color = TextSecondary,
                style = AppTextStyles.footnote,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }
    }
}
