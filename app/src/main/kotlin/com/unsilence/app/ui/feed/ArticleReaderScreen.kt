package com.unsilence.app.ui.feed

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

@Composable
fun ArticleReaderScreen(row: FeedRow, onDismiss: () -> Unit) {
    val title = articleTagValue(row.tags, "title")
    val image = articleTagValue(row.tags, "image")
    val context = LocalContext.current

    val bodyHtml = remember(row.content) { markdownToHtml(row.content) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Black),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── Top bar ────────────────────────────────────────────────────
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
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // ── Banner image (16:9, magazine-style header) ─────────────────
                if (!image.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model              = image,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(
                                bottomStart = Sizing.mediaCornerRadius,
                                bottomEnd   = Sizing.mediaCornerRadius,
                            )),
                    )
                }

                // ── Title ──────────────────────────────────────────────────────
                if (!title.isNullOrBlank()) {
                    Text(
                        text       = title,
                        color      = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp,
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

                // ── Body content (WebView) ──────────────────────────────────────
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
