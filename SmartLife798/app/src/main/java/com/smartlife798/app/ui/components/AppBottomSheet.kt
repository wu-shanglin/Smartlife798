package com.smartlife798.app.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep the expanded anchor independent of content, scrolling and animated inset consumption. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dismissEnabled: Boolean = true,
    balancedMotion: Boolean = false,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val viewportHeight = with(LocalDensity.current) {
        (windowHeight * 0.9f).toDp()
    }
    val inheritedMotion = MaterialTheme.motionScheme
    val sheetMotion = remember(inheritedMotion, balancedMotion) {
        if (balancedMotion) BalancedSheetMotion(inheritedMotion) else inheritedMotion
    }
    val backTravel = remember(sheetState) { Animatable(0f) }
    val canDismiss = rememberUpdatedState(dismissEnabled)
    val back = rememberUpdatedState(onBack)
    MaterialExpressiveTheme(motionScheme = sheetMotion) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = if (onBack == null) Modifier else Modifier.graphicsLayer {
                if (backTravel.value > 0f) {
                    // Follow the same vertical path as hide(). Holding a fraction of the
                    // remaining distance at commit keeps the first exit frame continuous.
                    translationY = (windowHeight - sheetState.requireOffset())
                        .coerceAtLeast(0f) * backTravel.value
                } else {
                    translationY = 0f
                }
            },
            sheetState = sheetState,
            sheetGesturesEnabled = dismissEnabled,
            scrimColor = BottomSheetDefaults.ScrimColor.let {
                if (onBack == null) it else it.copy(alpha = it.alpha * (1f - backTravel.value))
            },
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = dismissEnabled && onBack == null,
                shouldDismissOnClickOutside = dismissEnabled,
            ),
            // Keep the parent constraints intact: the framework needs the full window
            // height for its hidden/expanded anchors. Constrain the content instead.
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            if (onBack != null) {
                // Register in the modal window so Back cannot reach the covered page.
                PredictiveBackHandler { events ->
                    try {
                        events.collect { event ->
                            if (canDismiss.value && sheetState.isVisible) {
                                backTravel.snapTo(event.progress.coerceIn(0f, 1f) * 0.35f)
                            }
                        }
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (canDismiss.value) backTravel.animateTo(0f, sheetTransitionSpec())
                        return@PredictiveBackHandler
                    }
                    if (canDismiss.value) back.value?.invoke()
                }
            }
            Column(
                Modifier.fillMaxWidth().height(viewportHeight)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                MaterialExpressiveTheme(motionScheme = inheritedMotion) { content() }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
private class BalancedSheetMotion(private val inherited: MotionScheme) : MotionScheme by inherited {
    // Material3 uses different tokens for opening, closing and the scrim.
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = sheetTransitionSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = sheetTransitionSpec()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = sheetTransitionSpec()
}

private fun <T> sheetTransitionSpec(): FiniteAnimationSpec<T> =
    tween(durationMillis = 420, easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f))
