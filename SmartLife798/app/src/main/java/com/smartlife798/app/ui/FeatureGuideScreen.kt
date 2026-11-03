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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartlife798.app.core.FeatureGuide
import com.smartlife798.app.core.GuideTopic
import com.smartlife798.app.vm.MainViewModel

/** No permission requests or business actions are initiated from learning content. */
@Composable
fun FirstUseGuide(vm: MainViewModel, onExit: () -> Unit = {}) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val topics = FeatureGuide.basics()
    fun finish() = vm.acknowledgeGuide(FeatureGuide.topics.map { it.key }.toSet(), basicsDone = true)
    BackHandler { if (page > 0) page-- else onExit() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("欢迎使用慧生活798", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(shapes = ButtonDefaults.shapes(), onClick = ::finish) { Text("跳过") }
            }
            Text("${page + 1} / ${topics.size}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { (page + 1f) / topics.size }, modifier = Modifier.fillMaxWidth())
            AnimatedContent(page, modifier = Modifier.weight(1f), label = "guidePage", transitionSpec = {
                (fadeIn(animationSpec = ExpressiveEffects) + scaleIn(animationSpec = ExpressiveSpatial, initialScale = 0.94f)) togetherWith fadeOut(animationSpec = ExpressiveEffects)
            }) { index ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(48.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (index == 0) com.smartlife798.app.QrGlyph(MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(56.dp))
                            else Icon(when (index) { 1 -> Icons.Filled.Home; 2 -> Icons.Filled.ShoppingCart; else -> Icons.Filled.Settings }, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text(topics[index].title, style = MaterialTheme.typography.displaySmall)
                    Text(topics[index].body, style = MaterialTheme.typography.bodyLarge)
                    Text("在哪里：${topics[index].location}", style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page > 0) OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { page-- }) { Text("上一步") }
                Button(shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp), onClick = {
                    if (page < topics.lastIndex) page++ else finish()
                }) { Text(if (page == topics.lastIndex) "开始使用" else "下一步") }
            }
        }
    }
}

@Composable
fun GuideLibrary(vm: MainViewModel, topics: List<GuideTopic> = FeatureGuide.topics, onClose: () -> Unit) {
    val seen by vm.guideSeen.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("使用指南") }, actions = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onClose) { Text("关闭") }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("按需了解，随时回来查看", style = MaterialTheme.typography.headlineMedium) }
                items(topics, key = { it.key }) { topic ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(topic.title, style = MaterialTheme.typography.titleLarge)
                            Text(topic.body, style = MaterialTheme.typography.bodyLarge)
                            Text("在哪里：${topic.location}", style = MaterialTheme.typography.labelLarge)
                            TextButton(shapes = ButtonDefaults.shapes(), enabled = !FeatureGuide.isSeen(topic.key, seen), onClick = { vm.acknowledgeGuide(setOf(topic.key)) }) {
                                Text(if (FeatureGuide.isSeen(topic.key, seen)) "已了解" else "知道了")
                            }
                        }
                    }
                }
                item {
                    Button(shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                        vm.acknowledgeGuide(topics.map { it.key }.toSet()); onClose()
                    }) { Text("全部了解，继续使用") }
                }
            }
        }
    }
}

/** A single digest for all pending topics, never a stack of release dialogs. */
@Composable
fun NewFeaturesPrompt(vm: MainViewModel, eligible: Boolean) {
    val seen by vm.guideSeen.collectAsStateWithLifecycle()
    val pending = FeatureGuide.pending(seen)
    var deferred by rememberSaveable { mutableStateOf(false) }
    var library by rememberSaveable { mutableStateOf(false) }
    // Freeze this batch while reading; acknowledgements must not remove visible cards mid-scroll.
    var batchKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    if (library && eligible) GuideLibrary(vm, FeatureGuide.topics.filter { it.key in batchKeys }, onClose = { library = false; deferred = true })
    else if (eligible && !deferred && pending.isNotEmpty()) AlertDialog(
        onDismissRequest = { deferred = true },
        title = { Text("了解新增功能") },
        text = { Text("有 ${pending.size} 项功能可了解，已合并在一份指南中。你也可以稍后从“我的 → 使用指南”查看。") },
        confirmButton = { Button(shapes = ButtonDefaults.shapes(), onClick = { batchKeys = pending.map { it.key }; library = true }) { Text("查看功能") } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { deferred = true }) { Text("稍后") } },
    )
}
