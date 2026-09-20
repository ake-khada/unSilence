package com.unsilence.app.ui.navigation

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Spacing

/** Opening Global and choosing its saved lens are separate, non-overlapping actions. */
@Composable
internal fun GlobalFeedSelectorRow(
    selected: Boolean,
    lens: GlobalFeedLens,
    onSelect: () -> Unit,
    onLensChanged: (GlobalFeedLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = globalLensAccent(lens)
    val next = if (lens == GlobalFeedLens.TRUSTED) GlobalFeedLens.RAW else GlobalFeedLens.TRUSTED
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brand.copy(alpha = .08f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Global", color = Color.White,
                style = AppTextStyles.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false))
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected",
                tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        // This is the existing lens action relocated from the header. It does not
        // switch sources or restart relay subscriptions merely to change a preference.
        Box(
            modifier = Modifier.padding(end = Spacing.small)
                .widthIn(min = 48.dp, max = 160.dp).heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Global mode"
                    stateDescription = globalLensDescription(lens)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = "Switch Global to ${globalLensDescription(next)}",
                    onClick = { onLensChanged(next) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Plain shield + label, retaining the full-size touch target.
            Row(
                modifier = Modifier
                    .padding(horizontal = Spacing.small, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (lens == GlobalFeedLens.TRUSTED) Icons.Outlined.GppGood else Icons.Outlined.GppBad,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (lens == GlobalFeedLens.TRUSTED) "Trusted" else "Raw",
                    color = Color.White,
                    style = AppTextStyles.footnote,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}
