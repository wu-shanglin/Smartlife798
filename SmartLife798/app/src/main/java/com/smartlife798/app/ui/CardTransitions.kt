package com.smartlife798.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

private data class CardScene(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val entryId: String,
    val active: State<Boolean>,
)

private val LocalCardScene = staticCompositionLocalOf<CardScene?> { null }
private val LocalPageCardKey = staticCompositionLocalOf<CardTransitionKey?> { null }
// One unhurried morph, shared by the container, its corners and the page's own cross-fade.
//
// Sharing one spring is the whole point. When the page faded on its own fixed tween while
// the container took longer, the page reached full screen with the card's label still
// expanding over it ("the card text grows, then the page arrives"). Driving the bounds, the
// corners and the fade from the same spring keeps them together at any pace, so this can
// stay relaxed without splitting the animation into two beats.
private val CardMorphBounds = spring<Rect>(dampingRatio = 0.9f, stiffness = 650f)
private val CardMorphCorner = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.9f, stiffness = 650f)
// Entering: the page fades in on the container's own spring, so the card's label, the bounds
// and the page content all arrive together at whatever pace the morph is tuned to.
private val CardMorphEnter = spring<Float>(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 0.01f)
// Leaving: the page has to be gone *before* the bounds reach card size, so this is a fixed
// short fade rather than a spring. Any spring tied to the container (even a stiff one) still
// leaves page content inside the card area on the last frame, and the box then snaps to the
// card. A 220ms tween always finishes while the collapse is still running.
private val CardMorphExit = tween<Float>(durationMillis = 200)

/** A retained source must change visibility in the same transition as its detail. */
@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun AnimatedVisibilityScope.rememberCardSourceVisibility(): AnimatedVisibilityScope {
    val sourceTransition = transition.createChildTransition("retained card source") { phase ->
        if (phase == EnterExitState.Visible) EnterExitState.PostExit else EnterExitState.Visible
    }
    return remember(sourceTransition) {
        object : AnimatedVisibilityScope {
            override val transition = sourceTransition
        }
    }
}

/** A retained outgoing scene can still be composed after navigation has already moved on. */
@Composable
internal fun cardSceneActiveState(): State<Boolean> =
    LocalCardScene.current?.active ?: rememberUpdatedState(true)

@Composable
fun SharedCardScene(
    shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    entryId: String,
    active: Boolean = true,
    clipWhenInactive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val activeState = rememberUpdatedState(active)
    // Retain one navigation context across recompositions. Only the existing
    // State's value needs to change when this scene becomes active or inactive.
    val scene = remember(shared, visibility, entryId) { CardScene(shared, visibility, entryId, activeState) }
    CompositionLocalProvider(LocalCardScene provides scene) {
        // Full-screen pages retained above a popped source must not block touches.
        // Peer tabs retain their pixels until NavHost draws the replacement; hiding
        // them on currentEntry changes can precede the new scene by a whole frame.
        val semantics = if (!active)
            Modifier.clearAndSetSemantics { } else Modifier
        val outgoingClip = if (!active && clipWhenInactive) Modifier.graphicsLayer {
            clip = true
            shape = EmptySceneShape
        } else Modifier
        val inactiveInput = if (!active && !clipWhenInactive) Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        } else Modifier
        Box(Modifier.fillMaxSize().then(semantics).then(outgoingClip).then(inactiveInput)) { content() }
    }
}

private val EmptySceneShape = object : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect.Zero)
}

@Composable
fun currentCardSource(origin: CardOrigin): CardSource? =
    LocalCardScene.current?.let { CardSource(it.entryId, origin) }

/** Only side effects wait for entry; asynchronous content is free to render during the morph. */
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
        // The handoff layer draws only before sharedBounds takes over. The
        // fixed child viewport still avoids per-frame content remeasurement.
        Box(
            Modifier
                .cardHandoffOverlay(waitForSharedOverlay = source?.origin?.destination == CardDestination.DEVICE)
                .sharedCardPage(source, itemId)
                .fillMaxSize(),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

/** Bridge the navigation/overlay handoff without restoring the popped page's hit area. */
@Composable
private fun Modifier.cardHandoffOverlay(waitForSharedOverlay: Boolean): Modifier {
    val scene = LocalCardScene.current ?: return this
    val transition = scene.visibility.transition
    // Keep the layer node stable. Attaching it only on Back rebuilds the page's
    // modifier chain precisely when the source list starts handling a gesture.
    return with(scene.shared) {
        this@cardHandoffOverlay.renderInSharedTransitionScopeOverlay(
            zIndexInOverlay = 1f,
            renderInOverlay = {
                !scene.active.value && (!waitForSharedOverlay || !isTransitionActive) &&
                    transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
            },
        )
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
        val state = rememberSharedContentState(key)
        if (key.part == CardPart.TITLE) {
            // Labels and app-bar titles have different typography. Scale their complete
            // layouts within shared bounds instead of reflowing/clipping individual glyphs.
            this@cardElement.sharedBounds(
                sharedContentState = state,
                animatedVisibilityScope = scene.visibility,
                zIndexInOverlay = 2f,
            )
        } else {
            this@cardElement.sharedElement(
                sharedContentState = state,
                animatedVisibilityScope = scene.visibility,
                zIndexInOverlay = 2f,
            )
        }
    }
}

@Composable
private fun Modifier.cardBounds(key: CardTransitionKey?, page: Boolean): Modifier {
    val scene = LocalCardScene.current ?: return this
    if (key == null) return this
    val state = with(scene.shared) { rememberSharedContentState(key) }
    // Unmatched cards must not extend a peer-tab transition just to animate a clip.
    val corner = if (state.isMatchFound) {
        scene.visibility.transition.animateDp(
            transitionSpec = { CardMorphCorner }, label = "cardCorner",
        ) { phase ->
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
        // Only the page cross-fades; the source card stays fully opaque for the whole
        // transform, which is what makes the morph read as one container:
        //
        //  - Frame 0 shows the card. An opaque page at frame 0 would paint the scaled-down
        //    detail over the card the instant the finger lifts, which feels unresponsive and
        //    folds back to the wrong content when an entry is interrupted.
        //  - Fading the card as well multiplied both alphas, so the list showed through the
        //    dip and the detail looked like a second full-screen animation.
        //  - On the way back the card is still there, so the shrinking box lands on the
        //    card's own content instead of a scaled-down page that snaps at the last frame.
        this@cardBounds.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = scene.visibility,
            // Asymmetric on purpose: a fixed-duration tween would finish while the container
            // is still expanding (splitting the transform), and a matching exit spring would
            // leave page content inside the collapsed card.
            enter = if (page) fadeIn(CardMorphEnter) else EnterTransition.None,
            exit = if (page) fadeOut(CardMorphExit) else ExitTransition.None,
            boundsTransform = { _, _ -> CardMorphBounds },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                ContentScale.FillWidth,
                if (page) Alignment.TopStart else Alignment.Center,
            ),
            clipInOverlayDuringTransition = clip,
            zIndexInOverlay = if (page) 1f else 0f,
        )
    }
}
