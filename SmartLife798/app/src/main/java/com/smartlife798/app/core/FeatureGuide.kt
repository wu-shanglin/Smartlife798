package com.smartlife798.app.core

/** Stable topic + content revision, independent of app release numbers. Never reuse a retired key. */
enum class GuideIcon { WELCOME, SCAN, SESSION, WALLET, PREFERENCES }

data class GuideTopic(
    val key: String,
    val title: String,
    val body: String,
    val location: String,
    val basic: Boolean = false,
    val icon: GuideIcon = GuideIcon.PREFERENCES,
)

data class GuideDeck(val id: Int, val topics: List<GuideTopic>, val firstUse: Boolean, val manual: Boolean = false)

object FeatureGuide {
    val topics = listOf(
        GuideTopic("welcome:2", "轻盈体验，从登录开始", "欢迎使用慧生活798。登录后，即可体验无广告的原生应用界面，让设备查找与日常操作更简洁、更顺畅。设备状态、费用与业务结果均以服务端确认为准。", "登录 → 开始使用", basic = true, icon = GuideIcon.WELCOME),
        GuideTopic("scan-nfc:2", "打开应用，碰一碰直达设备", "使用碰一碰前，请先打开本应用，并保持手机解锁、应用在前台及 NFC 已开启。将手机 NFC 感应区靠近支持的慧生活 NFC 卡片，即可跳转至对应设备。当前仅支持慧生活 NFC 卡片跳转，暂不支持支付宝碰一碰；使用前请核对设备与费用。", "打开应用 → 靠近慧生活 NFC 卡片", icon = GuideIcon.SCAN),
        GuideTopic("preferences:2", "从你习惯的页面开始", "你可以自主选择每次打开应用时先进入首页，或直接进入设备页。在“我的”中设置默认启动页面，下次打开应用即可按你的选择进入；之后也可随时调整。", "我的 → 默认启动页面", icon = GuideIcon.PREFERENCES),
    )
    private fun topicId(key: String): String = key.substringBeforeLast(':')
    private fun revision(key: String): Int = key.substringAfterLast(':').toIntOrNull() ?: 0
    fun isSeen(key: String, seen: Set<String>): Boolean = key in seen || seen.any {
        topicId(it) == topicId(key) && revision(it) >= revision(key)
    }
    fun current(catalog: List<GuideTopic> = topics): List<GuideTopic> =
        catalog.groupBy { topicId(it.key) }.values
            .mapNotNull { revisions -> revisions.maxByOrNull { revision(it.key) } }
            .sortedBy { !it.basic }
    fun pending(seen: Set<String>, catalog: List<GuideTopic> = topics, includeBasics: Boolean = true): List<GuideTopic> =
        current(catalog).filter { (includeBasics || !it.basic) && !isSeen(it.key, seen) }
}
