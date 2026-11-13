@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.smartlife798.app.R
import com.smartlife798.app.core.GuideDeck
import com.smartlife798.app.core.GuideIcon
import com.smartlife798.app.core.GuideTopic
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.ui.theme.ExpressiveEffects

/** Shared by first use, unread updates and explicit replay. */
@Composable
fun FeatureGuideScreen(
    deck: GuideDeck,
    onRead: (String) -> Unit,
    onFinish: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val darkIcons = colors.background.luminance() >= 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }

    val topics = deck.topics
    var currentKey by rememberSaveable(deck.id) { mutableStateOf(topics.first().key) }
    // A recreated VM may have removed already-read cards from the batch.
    val page = topics.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    val lastPage = page == topics.lastIndex
    val previous = { currentKey = topics[page - 1].key }
    val advance = {
        // An input from a button still being recomposed must only advance its own step.
        val activeIndex = topics.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        if (activeIndex == page) {
            onRead(topics[page].key)
            if (lastPage) onFinish() else currentKey = topics[page + 1].key
        }
    }
    BackHandler { if (page > 0) previous() else onExit() }

    val entrance = remember(deck.id) { Animatable(0.96f) }
    LaunchedEffect(entrance) { entrance.animateTo(1f, ExpressiveEffects) }
    Box(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxSize().padding(horizontal = 24.dp),
        ) {
            GuideHeader(
                title = when {
                    deck.manual -> "使用指南"
                    deck.firstUse -> "慧生活 798"
                    else -> "了解新功能"
                },
                page = page,
                count = topics.size,
            )
            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (fadeIn(ExpressiveEffects) + slideInHorizontally { direction * it / 18 })
                        .togetherWith(fadeOut(ExpressiveEffects))
                },
                label = "guidePage",
            ) { index ->
                val visibility = if (index == page) Modifier else Modifier.semantics { hideFromAccessibility() }
                BoxWithConstraints(Modifier.fillMaxSize().then(visibility)) {
                    val viewportHeight = maxHeight
                    val compact = viewportHeight < 400.dp
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .heightIn(min = viewportHeight).padding(vertical = if (compact) 12.dp else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp, Alignment.CenterVertically),
                    ) {
                        GuideCopy(topics[index])
                        GuideVisual(topics[index].icon, Modifier.heightIn(min = if (compact) 152.dp else 196.dp).graphicsLayer {
                            scaleX = entrance.value
                            scaleY = entrance.value
                        })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(AppLayout.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page > 0) {
                    OutlinedButton(
                        onClick = previous,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = AppLayout.ActionHeight),
                    ) { Text("上一步") }
                }
                Button(
                    onClick = advance,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).heightIn(min = AppLayout.ActionHeight),
                ) {
                    Text(when {
                        !lastPage -> "下一步"
                        deck.firstUse && !deck.manual -> "开始使用"
                        else -> "完成"
                    })
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(title: String, page: Int, count: Int) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
            Text(
                "${page + 1} / $count",
                Modifier.clearAndSetSemantics { contentDescription = "第 ${page + 1} 步，共 $count 步" },
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(count) { index ->
                val color = when {
                    index == page -> colors.primary
                    index < page -> colors.primary.copy(alpha = 0.45f)
                    else -> colors.surfaceContainerHighest
                }
                Spacer(Modifier.weight(1f).height(4.dp).background(color, MaterialTheme.shapes.small))
            }
        }
    }
}

@Composable
private fun GuideCopy(topic: GuideTopic) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            topic.title,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(topic.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        topic.location?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GuideVisual(icon: GuideIcon, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(colors.primaryContainer, lerp(colors.primaryContainer, colors.surface, 0.58f)))),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            GuideIcon.WELCOME -> {
                val ring = colors.primary.copy(alpha = 0.19f)
                Canvas(Modifier.matchParentSize()) {
                    for (radius in listOf(78.dp, 107.dp, 136.dp)) {
                        drawCircle(ring, radius.toPx(), style = Stroke(1.dp.toPx()))
                    }
                }
                Image(
                    painterResource(R.drawable.ic_launcher_artwork),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp).clip(MaterialTheme.shapes.extraLarge)
                        .background(colorResource(R.color.launcher_background)).scale(1.2f),
                )
            }
            GuideIcon.SCAN -> NfcGuideVisual()
            GuideIcon.PREFERENCES -> StartupGuideVisual()
        }
    }
}

@Composable
private fun NfcGuideVisual() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.widthIn(max = 276.dp).fillMaxWidth(0.84f).height(136.dp)) {
        val unit = size.width / 228f
        val y = center.y - 56 * unit
        val outline = Stroke(width = 2 * unit, cap = StrokeCap.Round)
        val phone = Offset(22 * unit, y)
        val phoneSize = Size(66 * unit, 112 * unit)
        drawRoundRect(colors.surface, phone, phoneSize, CornerRadius(13 * unit))
        drawRoundRect(colors.primary, phone, phoneSize, CornerRadius(13 * unit), style = outline)
        drawLine(colors.primary, phone + Offset(27 * unit, 11 * unit), phone + Offset(39 * unit, 11 * unit),
            strokeWidth = outline.width, cap = StrokeCap.Round)
        drawRoundRect(colors.primaryContainer, phone + Offset(14 * unit, 34 * unit),
            Size(38 * unit, 50 * unit), CornerRadius(8 * unit))
        for (radius in listOf(14f, 24f, 34f)) {
            drawArc(
                color = colors.primary,
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset((92 - radius) * unit, center.y - radius * unit),
                size = Size(2 * radius * unit, 2 * radius * unit),
                style = outline,
            )
        }
        val card = Offset(142 * unit, center.y - 27 * unit)
        drawRoundRect(colors.primary, card, Size(78 * unit, 54 * unit), CornerRadius(10 * unit))
        drawRoundRect(colors.onPrimary.copy(alpha = 0.9f), card + Offset(13 * unit, 14 * unit),
            Size(15 * unit, 12 * unit), CornerRadius(3 * unit))
        drawLine(colors.onPrimary.copy(alpha = 0.7f), card + Offset(13 * unit, 40 * unit),
            card + Offset(46 * unit, 40 * unit), strokeWidth = outline.width, cap = StrokeCap.Round)
    }
}

@Composable
private fun StartupGuideVisual() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.padding(20.dp).widthIn(max = 280.dp).fillMaxWidth().clearAndSetSemantics { hideFromAccessibility() },
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Settings, null, Modifier.size(18.dp), tint = colors.primary)
                Text("启动页面", style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StartupOption(StartupPage.HOME.label, Icons.Filled.Home, Modifier.weight(1f))
                StartupOption(StartupPage.DEVICES.label, Icons.AutoMirrored.Filled.List, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StartupOption(label: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier, MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
