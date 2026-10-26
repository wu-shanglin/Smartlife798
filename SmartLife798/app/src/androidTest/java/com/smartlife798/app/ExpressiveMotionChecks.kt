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
                test.waitForIdleSync()
                SystemClock.sleep(500)
                capture(test, "expressive-refresh-${if (dark) "dark" else "light"}.png")
            }
            result.putString("stream", "PASS: real DOWN/UP expands center and compresses both neighbors in Choice/Settings groups, release restores widths within 2px and fires once, disabled zero clicks, 240dp/1.3 font overflow preserves four choices; light/dark refresh captures; ${geometry.joinToString("; ")}\n")
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
            result.putString("stream", "FAIL at $stage: ${failure.javaClass.simpleName}; ${geometry.joinToString("; ")}\n")
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
        return test.uiAutomation.rootInActiveWindow
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
        File(test.targetContext.filesDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
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
