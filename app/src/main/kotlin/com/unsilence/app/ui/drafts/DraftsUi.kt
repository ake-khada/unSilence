package com.unsilence.app.ui.drafts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.drafts.DraftContext
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DraftsScreen(
    onDismiss: () -> Unit,
    onResume: (Draft) -> Unit,
    viewModel: DraftsViewModel = hiltViewModel(key = "drafts-${LocalAppSessionKey.current}"),
) {
    BackHandler(onBack = onDismiss)
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizing.topBarHeight)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Drafts",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
            }
        }

        DraftsList(
            drafts = drafts,
            onResume = onResume,
            onDelete = viewModel::delete,
            emptyTitle = "No drafts",
            emptyMessage = "Saved drafts appear here for the signed-in account.",
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = Spacing.small),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsSheet(
    drafts: List<Draft>,
    onResume: (Draft) -> Unit,
    onDelete: (Draft) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Drafts",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            DraftsList(
                drafts = drafts,
                onResume = { draft ->
                    onResume(draft)
                    onDismiss()
                },
                onDelete = onDelete,
                emptyTitle = "No drafts",
                emptyMessage = "Save a note draft to resume it later.",
                modifier = Modifier.heightIn(max = 520.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = Spacing.small),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseDraftSheet(
    hasDraftableContent: Boolean,
    hasUnsavedMedia: Boolean,
    onSaveDraft: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onContinueEditing,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = Spacing.small),
        ) {
            Text(
                text = "Close composer?",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (hasUnsavedMedia) {
                    "Only text and uploaded media can be saved. Pending or failed media will be discarded."
                } else {
                    "Save this as a draft or discard it."
                },
                color = TextSecondary,
                fontSize = AppType.bodySmall,
                lineHeight = AppType.subheading,
                modifier = Modifier.padding(top = Spacing.small),
            )
            Spacer(Modifier.height(Spacing.large))
            Button(
                onClick = onSaveDraft,
                enabled = hasDraftableContent,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandDeep,
                    contentColor = Color.Black,
                    disabledContainerColor = Surface2,
                    disabledContentColor = TextSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save draft", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(Spacing.small))
            OutlinedButton(
                onClick = onDiscard,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Like.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Like),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Discard")
            }
            TextButton(
                onClick = onContinueEditing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue editing", color = TextSecondary)
            }
            Spacer(Modifier.height(Spacing.large))
        }
    }
}

@Composable
private fun DraftsList(
    drafts: List<Draft>,
    onResume: (Draft) -> Unit,
    onDelete: (Draft) -> Unit,
    emptyTitle: String,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (drafts.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(top = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Surface1, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Drafts, contentDescription = null, tint = TextSecondary)
            }
            Text(
                text = emptyTitle,
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.medium),
            )
            Text(
                text = emptyMessage,
                color = Text3,
                fontSize = AppType.bodySmall,
                modifier = Modifier.padding(top = Spacing.micro),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(drafts, key = { it.key }) { draft ->
            DraftRow(draft = draft, onResume = onResume, onDelete = onDelete)
        }
    }
}

@Composable
private fun DraftRow(
    draft: Draft,
    onResume: (Draft) -> Unit,
    onDelete: (Draft) -> Unit,
) {
    val icon = draftIcon(draft.context)
    val label = draftLabel(draft.context)
    val preview = draft.previewText.ifBlank {
        when (draft.attachmentCount) {
            0 -> "Empty draft"
            1 -> "1 attachment"
            else -> "${draft.attachmentCount} attachments"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface1)
            .border(1.dp, BorderFaint, RoundedCornerShape(8.dp))
            .clickable { onResume(draft) }
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brand.copy(alpha = 0.10f))
                .border(1.dp, Brand.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrandDeep, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Brand.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, Brand.copy(alpha = 0.18f)),
                ) {
                    Text(
                        text = label,
                        color = BrandDeep,
                        fontSize = AppType.caption,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeDraftTime(draft.updatedAt),
                    color = Text3,
                    fontSize = AppType.caption,
                    maxLines = 1,
                )
            }
            Text(
                text = preview,
                color = Color.White,
                fontSize = AppType.body,
                lineHeight = AppType.subheading,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            val detail = draftDetail(draft)
            if (detail != null) {
                Text(
                    text = detail,
                    color = if (draft.hadUnsavedMedia) Mint else Text3,
                    fontSize = AppType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        IconButton(onClick = { onDelete(draft) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete draft", tint = TextSecondary)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Text3, modifier = Modifier.size(16.dp))
    }
}

private fun draftIcon(context: DraftContext): ImageVector = when (context) {
    DraftContext.New -> Icons.Filled.Drafts
    is DraftContext.Reply -> Icons.AutoMirrored.Filled.Reply
    is DraftContext.Quote -> Icons.Filled.FormatQuote
    is DraftContext.ArticleComment -> Icons.AutoMirrored.Filled.Article
}

private fun draftLabel(context: DraftContext): String = when (context) {
    DraftContext.New -> "New note"
    is DraftContext.Reply -> "Reply"
    is DraftContext.Quote -> "Quote"
    is DraftContext.ArticleComment -> "Article comment"
}

private fun draftDetail(draft: Draft): String? {
    val media = when (draft.attachmentCount) {
        0 -> null
        1 -> "1 uploaded attachment"
        else -> "${draft.attachmentCount} uploaded attachments"
    }
    val unsaved = if (draft.hadUnsavedMedia) "some media was not saved" else null
    return listOfNotNull(media, unsaved).joinToString(" · ").ifBlank { null }
}

private fun relativeDraftTime(updatedAtMs: Long): String {
    val elapsedSeconds = ((System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L) / 1000L)
    return when {
        elapsedSeconds < 60 -> "now"
        elapsedSeconds < 60 * 60 -> "${elapsedSeconds / 60}m"
        elapsedSeconds < 24 * 60 * 60 -> "${elapsedSeconds / (60 * 60)}h"
        elapsedSeconds < 7 * 24 * 60 * 60 -> "${elapsedSeconds / (24 * 60 * 60)}d"
        else -> DateTimeFormatter.ofPattern("MMM d")
            .format(Instant.ofEpochMilli(updatedAtMs).atZone(ZoneId.systemDefault()))
    }
}
