@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.smartlife798.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.smartlife798.app.R
import com.smartlife798.app.core.GuideDeck
import com.smartlife798.app.core.GuideIcon
import com.smartlife798.app.core.GuideTopic
import com.smartlife798.app.core.StartupPage
import com.smartlife798.app.ui.theme.ExpressiveEffects
import com.smartlife798.app.ui.theme.WaterBlue80

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
    BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < 560.dp
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
                    fadeIn(ExpressiveEffects) togetherWith fadeOut(ExpressiveEffects)
                },
                label = "guidePage",
            ) { index ->
                val visibility = if (index == page) Modifier else Modifier.semantics { hideFromAccessibility() }
                BoxWithConstraints(Modifier.fillMaxSize().then(visibility)) {
                    val copySlots = measureGuideCopy(topics, constraints.maxWidth)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(top = if (compact) 20.dp else 48.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp),
                    ) {
                        GuideCopy(topics[index], copySlots)
                        GuideVisual(topics[index].icon, Modifier.height(if (compact) 152.dp else 196.dp).graphicsLayer {
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

private data class GuideCopySlots(val title: Dp, val body: Dp, val detail: Dp)

@Composable
private fun measureGuideCopy(topics: List<GuideTopic>, width: Int): GuideCopySlots {
    // Reserve the tallest copy in each slot for this deck, width and font scale.
    val type = MaterialTheme.typography
    val measurer = rememberTextMeasurer(cacheSize = topics.size * 3)
    val density = LocalDensity.current
    val constraints = Constraints(maxWidth = width)
    fun height(text: String, style: TextStyle): Dp = with(density) {
        measurer.measure(text, style, constraints = constraints).size.height.toDp()
    }
    return GuideCopySlots(
        title = topics.maxOf { height(it.title, type.displaySmall.copy(fontWeight = FontWeight.Bold)) },
        body = topics.maxOf { height(it.body, type.bodyLarge) },
        detail = topics.mapNotNull { it.location }.maxOfOrNull { height(it, type.labelLarge) } ?: 0.dp,
    )
}

@Composable
private fun GuideCopy(topic: GuideTopic, slots: GuideCopySlots) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(
            topic.title,
            modifier = Modifier.fillMaxWidth().heightIn(min = slots.title)
                .semantics { heading(); liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            topic.body,
            Modifier.fillMaxWidth().heightIn(min = slots.body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        if (slots.detail > 0.dp) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = slots.detail)) {
                topic.location?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
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
            GuideIcon.WELCOME -> LoginGuideVisual()
            GuideIcon.SCAN -> NfcGuideVisual()
            GuideIcon.PREFERENCES -> StartupGuideVisual()
        }
    }
}

@Composable
private fun LoginGuideVisual() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.widthIn(max = 264.dp).fillMaxWidth(0.84f)
            .clearAndSetSemantics { hideFromAccessibility() },
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_artwork),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall)
                        .background(colorResource(R.color.launcher_background)),
                )
                Text("手机号登录", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Phone, null, Modifier.size(16.dp), tint = colors.primary)
                Text("手机号", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(6) {
                    Box(
                        Modifier.weight(1f).height(25.dp)
                            .background(colors.primaryContainer, MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center,
                    ) {
                        Spacer(Modifier.size(4.dp).background(colors.onPrimaryContainer, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun NfcGuideVisual() {
    val sticker = Color(0xFF20242C) // The physical NFC sticker has a dark face in both app themes.
    Box(
        Modifier.size(144.dp).clip(CircleShape).background(sticker)
            .clearAndSetSemantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.30f), radius = size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(WaterBlue80, radius = size.minDimension / 2 - 8.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NFC", color = Color.White, fontSize = 14.sp, letterSpacing = 2.sp)
            Canvas(Modifier.size(width = 76.dp, height = 36.dp)) {
                val phoneWidth = 19.dp.toPx()
                val phoneHeight = 30.dp.toPx()
                val rim = 2.dp.toPx()
                val phone = Offset(center.x - phoneWidth / 2, center.y - phoneHeight / 2)
                drawRoundRect(Color.White, phone, Size(phoneWidth, phoneHeight), CornerRadius(4.dp.toPx()))
                drawRoundRect(
                    sticker, phone + Offset(rim * 1.4f, rim * 2),
                    Size(phoneWidth - rim * 2.8f, phoneHeight - rim * 5), CornerRadius(rim),
                )
                drawCircle(sticker, radius = rim * 0.65f, center = Offset(center.x, phone.y + phoneHeight - rim * 1.5f))
                for (radiusDp in 12..24 step 6) {
                    val radius = radiusDp.dp.toPx()
                    for (angle in listOf(-37f, 143f)) {
                        drawArc(
                            Color.White, angle, 74f, useCenter = false,
                            topLeft = center - Offset(radius, radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(rim, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Text("碰一下", color = Color.White, fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun StartupGuideVisual() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(0.88f).clearAndSetSemantics { hideFromAccessibility() },
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
