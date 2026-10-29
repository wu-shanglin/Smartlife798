package com.smartlife798.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.smartlife798.app.core.Preview
import com.smartlife798.app.core.Store
import com.smartlife798.app.core.session
import com.smartlife798.app.ui.components.ChoiceButtonGroup
import com.smartlife798.app.ui.components.ExpressiveRefreshBox
import com.smartlife798.app.ui.components.SettingsChoiceButtonGroup
import com.smartlife798.app.ui.theme.SmartLifeTheme
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real touch events, accessibility layout bounds and screenshots on account-free preview content. */
object ExpressiveMotionChecks {
    fun run(test: Instrumentation) {
        val result = Bundle()
        var stage = "account-free guard"
        var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null
        var downTime: Long? = null
        var downPoint: Pair<Float, Float>? = null
        val originalPreview = Preview.enabled
        val counts = AtomicInteger()
        val selected = Collections.synchronizedList(mutableListOf<Int>())
        val geometry = mutableListOf<String>()
        val labels = listOf("春日晨", "夏日午", "秋日夜")
        test.runOnMainSync { ServiceLocator.init(test.targetContext) }
        val accessibilityInfo = test.uiAutomation.serviceInfo
        accessibilityInfo.flags = accessibilityInfo.flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = accessibilityInfo
        try {
            check(Store.token.isNullOrBlank() && session.token.isNullOrBlank())
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("preview", true)) as MainActivity
            val screen = activity ?: error("Preview activity unavailable")
            for (settings in listOf(false, true)) {
                val name = if (settings) "settings" else "choice"
                stage = "$name press expands center and compresses neighbors"
                counts.set(0)
                test.runOnMainSync {
                    screen.setContent {
                        SmartLifeTheme(darkTheme = false) {
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .padding(top = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("联动动效离线验证", style = MaterialTheme.typography.titleMedium)
                                if (settings) SettingsChoiceButtonGroup(labels, 0, { counts.incrementAndGet() })
                                else ChoiceButtonGroup(labels, 0, { counts.incrementAndGet() })
                            }
                        }
                    }
                }
                await { labels.all { find(test, it) != null } }
                test.waitForIdleSync()
                SystemClock.sleep(450)
                val before = labels.map { bounds(button(find(test, it)!!)) }
                capture(test, "expressive-$name-rest.png")
                val center = before[1]
                val point = center.exactCenterX() to center.exactCenterY()
                val started = SystemClock.uptimeMillis()
                downTime = started
                downPoint = point
                touch(test, MotionEvent.ACTION_DOWN, started, point)
                SystemClock.sleep(350)
                val pressed = labels.map { bounds(button(find(test, it)!!)) }
                capture(test, "expressive-$name-held.png")
                geometry.add("$name before=${before.map(Rect::width)},held=${pressed.map(Rect::width)}")
                check(pressed[1].width() > before[1].width() + 2)
                check(pressed[0].width() < before[0].width() - 1 && pressed[2].width() < before[2].width() - 1)
                check(counts.get() == 0)
                touch(test, MotionEvent.ACTION_UP, started, point)
                downTime = null
                downPoint = null
                stage = "$name release restores widths and selects once"
                await {
                    labels.mapIndexed { index, label ->
                        find(test, label)?.let { abs(bounds(button(it)).width() - before[index].width()) <= 2 } ?: false
                    }.all { it } && counts.get() == 1
                }
                val restored = labels.map { bounds(button(find(test, it)!!)).width() }
                geometry.add("$name released=$restored,clicks=${counts.get()}")
            }

            stage = "disabled controls ignore real touch"
            counts.set(0)
            val disabledLabels = listOf("禁用春", "禁用夏", "禁用秋")
            test.runOnMainSync {
                screen.setContent {
                    SmartLifeTheme(darkTheme = false) {
                        Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
                            ChoiceButtonGroup(disabledLabels, 0, { counts.incrementAndGet() }, enabled = false)
                        }
                    }
                }
            }
            await { find(test, disabledLabels[1]) != null }
            val disabled = button(find(test, disabledLabels[1])!!)
            check(!disabled.isEnabled)
            val area = bounds(disabled)
            tap(test, area)
            SystemClock.sleep(250)
            check(counts.get() == 0)

