package com.smartlife798.app.ui.components

import android.graphics.Bitmap
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
import androidx.compose.ui.layout.ContentScale

/** Keep this state above lazy items. Identity changes discard old account pixels immediately. */
@Composable
fun rememberAvatar(identity: String?, image: String?, load: suspend (String) -> Bitmap?): Bitmap? {
    val currentLoad by rememberUpdatedState(load)
    return key(identity, image) {
        val bitmap by produceState<Bitmap?>(null, image) {
            value = image?.takeIf { it.isNotBlank() }?.let { currentLoad(it) }
        }
        bitmap
    }
}

@Composable
fun NetworkAvatar(bitmap: Bitmap?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text(name.take(1).ifBlank { "慧" }, style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize())
        }
    }
}
