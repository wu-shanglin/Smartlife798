@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.smartlife798.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

private data class CardScene(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val entryId: String,
)

private val LocalCardScene = staticCompositionLocalOf<CardScene?> { null }
private val LocalPageCardKey = staticCompositionLocalOf<CardTransitionKey?> { null }

@Composable
fun SharedCardScene(
    shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    entryId: String,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCardScene provides CardScene(shared, visibility, entryId), content = content)
}

@Composable
fun currentCardSource(origin: CardOrigin): CardSource? =
    LocalCardScene.current?.let { CardSource(it.entryId, origin) }

/** Fetch immediately, but publish the first detail layout only after the entry has settled. */
@Composable
fun rememberAwaitCardEntry(): suspend () -> Unit {
    val scene = LocalCardScene.current
    return remember(scene) {
        suspend {
            if (scene != null) {
                snapshotFlow {
                    val transition = scene.visibility.transition
                    transition.currentState == EnterExitState.Visible &&
                        transition.targetState == EnterExitState.Visible &&
                        !transition.isRunning && !scene.shared.isTransitionActive
                }.first { it }
            }
        }
    }
}

@Composable
fun Modifier.sharedCard(origin: CardOrigin, itemId: String = ""): Modifier =
    cardBounds(currentCardSource(origin)?.key(itemId), page = false)

@Composable
fun Modifier.sharedCardPage(source: CardSource?, itemId: String = ""): Modifier =
    cardBounds(source?.key(itemId), page = true)

@Composable
fun SharedCardPage(source: CardSource?, itemId: String = "", content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageCardKey provides source?.key(itemId)) {
        Box(Modifier.sharedCardPage(source, itemId).fillMaxSize()) { content() }
    }
}

@Composable
fun Modifier.sharedCardElement(
    origin: CardOrigin,
    itemId: String = "",
    part: CardPart = CardPart.TITLE,
): Modifier = cardElement(currentCardSource(origin)?.key(itemId)?.copy(part = part))

@Composable
fun Modifier.sharedCardPageElement(part: CardPart = CardPart.TITLE): Modifier =
    cardElement(LocalPageCardKey.current?.copy(part = part))

@Composable
private fun Modifier.cardElement(key: CardTransitionKey?): Modifier {
    val scene = LocalCardScene.current ?: return this
    if (key == null) return this
    return with(scene.shared) {
        this@cardElement.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = scene.visibility,
            zIndexInOverlay = 2f,
        )
    }
}

@Composable
private fun Modifier.cardBounds(key: CardTransitionKey?, page: Boolean): Modifier {
    val scene = LocalCardScene.current ?: return this
    if (key == null) return this
    val state = with(scene.shared) { rememberSharedContentState(key) }
    // Unmatched cards must not extend a peer-tab transition just to animate a clip.
    val corner = if (state.isMatchFound) {
        scene.visibility.transition.animateDp(label = "cardCorner") { phase ->
            if ((phase == EnterExitState.Visible) == page) 0.dp else key.origin.cornerDp.dp
        }
    } else {
        null
    }
    // Read the animated value during drawing, not composition. Reuse the path instead
    // of recomposing every shared container and allocating a new shape each frame.
    val clip = remember(corner, page, key.origin) {
        object : SharedTransitionScope.OverlayClip {
            private val path = Path()

            override fun getClipPath(
                sharedContentState: SharedTransitionScope.SharedContentState,
                bounds: Rect,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Path {
                val radius = with(density) {
                    (corner?.value ?: if (page) 0.dp else key.origin.cornerDp.dp)
                        .coerceIn(0.dp, key.origin.cornerDp.dp).toPx()
                }
                path.reset()
                path.addRoundRect(RoundRect(bounds, CornerRadius(radius)))
                return path
            }
        }
    }
    return with(scene.shared) {
        this@cardBounds.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = scene.visibility,
            clipInOverlayDuringTransition = clip,
            zIndexInOverlay = if (page) 1f else 0f,
        )
    }
}
