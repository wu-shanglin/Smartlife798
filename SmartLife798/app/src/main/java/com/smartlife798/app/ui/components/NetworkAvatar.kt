package com.smartlife798.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

/** Loading is supplied by the VM; changing identity disposes the old image and request. */
@Composable
fun NetworkAvatar(
    image: String?,
    name: String,
    load: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val currentLoad by rememberUpdatedState(load)
    key(image, name) {
        val bitmap by produceState<Bitmap?>(null, image) {
            value = image?.takeIf { it.isNotBlank() }?.let { currentLoad(it) }
        }
        val alpha by animateFloatAsState(if (bitmap == null) 0f else 1f, label = "avatarArrival")
        Box(
            modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).ifBlank { "慧" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha },
                )
            }
        }
    }
}
