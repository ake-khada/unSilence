package com.unsilence.app.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.EventFeedItem
import com.unsilence.app.ui.shared.forEvent
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.White
import com.unsilence.app.ui.thread.ThreadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveEngagementSheet(
    item: ImmersiveVideoItem,
    model: EventModel,
    callbacks: EventActionCallbacks,
    engagement: EngagementSnapshot,
    threadViewModel: ThreadViewModel,
    eventModelProvider: (String) -> EventModel?,
    thumbnailCache: VideoThumbnailCache,
    imageDimensionCache: ImageDimensionCache,
    sensitiveMode: SensitiveContentMode,
    onDismiss: () -> Unit,
    onReply: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val sheetHeight = with(density) {
        (LocalWindowInfo.current.containerSize.height * 0.53f).toDp()
    }
    val threadState by threadViewModel.uiState.collectAsStateWithLifecycle()
    val threadWotLookups by threadViewModel.wotLookups.collectAsStateWithLifecycle()
    val row = item.row

    LaunchedEffect(model.navigateId) {
        threadViewModel.loadThread(model.navigateId)
    }

    val replyCallbacks = remember(callbacks, threadViewModel, threadWotLookups, onReply) {
        callbacks.copy(
            profileFlow = threadViewModel::profileFlow,
            statsFlow = threadViewModel::statsFlow,
            zapDetailsForEvent = threadViewModel::zapDetailsForEvent,
            repostPubkeysForEvent = threadViewModel::repostPubkeysForEvent,
            reactionsForEvent = threadViewModel::reactionsForEvent,
            wotLookup = { pubkey -> threadWotLookups[pubkey] },
            onWotSubjectsVisible = {},
            onComment = onReply,
        )
    }

    val liveStats = callbacks.statsFlow?.let { statsFlow ->
        key(model.engagementId) {
            statsFlow(model.engagementId).collectAsStateWithLifecycle().value
        }
    }
    val eventEngagement = remember(model.engagementId, engagement) {
        engagement.forEvent(model.engagementId)
    }
    val authorProfile = collectProfileAsState(model.pubkey, callbacks.profileFlow)
    val zapEnabled = authorProfile == null || !authorProfile.lud16.isNullOrBlank()
    var drawerOpen by remember(model.engagementId) { mutableStateOf(false) }
    val pinnedEmojis = callbacks.pinnedEmojis()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface1,
        contentColor = White,
        scrimColor = Black.copy(alpha = 0.28f),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 34.dp, height = 3.dp)
                    .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item(key = "author-${row.id}") {
                    AuthorHeader(
                        pubkey = model.pubkey,
                        picture = authorProfile?.picture ?: row.authorPicture,
                        displayName = authorProfile?.displayName
                            ?: authorProfile?.name
                            ?: row.displayName,
                        nip05 = authorProfile?.nip05 ?: row.authorNip05,
                        createdAt = model.createdAt,
                        onAuthorClick = callbacks.onAuthorClick,
                        onNoteClick = { callbacks.onNoteClick(model.navigateId) },
                        lookupProfile = callbacks.lookupProfile,
                        profileFlow = callbacks.profileFlow,
                        wotLookup = callbacks.wotLookup,
                        feedWotDisplayMode = callbacks.feedWotDisplayMode,
                    )
                    InlineText(
                        segments = model.segments,
                        lookupProfile = callbacks.lookupProfile,
                        onAuthorClick = callbacks.onAuthorClick,
                        onHashtagClick = callbacks.onHashtagClick,
                        onTextClick = { callbacks.onNoteClick(model.navigateId) },
                        customEmojis = model.customEmojis,
                        modifier = Modifier.padding(
                            horizontal = Spacing.medium,
                            vertical = Spacing.small,
                        ),
                    )
                }

                item(key = "actions-${row.id}") {
                    EventActionBar(
                        noteId = model.navigateId,
                        zapTargetId = model.engagementId,
                        replyCount = liveStats?.replyCount ?: row.replyCount,
                        repostCount = liveStats?.repostCount ?: row.repostCount,
                        reactionCount = liveStats?.reactionCount ?: row.reactionCount,
                        zapTotalSats = liveStats?.zapTotalSats ?: row.zapTotalSats,
                        hasReacted = eventEngagement.hasReacted,
                        hasReposted = eventEngagement.hasReposted,
                        hasZapped = eventEngagement.hasZapped,
                        isNwcConfigured = eventEngagement.isNwcConfigured,
                        isZapLoading = eventEngagement.isZapLoading,
                        extraZapSats = eventEngagement.extraZapSats,
                        zapFlash = eventEngagement.zapFlash,
                        zapEnabled = zapEnabled,
                        drawerOpen = drawerOpen,
                        onChevronTap = { drawerOpen = !drawerOpen },
                        onNoteClick = { callbacks.onNoteClick(model.navigateId) },
                        onComment = { onReply(model.navigateId) },
                        onReact = { callbacks.react(model.engagementId, model.pubkey, "+", null) },
                        onReactLongPress = {
                            callbacks.onReactLongPress?.invoke(model.engagementId, model.pubkey)
                        },
                        pinnedEmojis = pinnedEmojis,
                        onReactWithEmoji = { emoji ->
                            callbacks.react(
                                model.engagementId,
                                model.pubkey,
                                ":${emoji.shortcode}:",
                                emoji.url,
                            )
                        },
                        onRepost = {
                            callbacks.repost(model.engagementId, model.pubkey, row.relayUrl)
                        },
                        onQuote = callbacks.onQuote,
                        onZap = { request: ZapRequest ->
                            callbacks.zap(model.engagementId, model.pubkey, row.relayUrl, request)
                        },
                        onSaveNwcUri = callbacks.saveNwcUri,
                    )
                    AnimatedVisibility(
                        visible = drawerOpen,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        EngagementDrawer(
                            eventId = model.engagementId,
                            statsFlow = callbacks.statsFlow,
                            zapDetailsForEvent = callbacks.zapDetailsForEvent,
                            repostPubkeysForEvent = callbacks.repostPubkeysForEvent,
                            reactionsForEvent = callbacks.reactionsForEvent,
                            profileFlow = callbacks.profileFlow,
                            lookupProfile = callbacks.lookupProfile,
                            onProfileTap = callbacks.onAuthorClick,
                        )
                    }
                    HorizontalDivider(color = BorderFaint)
                }

                item(key = "comments-label-${row.id}") {
                    Text(
                        text = when {
                            threadState.loading -> "Comments"
                            threadState.replies.isEmpty() -> "No comments yet"
                            else -> "${threadState.replies.size} ${if (threadState.replies.size == 1) "comment" else "comments"}"
                        },
                        color = TextSecondary,
                        fontSize = AppType.footnote,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            horizontal = Spacing.medium,
                            vertical = Spacing.small,
                        ),
                    )
                }

                if (threadState.loading) {
                    item(key = "comments-loading-${row.id}") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Brand,
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                } else {
                    items(threadState.replies, key = { it.row.id }) { depthRow ->
                        EventFeedItem(
                            row = depthRow.row,
                            engagement = engagement,
                            callbacks = replyCallbacks,
                            pinnedEmojis = pinnedEmojis,
                            videoScope = null,
                            role = CardRole.Reply,
                            isNewPost = false,
                            onNewPostAnimated = {},
                            thumbnailCache = thumbnailCache,
                            imageDimensionCache = imageDimensionCache,
                            eventModelProvider = eventModelProvider,
                            sensitiveMode = sensitiveMode,
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderFaint)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2)
                    .clickable { onReply(model.navigateId) }
                    .padding(horizontal = Spacing.medium, vertical = 12.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = Brand,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(Spacing.small))
                    Text(
                        text = "Reply",
                        color = White,
                        fontSize = AppType.body,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