            stage = "narrow layout and 1.3 font scale retain all overflow choices"
            val longLabels = listOf("洗衣机标准模式", "烘干机高温模式", "淋浴设备节水模式", "饮水机常温模式")
            test.runOnMainSync {
                screen.setContent {
                    SmartLifeTheme(darkTheme = false) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                            Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
                                Text("窄屏大字体", style = MaterialTheme.typography.titleMedium)
                                Column(Modifier.width(240.dp)) {
                                    ChoiceButtonGroup(longLabels, 0, { selected.add(it) })
                                }
                            }
                        }
                    }
                }
            }
            stage = "narrow layout exposes overflow indicator"
            await { find(test, "更多选项") != null }
            stage = "narrow held button cannot cover overflow"
            val firstVisible = longLabels.first { find(test, it) != null }
            val narrowBefore = bounds(button(find(test, firstVisible)!!))
            val overflowBefore = bounds(button(find(test, "更多选项")!!))
            check(!Rect.intersects(narrowBefore, overflowBefore))
            val narrowPoint = narrowBefore.exactCenterX() to narrowBefore.exactCenterY()
            val narrowStarted = SystemClock.uptimeMillis()
            downTime = narrowStarted
            downPoint = narrowPoint
            touch(test, MotionEvent.ACTION_DOWN, narrowStarted, narrowPoint)
            SystemClock.sleep(350)
            val narrowHeld = bounds(button(find(test, firstVisible)!!))
            val overflowHeld = bounds(button(find(test, "更多选项")!!))
            check(!Rect.intersects(narrowHeld, overflowHeld))
            check(abs(narrowHeld.width() - narrowBefore.width()) <= 2)
            check(selected.isEmpty())
            capture(test, "expressive-overflow-held.png")
            touch(test, MotionEvent.ACTION_UP, narrowStarted, narrowPoint)
            downTime = null
            downPoint = null
            await { selected.size == 1 }
            check(selected.single() == longLabels.indexOf(firstVisible))
            selected.clear()
            for ((index, label) in longLabels.withIndex()) {
                stage = "overflow option $index resolve visible label"
                if (find(test, label) == null) {
                    stage = "overflow option $index open menu"
                    click(test, "更多选项")
                    stage = "overflow option $index appears in menu"
                    await { find(test, label) != null }
                }
                stage = "overflow option $index click"
                click(test, label)
                stage = "overflow option $index delivers one callback"
                await { selected.size == index + 1 }
                check(selected.last() == index)
                test.waitForIdleSync()
                stage = "overflow option $index menu dismisses before next choice"
                await { find(test, "更多选项") != null }
            }
            capture(test, "expressive-overflow-large-font.png")

            stage = "real appearance labels stay visible and vertically centered at large font"
            val settingsLabels = listOf("跟随系统", "浅色", "深色")
            counts.set(0)
            test.runOnMainSync {
                screen.setContent {
                    SmartLifeTheme(darkTheme = false) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                            Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
                                Column(Modifier.width(240.dp)) {
                                    SettingsChoiceButtonGroup(settingsLabels, 0, { counts.incrementAndGet() })
                                }
                            }
                        }
                    }
                }
            }
            await { find(test, settingsLabels.first()) != null }
            test.waitForIdleSync()
            val settingBounds = settingsLabels.mapNotNull { find(test, it)?.let { node -> bounds(button(node)) } }
            val minTouch = 48 * test.targetContext.resources.displayMetrics.density
            check(settingBounds.all { it.width() >= minTouch && it.height() >= minTouch })
            check(settingBounds.all { abs(it.exactCenterY() - settingBounds.first().exactCenterY()) <= 1f })
            check(settingBounds.zipWithNext().all { (left, right) -> !Rect.intersects(left, right) })
            // A narrow large-font row must offer full labels through overflow,
            // rather than force every choice into a compressed multiline button.
            settingsLabels.forEachIndexed { index, label ->
                if (find(test, label) == null) {
                    click(test, "更多选项")
                    await { find(test, label) != null }
                }
                click(test, label)
                await { counts.get() == index + 1 }
            }
            capture(test, "expressive-settings-large-font.png")
            geometry.add("settings-large-font=${settingBounds.map { "${it.width()}x${it.height()}" }}")

            stage = "long labels do not change row height while either edge is held"
            val edgeLabels = listOf("跟随系统", "浅色模式", "深色模式")
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                test.runOnMainSync {
                    screen.setContent {
                        SmartLifeTheme(darkTheme = false) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                                Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
                                    SettingsChoiceButtonGroup(edgeLabels, 0, {})
                                }
                            }
                        }
                    }
                }
                test.waitForIdleSync()
                SystemClock.sleep(300)
                await { find(test, edgeLabels.first()) != null }
                for (pressedLabel in listOf(edgeLabels.first(), edgeLabels.last())) {
                    stage = "font $fontScale edge $pressedLabel retains row height"
                    val target = find(test, pressedLabel) ?: continue // Available in overflow instead.
                    val visible = edgeLabels.mapNotNull { label -> find(test, label)?.let { label to bounds(button(it)) } }
                    val area = bounds(button(target))
                    val point = area.exactCenterX() to area.exactCenterY()
                    val started = SystemClock.uptimeMillis()
                    downTime = started
                    downPoint = point
                    touch(test, MotionEvent.ACTION_DOWN, started, point)
                    repeat(5) {
                        SystemClock.sleep(70)
                        visible.forEach { (label, before) ->
                            val held = bounds(button(find(test, label)!!))
                            check(abs(held.height() - before.height()) <= 1)
                            check(held.top == before.top && held.bottom == before.bottom)
                        }
                    }
                    capture(test, "expressive-edge-$fontScale-${edgeLabels.indexOf(pressedLabel)}.png")
                    touch(test, MotionEvent.ACTION_UP, started, point)
                    downTime = null
                    downPoint = null
                    SystemClock.sleep(350)
                }
            }

            stage = "hidden indicator appears on real pull and refreshes once"
            counts.set(0)
            test.runOnMainSync {
                screen.setContent {
                    SmartLifeTheme(darkTheme = false) {
                        ExpressiveRefreshBox(false, { counts.incrementAndGet() }, Modifier.fillMaxSize()) {
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                                items(30) { index -> Text("刷新手势夹具 $index", Modifier.padding(24.dp)) }
                            }
                        }
                    }
                }
            }
            test.waitForIdleSync()
            await { find(test, "刷新手势夹具 0") != null }
            val display = test.targetContext.resources.displayMetrics
            val start = display.heightPixels * 0.25f
            val end = display.heightPixels * 0.78f
            val x = display.widthPixels * 0.5f
            val began = SystemClock.uptimeMillis()
            downTime = began
            downPoint = x to start
            touch(test, MotionEvent.ACTION_DOWN, began, x to start)
            for (step in 1..16) {
                SystemClock.sleep(16)
                touch(test, MotionEvent.ACTION_MOVE, began, x to (start + (end - start) * step / 16))
            }
            touch(test, MotionEvent.ACTION_UP, began, x to end)
            downTime = null
            downPoint = null
            await { counts.get() == 1 }
            SystemClock.sleep(400)
            check(counts.get() == 1)

            stage = "light and dark refresh indicator captures"
            for (dark in listOf(false, true)) {
                test.runOnMainSync {
                    screen.setContent {
                        SmartLifeTheme(darkTheme = dark) {
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("刷新颜色预览", color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 16.dp))
                                ExpressiveRefreshBox(true, {}, Modifier.fillMaxWidth().height(300.dp)) {
                                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                                        Text("使用主题主色与中性容器", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
                // This preview deliberately runs an infinite progress animation;
                // the main queue need not become idle on a fast emulator.
                SystemClock.sleep(500)
                capture(test, "expressive-refresh-${if (dark) "dark" else "light"}.png")
            }
            result.putString("stream", "PASS: real DOWN/UP expands center and compresses both neighbors in Choice/Settings groups, release restores widths within 2px and fires once, disabled zero clicks, 240dp/1.3 font held overflow does not overlap and preserves four choices; settings labels remain accessible inline or through overflow at 240dp/2x font, edge holds at 1x/1.3x/2x retain row height; light/dark refresh captures; ${geometry.joinToString("; ")}\n")
            code = Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("failureFrames", failure.stackTrace.take(5).joinToString {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            })
            result.putString("overflowCallbacks", selected.joinToString())
            val testNodes = nodes(root(test)).filter { it.packageName?.toString() == test.targetContext.packageName }
            result.putString("fixtureTree", testNodes.take(40).joinToString("; ") {
                "text=${it.text?.toString().orEmpty()},desc=${it.contentDescription?.toString().orEmpty()},clickable=${it.isClickable}," +
                    "checkable=${it.isCheckable},enabled=${it.isEnabled},visible=${it.isVisibleToUser},bounds=${bounds(it)}"
            })
            if (activity != null) capture(test, "expressive-failure.png")
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; line=${failure.stackTrace.firstOrNull { it.className.contains("ExpressiveMotionChecks") }?.lineNumber}; ${geometry.joinToString("; ")}\n")
        } finally {
            val started = downTime
            val point = downPoint
            if (started != null && point != null) touch(test, MotionEvent.ACTION_CANCEL, started, point)
            test.runOnMainSync { activity?.finish() }
            Preview.enabled = originalPreview
        }
        test.finish(code, result)
    }

    private fun touch(test: Instrumentation, action: Int, down: Long, point: Pair<Float, Float>) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun tap(test: Instrumentation, bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        val point = bounds.exactCenterX() to bounds.exactCenterY()
        touch(test, MotionEvent.ACTION_DOWN, time, point)
        SystemClock.sleep(80)
        touch(test, MotionEvent.ACTION_UP, time, point)
    }

    private fun root(test: Instrumentation): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        val active = test.uiAutomation.rootInActiveWindow
        if (active?.packageName?.toString() == test.targetContext.packageName) return active
        // API 28 can keep the dismissed overflow popup as the active window.
        return test.uiAutomation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == test.targetContext.packageName } ?: active
    }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun find(test: Instrumentation, text: String): AccessibilityNodeInfo? =
        nodes(root(test)).firstOrNull {
            it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
        }

    private fun button(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && !current.isCheckable && current.parent != null) current = current.parent
        return current
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun click(test: Instrumentation, label: String) {
        val target = button(find(test, label) ?: error("Option unavailable"))
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync()
    }

    private fun capture(test: Instrumentation, name: String) {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val image = File(test.targetContext.filesDir, name)
        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        check(condition()) { "Timed out" }
    }
}
