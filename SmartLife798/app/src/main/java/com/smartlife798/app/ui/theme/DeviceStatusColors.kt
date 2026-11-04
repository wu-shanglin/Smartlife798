package com.smartlife798.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.smartlife798.app.core.DeviceStateKind

/** Device availability has a consistent meaning across brand and dynamic themes. */
fun ColorScheme.deviceStatusColors(kind: DeviceStateKind): Pair<Color, Color> {
    val dark = surface.luminance() < 0.5f
    return when (kind) {
        DeviceStateKind.ONLINE -> if (dark) Color(0xFF153D27) to Color(0xFFA8E8B7)
            else Color(0xFFDDF4E3) to Color(0xFF176735)
        DeviceStateKind.RUNNING, DeviceStateKind.STARTING -> if (dark) Color(0xFF4A3908) to Color(0xFFFFE08A)
            else Color(0xFFFFF0C2) to Color(0xFF705300)
        DeviceStateKind.OFFLINE -> surfaceVariant to onSurfaceVariant
        DeviceStateKind.DISABLED -> errorContainer to onErrorContainer
        DeviceStateKind.BLUETOOTH -> secondaryContainer to onSecondaryContainer
    }
}
