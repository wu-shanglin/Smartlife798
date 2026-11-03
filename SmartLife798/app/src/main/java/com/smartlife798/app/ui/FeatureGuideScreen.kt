@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartlife798.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Settings
import com.smartlife798.app.ui.theme.ExpressiveEffects
import com.smartlife798.app.ui.theme.ExpressiveSpatial
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A single full-screen card sequence for first use, updates and explicit replay. */
@Composable
fun FeatureGuideScreen(
    deck: com.smartlife798.app.core.GuideDeck,
    onRead: (String) -> Unit,
    onFinish: () -> Unit,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    val lightIcons = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
    }
    val topics = deck.topics
    var currentKey by rememberSaveable(deck.id) { mutableStateOf(topics.first().key) }
    // A recreated VM may have removed already-read cards from the batch.
    val page = topics.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    BackHandler { if (page > 0) currentKey = topics[page - 1].key else onExit() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (deck.manual) "使用指南" else if (deck.firstUse) "欢迎使用慧生活798" else "了解新功能",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onFinish) { Text("跳过") }
            }
            Text("${page + 1} / ${topics.size}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (page + 1f) / topics.size }, modifier = Modifier.fillMaxWidth())
            AnimatedContent(page, modifier = Modifier.weight(1f), label = "guidePage", transitionSpec = {
                (fadeIn(animationSpec = ExpressiveEffects) + scaleIn(animationSpec = ExpressiveSpatial, initialScale = 0.94f)) togetherWith fadeOut(animationSpec = ExpressiveEffects)
            }) { index ->
                val topic = topics[index]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (topic.icon == com.smartlife798.app.core.GuideIcon.SCAN)
                                com.smartlife798.app.QrGlyph(MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(48.dp))
                            else Icon(when (topic.icon) {
                                com.smartlife798.app.core.GuideIcon.WALLET -> Icons.Filled.ShoppingCart
                                com.smartlife798.app.core.GuideIcon.PREFERENCES -> Icons.Filled.Settings
                                else -> Icons.Filled.Home
                            }, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text(topic.title, style = MaterialTheme.typography.displaySmall)
                    Text(topic.body, style = MaterialTheme.typography.bodyLarge)
                    Text("在哪里：${topic.location}", style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page > 0) OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { currentKey = topics[page - 1].key }) { Text("上一步") }
                Button(shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp), onClick = {
                    onRead(topics[page].key)
                    if (page < topics.lastIndex) currentKey = topics[page + 1].key else onFinish()
                }) { Text(if (page == topics.lastIndex) "开始使用" else "下一步") }
            }
        }
    }
}
