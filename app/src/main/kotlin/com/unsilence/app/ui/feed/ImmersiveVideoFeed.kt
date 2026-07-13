package com.unsilence.app.ui.feed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.WotInlineLabel
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.White
import com.unsilence.app.ui.thread.ThreadViewModel
import kotlinx.coroutines.delay

private const val IMMERSIVE_OWNER_ID = "feed-immersive"
private const val LOAD_MORE_DISTANCE = 3
private const val PROGRESS_POLL_MS = 250L
private const val SOUNDWAVE_FIRST_BASE_DP = 8f
private const val SOUNDWAVE_CENTER_BASE_DP = 14f
private const val SOUNDWAVE_LAST_BASE_DP = 6f
private const val SOUNDWAVE_FIRST_DURATION_MS = 900
private const val SOUNDWAVE_CENTER_DURATION_MS = 1_100
private const val SOUNDWAVE_LAST_DURATION_MS = 1_300
private val VIDEO_CORNER_RADIUS = 6.dp

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun ImmersiveVideoFeed(
    items: List<ImmersiveVideoItem>,
    holder: SharedPlayerHolder,
    thumbnailCache: VideoThumbnailCache,
    imageDimensionCache: ImageDimensionCache,
    callbacks: EventActionCallbacks,
    engagement: EngagementSnapshot,
    threadViewModel: ThreadViewModel,
    eventModelProvider: (String) -> EventModel?,
    sensitiveMode: SensitiveContentMode,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onPageSettled: (com.unsilence.app.data.memory.FeedRow) -> Unit,
    onExit: () -> Unit,
) {
    var sessionItems by remember {
        mutableStateOf(mergeImmersiveItems(emptyList(), items))
    }
    LaunchedEffect(items) {
        sessionItems = mergeImmersiveItems(sessionItems, items)
    }

    if (sessionItems.isEmpty()) {
        BackHandler(onBack = onExit)
        Box(modifier = Modifier.fillMaxSize().background(Black).consumeUnclaimedTaps()) {
            Text(
                text = "No playable videos match",
                color = TextSecondary,
                fontSize = AppType.body,
                modifier = Modifier.align(Alignment.Center),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = Spacing.medium)
                    .offset(y = (-6).dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImmersiveIconButton(onClick = onExit, contentDescription = "Exit videos") {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = White)
                }
            }
        }
        return
    }

    val player = holder.player
    val pagerState = rememberPagerState(pageCount = { sessionItems.size })
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(1),
    )
    var muted by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var renderedVideoUrl by remember { mutableStateOf<String?>(null) }
    var sheetEventId by remember { mutableStateOf<String?>(null) }
    var anchoredEventId by remember { mutableStateOf(sessionItems.first().row.id) }
    var revealedSensitiveIds by remember { mutableStateOf(emptySet<String>()) }
    val isPowerSaveMode = rememberPowerSaveMode()

    BackHandler(enabled = sheetEventId == null, onBack = onExit)

    DisposableEffect(holder, player) {
        holder.claim(IMMERSIVE_OWNER_ID)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 1f
        onDispose {
            player.playWhenReady = false
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ALL
            holder.releaseOwnership(IMMERSIVE_OWNER_ID)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedVideoUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (shouldClearRenderedFrame(reason)) renderedVideoUrl = null
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> if (!paused) player.playWhenReady = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(muted, player) {
        player.volume = if (muted) 0f else 1f
    }

    val settledItem = sessionItems.getOrNull(pagerState.settledPage)
    val settledItemBlocked = settledItem?.row?.let { row ->
        sensitiveMode == SensitiveContentMode.BLUR &&
            row.hasContentWarning && row.id !in revealedSensitiveIds
    } == true
    LaunchedEffect(
        settledItem?.row?.id,
        settledItem?.video?.videoUrl,
        settledItemBlocked,
    ) {
        val item = settledItem ?: return@LaunchedEffect
        anchoredEventId = item.row.id
        onPageSettled(item.row)
        callbacks.onWotSubjectsVisible(setOf(item.row.pubkey))
        if (settledItemBlocked) {
            player.playWhenReady = false
            paused = true
            return@LaunchedEffect
        }
        holder.claim(IMMERSIVE_OWNER_ID)
        val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUrl != item.video.videoUrl || player.mediaItemCount == 0) {
            renderedVideoUrl = null
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(item.video.videoUrl))
            player.prepare()
        }
        paused = false
        player.playWhenReady = true
    }

    // Preserve the viewed event when live inserts prepend rows to the filtered feed.
    LaunchedEffect(sessionItems.map { it.row.id }) {
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val anchoredIndex = sessionItems.indexOfFirst { it.row.id == anchoredEventId }
        if (anchoredIndex >= 0 && anchoredIndex != pagerState.currentPage) {
            pagerState.scrollToPage(anchoredIndex)
        }
    }

    val preloadIndex = immersivePreloadIndex(
        currentIndex = pagerState.settledPage,
        itemCount = sessionItems.size,
        isPowerSaveMode = isPowerSaveMode,
    )
    LaunchedEffect(preloadIndex, sessionItems) {
        val next = preloadIndex?.let(sessionItems::getOrNull) ?: return@LaunchedEffect
        // Decoder-free warm-up: MMR extracts and releases one scaled first frame,
        // priming the media URL while keeping the thermal/decoder budget bounded.
        thumbnailCache.getThumbnail(next.video.videoUrl)
    }

    LaunchedEffect(pagerState.settledPage, sessionItems.size, isLoadingMore) {
        if (!isLoadingMore && pagerState.settledPage >= sessionItems.size - 1 - LOAD_MORE_DISTANCE) {
            onLoadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Black).consumeUnclaimedTaps()) {
        VerticalPager(
            state = pagerState,
            flingBehavior = flingBehavior,
            beyondViewportPageCount = 0,
            userScrollEnabled = sheetEventId == null,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = sessionItems[page]
            val active = page == pagerState.settledPage
            val blocked = sensitiveMode == SensitiveContentMode.BLUR &&
                item.row.hasContentWarning && item.row.id !in revealedSensitiveIds
            ImmersiveVideoPage(
                item = item,
                player = player,
                active = active,
                paused = paused && active && !blocked,
                frameReady = renderedVideoUrl == item.video.videoUrl,
                sensitiveBlocked = blocked,
                thumbnailCache = thumbnailCache,
                onTogglePlayback = {
                    if (active) {
                        if (blocked) {
                            revealedSensitiveIds = revealedSensitiveIds + item.row.id
                        } else {
                            paused = !paused
                            player.playWhenReady = !paused
                        }
                    }
                },
                onLongPress = { callbacks.onLongPress?.invoke(item.row) },
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = Spacing.medium)
                .offset(y = (-6).dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImmersiveIconButton(
                onClick = { muted = !muted },
                contentDescription = if (muted) "Unmute" else "Mute",
            ) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = White,
                )
            }
            Spacer(Modifier.width(Spacing.micro))
            ImmersiveIconButton(onClick = onExit, contentDescription = "Exit videos") {
                Icon(Icons.Filled.Close, contentDescription = null, tint = White)
            }
        }

        settledItem?.let { item ->
            ImmersiveAuthorBar(
                item = item,
                callbacks = callbacks,
                playing = !paused && !settledItemBlocked,
                onOpenSheet = { sheetEventId = item.row.id },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            PlaybackProgressComet(
                player = player,
                eventId = item.row.id,
                videoUrl = item.video.videoUrl,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    val sheetItem = sheetEventId?.let { id -> sessionItems.firstOrNull { it.row.id == id } }
    if (sheetItem != null) {
        ImmersiveEngagementSheet(
            item = sheetItem,
            model = eventModelProvider(sheetItem.row.id) ?: sheetItem.row.toEventModel(),
            callbacks = callbacks,
            engagement = engagement,
            threadViewModel = threadViewModel,
            eventModelProvider = eventModelProvider,
            thumbnailCache = thumbnailCache,
            imageDimensionCache = imageDimensionCache,
            sensitiveMode = sensitiveMode,
            onDismiss = { sheetEventId = null },
        )
    }
}

private fun Modifier.consumeUnclaimedTaps(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true)
        waitForUpOrCancellation()?.consume()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ImmersiveVideoPage(
    item: ImmersiveVideoItem,
    player: ExoPlayer,
    active: Boolean,
    paused: Boolean,
    frameReady: Boolean,
    sensitiveBlocked: Boolean,
    thumbnailCache: VideoThumbnailCache,
    onTogglePlayback: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.Center) {
        VideoThumbnailImage(
            model = item.video,
            thumbnailCache = thumbnailCache,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .then(if (sensitiveBlocked) Modifier.blur(24.dp) else Modifier),
        )
        if (active && !sensitiveBlocked) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onReset = { it.player = null },
                onRelease = { it.player = null },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (frameReady) 1f else 0f),
            )
            if (!frameReady) {
                // SurfaceView retains the previous stream's aspect until the next
                // first frame. Keep the destination poster above that stale surface
                // so its temporary black rectangle is never user-visible.
                VideoThumbnailImage(
                    model = item.video,
                    thumbnailCache = thumbnailCache,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        VideoCornerMasks(
            aspectRatio = item.video.aspectRatio,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.row.id) {
                    detectTapGestures(
                        onTap = { onTogglePlayback() },
                        onLongPress = { onLongPress() },
                    )
                },
        )
        if (paused) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.52f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Paused",
                    tint = White,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        if (sensitiveBlocked) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.row.contentWarningReason?.takeIf { it.isNotBlank() }
                        ?: "Sensitive content · tap to reveal",
                    color = White,
                    fontSize = AppType.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VideoCornerMasks(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val videoAspect = aspectRatio.coerceAtLeast(0.01f)
        val viewportAspect = size.width / size.height
        val videoWidth: Float
        val videoHeight: Float
        if (videoAspect >= viewportAspect) {
            videoWidth = size.width
            videoHeight = size.width / videoAspect
        } else {
            videoHeight = size.height
            videoWidth = size.height * videoAspect
        }
        val left = (size.width - videoWidth) / 2f
        val top = (size.height - videoHeight) / 2f
        val right = left + videoWidth
        val bottom = top + videoHeight
        val radius = VIDEO_CORNER_RADIUS.toPx().coerceAtMost(minOf(videoWidth, videoHeight) / 2f)

        val masks = listOf(
            Path().apply {
                moveTo(left, top)
                lineTo(left + radius, top)
                quadraticTo(left, top, left, top + radius)
                close()
            },
            Path().apply {
                moveTo(right, top)
                lineTo(right - radius, top)
                quadraticTo(right, top, right, top + radius)
                close()
            },
            Path().apply {
                moveTo(right, bottom)
                lineTo(right - radius, bottom)
                quadraticTo(right, bottom, right, bottom - radius)
                close()
            },
            Path().apply {
                moveTo(left, bottom)
                lineTo(left + radius, bottom)
                quadraticTo(left, bottom, left, bottom - radius)
                close()
            },
        )
        masks.forEach { drawPath(it, Black) }
    }
}

@Composable
private fun ImmersiveAuthorBar(
    item: ImmersiveVideoItem,
    callbacks: EventActionCallbacks,
    playing: Boolean,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = item.row
    val profile = collectProfileAsState(row.pubkey, callbacks.profileFlow)
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
        ?: row.displayName
        ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"
    val caption = remember(row.content, item.video.videoUrl) {
        row.content.replace(item.video.videoUrl, "").trim().replace('\n', ' ')
    }
    var dragAmount by remember(row.id) { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f)),
                ),
            )
            .clickable(onClick = onOpenSheet)
            .pointerInput(row.id) {
                detectVerticalDragGestures(
                    onDragStart = { dragAmount = 0f },
                    onVerticalDrag = { change, amount ->
                        dragAmount += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (dragAmount < -24.dp.toPx()) onOpenSheet()
                        dragAmount = 0f
                    },
                    onDragCancel = { dragAmount = 0f },
                )
            }
            .navigationBarsPadding()
            .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarImage(
                pubkey = row.pubkey,
                picture = profile?.picture ?: row.authorPicture,
                sizeDp = 34.dp,
                lookupProfile = callbacks.lookupProfile,
                profileFlow = callbacks.profileFlow,
                modifier = Modifier
                    .size(34.dp)
                    .clickable { callbacks.onAuthorClick(row.pubkey) },
            )
            Spacer(Modifier.width(Spacing.small))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayName,
                    color = White,
                    fontSize = AppType.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val lookup = callbacks.wotLookup?.invoke(row.pubkey)
                if (lookup is WotLookup.Scored) {
                    Spacer(Modifier.width(Spacing.small))
                    WotInlineLabel(assertion = lookup.assertion, prefix = "")
                }
            }
            SoundwaveGlyph(
                playing = playing,
                modifier = Modifier.size(34.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.small)
                .height(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    color = White.copy(alpha = 0.86f),
                    fontSize = AppType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SoundwaveGlyph(
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.semantics { contentDescription = "Show engagement" },
        contentAlignment = Alignment.Center,
    ) {
        if (playing) AnimatedSoundwaveBars() else SoundwaveBars()
    }
}

@Composable
private fun AnimatedSoundwaveBars() {
    val transition = rememberInfiniteTransition(label = "soundwave")
    val firstHeight = transition.animateFloat(
        initialValue = SOUNDWAVE_FIRST_BASE_DP - 3f,
        targetValue = SOUNDWAVE_FIRST_BASE_DP + 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(SOUNDWAVE_FIRST_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "soundwave-first",
    )
    val centerHeight = transition.animateFloat(
        initialValue = SOUNDWAVE_CENTER_BASE_DP - 3f,
        targetValue = SOUNDWAVE_CENTER_BASE_DP + 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(SOUNDWAVE_CENTER_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "soundwave-center",
    )
    val lastHeight = transition.animateFloat(
        initialValue = SOUNDWAVE_LAST_BASE_DP - 3f,
        targetValue = SOUNDWAVE_LAST_BASE_DP + 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(SOUNDWAVE_LAST_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "soundwave-last",
    )
    SoundwaveBars(firstHeight, centerHeight, lastHeight)
}

@Composable
private fun SoundwaveBars(
    firstHeightState: State<Float>? = null,
    centerHeightState: State<Float>? = null,
    lastHeightState: State<Float>? = null,
) {
    Canvas(modifier = Modifier.width(12.5.dp).height(20.dp)) {
        val barWidth = 2.5.dp.toPx()
        val stride = 5.dp.toPx()

        fun drawBar(index: Int, heightDp: Float, alpha: Float) {
            val heightPx = heightDp.dp.toPx()
            drawRoundRect(
                color = White.copy(alpha = alpha),
                topLeft = Offset(index * stride, size.height - heightPx),
                size = Size(barWidth, heightPx),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }

        drawBar(0, firstHeightState?.value ?: SOUNDWAVE_FIRST_BASE_DP, 0.7f)
        drawBar(1, centerHeightState?.value ?: SOUNDWAVE_CENTER_BASE_DP, 0.85f)
        drawBar(2, lastHeightState?.value ?: SOUNDWAVE_LAST_BASE_DP, 0.65f)
    }
}

@Composable
private fun ImmersiveIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.58f), CircleShape),
    ) {
        Box(
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun PlaybackProgressComet(
    player: ExoPlayer,
    eventId: String,
    videoUrl: String,
    modifier: Modifier = Modifier,
) {
    var progress by remember(eventId) { mutableFloatStateOf(0f) }
    var durationKnown by remember(eventId) { mutableStateOf(false) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(PROGRESS_POLL_MS.toInt(), easing = LinearEasing),
        label = "video-progress",
    )
    LaunchedEffect(player, eventId, videoUrl) {
        while (true) {
            val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
            val duration = player.duration.takeIf { currentUrl == videoUrl && it > 0 }
            durationKnown = duration != null
            progress = if (duration != null) {
                (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            } else 0f
            delay(PROGRESS_POLL_MS)
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(4.dp),
    ) {
        if (!durationKnown) return@Canvas
        val radius = 2.dp.toPx()
        val tailHeight = 2.dp.toPx()
        val dotX = radius + animatedProgress * (size.width - radius * 2f)
        val tailStart = (dotX - 24.dp.toPx()).coerceAtLeast(0f)
        val tailWidth = dotX - tailStart
        if (tailWidth > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.7f to White.copy(alpha = 0.12f),
                        1f to White.copy(alpha = 0.3f),
                    ),
                    startX = tailStart,
                    endX = dotX,
                ),
                topLeft = Offset(tailStart, (size.height - tailHeight) / 2f),
                size = Size(tailWidth, tailHeight),
                cornerRadius = CornerRadius(tailHeight / 2f),
            )
        }
        drawCircle(
            color = White.copy(alpha = 0.9f),
            radius = radius,
            center = Offset(dotX, size.height / 2f),
        )
    }
}

@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var enabled by remember { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    DisposableEffect(context, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                enabled = powerManager?.isPowerSaveMode == true
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return enabled
}
