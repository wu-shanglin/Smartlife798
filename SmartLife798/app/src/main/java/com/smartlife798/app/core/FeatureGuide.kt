package com.smartlife798.app.core

/** Stable topic + content revision, independent of app release numbers. Never reuse a retired key. */
enum class GuideIcon { WELCOME, SCAN, PREFERENCES }

data class GuideTopic(
    val key: String,
    val title: String,
    val body: String,
    val location: String? = null,
    val basic: Boolean = false,
    val icon: GuideIcon = GuideIcon.PREFERENCES,
)

data class GuideDeck(val id: Int, val topics: List<GuideTopic>, val firstUse: Boolean, val manual: Boolean = false)

object FeatureGuide {
    val topics = listOf(
        GuideTopic(
            key = "welcome:2",
            title = "登录后查找设备",
            body = "用手机号登录，即可查找设备、查看使用记录和账单。设备状态和费用以服务端为准。",
            basic = true,
            icon = GuideIcon.WELCOME,
        ),
        GuideTopic(
            key = "scan-nfc:2",
            title = "碰一碰找设备",
            body = "解锁手机，开启 NFC 并打开本应用，再将手机感应区贴近卡片。使用前请核对设备与费用。",
            location = "仅支持慧生活 NFC 卡片",
            icon = GuideIcon.SCAN,
        ),
        GuideTopic(
            key = "preferences:2",
            title = "选择启动页面",
            body = "在首页和设备页之间选一个。下次打开应用时，会直接进入你选的页面。",
            location = "我的 → 启动页面",
        ),
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
