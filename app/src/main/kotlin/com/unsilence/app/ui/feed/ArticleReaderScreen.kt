package com.unsilence.app.ui.feed

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleReaderScreen(
    row: FeedRow,
    onDismiss: () -> Unit,
    onNoteClick: (String) -> Unit = {},
    onReact: () -> Unit = {},
    onReactLongPress: () -> Unit = {},
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji> = emptyList(),
    onReactWithEmoji: (com.unsilence.app.data.memory.CustomEmoji) -> Unit = {},
    onRepost: () -> Unit = {},
    onQuote: (String) -> Unit = {},
    onZap: (request: ZapRequest) -> Unit = {},
    onSaveNwcUri: (String) -> Unit = {},
    hasReacted: Boolean = false,
    hasReposted: Boolean = false,
    hasZapped: Boolean = false,
    isNwcConfigured: Boolean = false,
    isZapLoading: Boolean = false,
    extraZapSats: Long = 0L,
    zapFlash: NoteActionsViewModel.ZapFlashState? = null,
    // Live chrome (mirrors EventCard): null → static row.* counts + no drawer (SearchScreen).
    onAuthorClick: (String) -> Unit = {},
    lookupProfile: (suspend (String) -> com.unsilence.app.data.memory.UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.UserEntity?>)? = null,
    statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)? = null,
    zapDetailsForEvent: ((String) -> List<com.unsilence.app.data.memory.ZapDetail>)? = null,
    repostPubkeysForEvent: ((String) -> List<String>)? = null,
    reactionsForEvent: ((String) -> List<com.unsilence.app.data.memory.ReactionInfo>)? = null,
) {
    // Unwrap reposts: for a kind-6/16 wrapper the FeedRow's tags/content belong to
    // the wrapper (embedded JSON string + wrapper tags). Parse to the EventModel so
    // title/image come from the INNER article tags and the body is the inner
    // markdown — not the raw embedded-JSON dump. For non-reposts these resolve to
    // the same values as the raw row.
    val model = remember(row.id, row.content, row.tags) { row.toEventModel() }
    val title = model.article?.title ?: articleTagValue(row.tags, "title")
    val image = model.article?.image ?: articleTagValue(row.tags, "image")
    val context = LocalContext.current

    val bodyHtml = remember(model.articleContent) { markdownToHtml(model.articleContent ?: "") }

    // Effective author (inner author on a reposted article — never the reposter),
    // mirroring EventCard.ArticleLayout's resolution.
    val isRepost = model.repost != null
    val authorProfile = profileFlow?.invoke(model.pubkey)?.collectAsStateWithLifecycle()?.value
    val authorLabel = authorProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: authorProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
        ?: (if (isRepost) null else row.displayName)
        ?: "${model.pubkey.take(6)}…${model.pubkey.takeLast(4)}"

    // Live engagement counts keyed off the effective id; fall back to the static
    // FeedRow snapshot when no statsFlow is wired (e.g. SearchScreen).
    val liveStats = statsFlow?.invoke(model.engagementId)?.collectAsStateWithLifecycle()?.value
    val replyCount    = liveStats?.replyCount    ?: row.replyCount
    val repostCount   = liveStats?.repostCount   ?: row.repostCount
    val reactionCount = liveStats?.reactionCount ?: row.reactionCount
    val zapTotalSats  = liveStats?.zapTotalSats  ?: row.zapTotalSats

    var drawerOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Black),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Scrollable content ─────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Top bar ────────────────────────────────────────────────
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector        = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint               = Color.White,
                                modifier           = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Share lives here — EventActionBar (below) has no Share slot.
                        IconButton(onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "https://njump.me/${model.navigateId}")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }) {
                            Icon(
                                imageVector        = Icons.Filled.Share,
                                contentDescription = "Share",
                                tint               = Color.White,
                                modifier           = Modifier.size(22.dp),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    // ── Author header (effective author, live profile) ─────────
                    AuthorHeader(
                        pubkey        = model.pubkey,
                        picture       = authorProfile?.picture ?: if (isRepost) null else row.authorPicture,
                        displayName   = authorLabel,
                        nip05         = authorProfile?.nip05 ?: if (isRepost) null else row.authorNip05,
                        createdAt     = model.createdAt,
                        onAuthorClick = onAuthorClick,
                        onNoteClick   = {},
                        lookupProfile = lookupProfile,
                        profileFlow   = profileFlow,
                        modifier      = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    )

                    // ── Banner image — full image at its natural aspect (no crop) ──
                    if (!image.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model              = rememberFullWidthImageRequest(image, aspectRatio = 16f / 9f),
                            contentDescription = null,
                            contentScale       = ContentScale.FillWidth,
                            modifier           = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(
                                    bottomStart = Sizing.mediaCornerRadius,
                                    bottomEnd   = Sizing.mediaCornerRadius,
                                )),
                        )
                    }

                    // ── Title ──────────────────────────────────────────────────
                    if (!title.isNullOrBlank()) {
                        Text(
                            text       = title,
                            color      = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize   = AppType.title,
                            lineHeight = 30.sp,
                            modifier   = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.medium)
                                .padding(top = Spacing.medium, bottom = Spacing.small),
                        )
                    }

                    HorizontalDivider(
                        color    = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.medium),
                    )

                    Spacer(Modifier.height(Spacing.small))

                    // ── Body content (WebView) ─────────────────────────────────
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.BLACK)
                                settings.javaScriptEnabled = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = false

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        request?.url?.let { uri ->
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        }
                                        return true
                                    }
                                }

                                loadDataWithBaseURL(null, bodyHtml, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.xl),
                    )
                }

                // ── Sticky bottom: engagement drawer (expands upward) + action bar ──
                // Same chrome as EventCard. The card renders the drawer BELOW an inline
                // bar; here the bar is sticky-bottom, so the drawer sits above it and the
                // whole container grows upward when opened.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant)
                        .navigationBarsPadding(),
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = drawerOpen,
                        enter   = androidx.compose.animation.expandVertically(),
                        exit    = androidx.compose.animation.shrinkVertically(),
                    ) {
                        // The card's drawer scrolls with the feed LazyColumn; here it's
                        // bottom-anchored, so cap its height and give it its own scroll —
                        // otherwise a heavily-zapped article's list runs off-screen.
                        Box(
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EngagementDrawer(
                                eventId               = model.engagementId,
                                statsFlow             = statsFlow,
                                zapDetailsForEvent    = zapDetailsForEvent,
                                repostPubkeysForEvent = repostPubkeysForEvent,
                                reactionsForEvent     = reactionsForEvent,
                                profileFlow           = profileFlow,
                                lookupProfile         = lookupProfile,
                                onProfileTap          = onAuthorClick,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    EventActionBar(
                        noteId           = model.navigateId,
                        replyCount       = replyCount,
                        repostCount      = repostCount,
                        reactionCount    = reactionCount,
                        zapTotalSats     = zapTotalSats,
                        hasReacted       = hasReacted,
                        hasReposted      = hasReposted,
                        hasZapped        = hasZapped,
                        isNwcConfigured  = isNwcConfigured,
                        isZapLoading     = isZapLoading,
                        extraZapSats     = extraZapSats,
                        zapFlash         = zapFlash,
                        drawerOpen       = drawerOpen,
                        onChevronTap     = { drawerOpen = !drawerOpen },
                        onNoteClick      = { onNoteClick(model.navigateId) },
                        onComment        = { onNoteClick(model.navigateId) },
                        onReact          = onReact,
                        onReactLongPress = onReactLongPress,
                        pinnedEmojis     = pinnedEmojis,
                        onReactWithEmoji = onReactWithEmoji,
                        onRepost         = onRepost,
                        onQuote          = onQuote,
                        onZap            = onZap,
                        onSaveNwcUri     = onSaveNwcUri,
                        modifier         = Modifier.padding(top = Spacing.small),
                    )
                }
            }
        }
    }
}

