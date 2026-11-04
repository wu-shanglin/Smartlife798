package com.smartlife798.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Shared thumbtack silhouette for home, device lists and device actions. */
val PinIcon: ImageVector = ImageVector.Builder("Pin", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(16f, 9f); verticalLineTo(4f); horizontalLineTo(17f); verticalLineTo(2f)
        horizontalLineTo(7f); verticalLineTo(4f); horizontalLineTo(8f); verticalLineTo(9f)
        lineTo(5f, 12f); verticalLineTo(14f); horizontalLineTo(11f); verticalLineTo(22f)
        horizontalLineTo(13f); verticalLineTo(14f); horizontalLineTo(19f); verticalLineTo(12f)
        close()
    }
}.build()

@Composable
fun PinBadge(visible: Boolean = true) {
    val semantics = if (visible) Modifier else Modifier.clearAndSetSemantics { }
    Surface(modifier = semantics.drawWithContent { if (visible) drawContent() },
        color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(PinIcon, null, Modifier.size(16.dp))
            Text("置顶", style = MaterialTheme.typography.labelSmall)
        }
    }
}
