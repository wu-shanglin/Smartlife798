package com.smartlife798.app.ui.components

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
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** Keep the expanded anchor independent of content, scrolling and animated inset consumption. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dismissEnabled: Boolean = true,
    balancedMotion: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val viewportHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.9f).toDp()
    }
    val inheritedMotion = MaterialTheme.motionScheme
    val sheetMotion = remember(inheritedMotion, balancedMotion) {
        if (balancedMotion) BalancedSheetMotion(inheritedMotion) else inheritedMotion
    }
    // Material3 alpha14 uses FastEffects for hide(), unlike DefaultSpatial for show().
    // Use a critically damped exit: an underdamped entrance spring crosses Hidden,
    // then rebounds into the viewport while the invisible dialog still intercepts input.
    // Keep the scrim on the same non-bouncy envelope; child effects remain inherited.
    MaterialExpressiveTheme(motionScheme = sheetMotion) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            sheetGesturesEnabled = dismissEnabled,
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = dismissEnabled,
                shouldDismissOnClickOutside = dismissEnabled,
            ),
            // Keep the parent constraints intact: the framework needs the full window
            // height for its hidden/expanded anchors. Constrain the content instead.
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
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
    private fun <T> exitSpec(): FiniteAnimationSpec<T> {
        val spatial = inherited.defaultSpatialSpec<T>()
        return if (spatial is SpringSpec<T>) spring(
            dampingRatio = maxOf(1f, spatial.dampingRatio),
            stiffness = spatial.stiffness,
            visibilityThreshold = spatial.visibilityThreshold,
        ) else inherited.defaultEffectsSpec()
    }

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = exitSpec()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = exitSpec()
}