// ── Markdown → HTML conversion ────────────────────────────────────────────────

private fun markdownToHtml(markdown: String): String {
    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
    val body = HtmlGenerator(markdown, tree, flavour).generateHtml()
    return wrapHtml(body)
}

private fun wrapHtml(body: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { box-sizing: border-box; }
body {
    background: #000000;
    color: #FFFFFF;
    font-family: -apple-system, system-ui, sans-serif;
    font-size: 16px;
    line-height: 1.6;
    margin: 0;
    padding: 0 16px 32px 16px;
    word-wrap: break-word;
    overflow-wrap: break-word;
}
a { color: #00E5FF; text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3, h4, h5, h6 { color: #FFFFFF; font-weight: bold; margin: 1.2em 0 0.4em; }
h1 { font-size: 1.6em; }
h2 { font-size: 1.4em; }
h3 { font-size: 1.2em; }
h4, h5, h6 { font-size: 1.05em; }
p { margin: 0.6em 0; }
img {
    max-width: 100%;
    height: auto;
    border-radius: 8px;
    margin: 8px 0;
    display: block;
}
pre {
    background: #1A1A1A;
    padding: 12px;
    border-radius: 8px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    margin: 12px 0;
}
pre code {
    background: none;
    padding: 0;
    border-radius: 0;
    font-size: 14px;
}
code {
    background: #1A1A1A;
    padding: 2px 6px;
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    font-size: 14px;
}
blockquote {
    border-left: 3px solid #00E5FF;
    padding-left: 16px;
    color: #AAAAAA;
    margin: 12px 0;
}
table {
    border-collapse: collapse;
    width: 100%;
    margin: 12px 0;
    overflow-x: auto;
    display: block;
}
th, td {
    border: 1px solid #333333;
    padding: 8px;
    text-align: left;
}
th {
    font-weight: bold;
    background: #0D0D0D;
}
ul, ol {
    padding-left: 24px;
    margin: 8px 0;
    color: #FFFFFF;
}
li { margin: 4px 0; }
hr {
    border: none;
    border-top: 1px solid #333333;
    margin: 16px 0;
}
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()

private fun articleTagValue(tagsJson: String, key: String): String? = runCatching {
    Json.parseToJsonElement(tagsJson).jsonArray
        .firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key
        }
        ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
}.getOrNull()
