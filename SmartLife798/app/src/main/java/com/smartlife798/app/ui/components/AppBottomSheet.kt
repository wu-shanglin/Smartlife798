package com.smartlife798.app.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
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
import kotlin.math.roundToInt

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
    val backTravel = remember(sheetState) { Animatable(0f) }
    val sheetMotion = remember(inheritedMotion, balancedMotion, backTravel) {
        if (balancedMotion) BalancedSheetMotion(inheritedMotion) { backTravel.value } else inheritedMotion
    }
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
                        if (canDismiss.value) backTravel.animateTo(0f, tween(180, easing = sheetEasing))
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
private class BalancedSheetMotion(
    inherited: MotionScheme,
    private val previewTravel: () -> Float,
) : MotionScheme by inherited {
    // Material3 uses different tokens for opening, closing and the scrim.
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = sheetTransitionSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = settleSpec()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = settleSpec()

    // Capture gesture travel when the animation starts, even if release arrives before the
    // last gesture frame recomposes. A shorter path keeps the same average travel speed.
    private fun <T> settleSpec(): FiniteAnimationSpec<T> = object : FiniteAnimationSpec<T> {
        override fun <V : AnimationVector> vectorize(
            converter: TwoWayConverter<T, V>,
        ): VectorizedFiniteAnimationSpec<V> = sheetTransitionSpec<T>(
            durationMillis = (SheetDurationMillis * (1f - previewTravel())).roundToInt(),
        ).vectorize(converter)
    }
}

private const val SheetDurationMillis = 360
private val sheetEasing = CubicBezierEasing(0.25f, 0.20f, 0.60f, 1f)

private fun <T> sheetTransitionSpec(durationMillis: Int = SheetDurationMillis): FiniteAnimationSpec<T> =
    tween(durationMillis = durationMillis, easing = sheetEasing)
