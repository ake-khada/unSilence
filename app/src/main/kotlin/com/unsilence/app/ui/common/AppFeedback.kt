package com.unsilence.app.ui.common

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.White
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal data class ActivityNotice(
    val account: String,
    val message: String,
    val duration: SnackbarDuration = SnackbarDuration.Short,
)

@Composable
internal fun AppFeedback(
    account: String,
    activityNotices: Flow<ActivityNotice>,
    content: @Composable () -> Unit,
) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val show: (String) -> Unit = remember(host, scope) {
        { message ->
            scope.launch { host.showSnackbar(message) }
            Unit
        }
    }
    LaunchedEffect(activityNotices, account) {
        activityNotices.collect { notice ->
            if (notice.account == account) {
                host.showSnackbar(notice.message, duration = notice.duration)
            }
        }
    }
    CompositionLocalProvider(LocalShowSnackbar provides show, content = content)
    val notice = host.currentSnackbarData ?: return

    // Limit the notice window to the message. Accessibility services can reach
    // it alongside the focused editor, and touches outside it reach the app.
    val ownerView = LocalView.current
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing.union(WindowInsets.ime)
    val bottom = safe.getBottom(density)
    val availableWidth = with(density) { ownerView.rootView.width.toDp() - 48.dp }
    val clearance = if (WindowInsets.ime.getBottom(density) > 0) Spacing.medium else Sizing.bottomNavHeight + Spacing.medium
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        val entry = remember(notice) { Animatable(1f) }
        var entering by remember(notice) { mutableStateOf(true) }
        LaunchedEffect(notice) {
            entry.animateTo(0f, tween(durationMillis = 200, easing = LinearOutSlowInEasing))
            entering = false
        }
        SideEffect {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            val accessibility = ownerView.context.getSystemService(AccessibilityManager::class.java)
            if (accessibility.isEnabled) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
            val params = window.attributes
            params.windowAnimations = 0
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.fitInsetsTypes = 0
            // Reserve travel inside the window during entry. Once settled, keep
            // the same screen position with a window only as tall as the notice.
            params.y = bottom + if (entering) 0 else with(density) { clearance.roundToPx() }
            window.attributes = params
        }
        Box(Modifier.clipToBounds().padding(bottom = if (entering) clearance else 0.dp)) {
            key(notice) {
                SnackbarHost(host, Modifier.graphicsLayer {
                    translationY = (size.height + clearance.toPx()) * entry.value
                }) { data ->
                    Surface(
                        modifier = Modifier.widthIn(max = availableWidth.coerceIn(1.dp, 360.dp)),
                        shape = RoundedCornerShape(18.dp),
                        color = Surface2.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    ) {
                        Text(
                            text = data.visuals.message,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            color = White,
                            style = AppTextStyles.body,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
