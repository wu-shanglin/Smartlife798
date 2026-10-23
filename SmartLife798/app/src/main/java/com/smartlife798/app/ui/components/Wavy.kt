package com.smartlife798.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material-3-Expressive-flavoured loading indicator: two travelling sine waves.
 * Rendered locally because material3 1.4.0 ships the expressive theme/navigation
 * but not the 1.5 "wavy" progress components.
 */
@Composable
fun WavyLinearProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    heightDp: Int = 6,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(
        modifier = modifier.height(heightDp.dp),
    ) {
        val h = size.height
        val w = size.width
        val amplitude = h * 0.42f
        val mid = h / 2f
        val stroke = Stroke(width = h * 0.55f, cap = StrokeCap.Round)

        drawLine(
            color = track,
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = h * 0.55f,
            cap = StrokeCap.Round,
        )

        fun wave(offsetPhase: Float, alpha: Float) {
            val path = Path()
            val steps = 48
            for (i in 0..steps) {
                val x = w * i / steps
                val y = mid + amplitude * sin((x / w * 3.2f + offsetPhase + phase) * 2f * PI.toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color.copy(alpha = alpha), style = stroke)
        }
        wave(0f, 0.95f)
        wave(0.32f, 0.45f)
    }
}

@Composable
fun WavyCircularProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    sizeDp: Int = 30,
) {
    val transition = rememberInfiniteTransition(label = "wavyCircle")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val squash by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "squash",
    )
    Canvas(modifier = modifier.size(sizeDp.dp)) {
        val radius = size.minDimension / 2f - 3f
        rotate(rotation) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 250f,
                useCenter = false,
                style = Stroke(width = 3.2f, cap = StrokeCap.Round),
                topLeft = Offset(center.x - radius, center.y - radius * squash),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2 * squash),
            )
        }
    }
}
