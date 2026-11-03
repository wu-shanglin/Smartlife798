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
        GuideTopic("welcome:1", "日常使用，从这里开始", "扫码找到设备，在详情页确认费用并开始使用。首页查看进度，订单核对账单，钱包管理充值。", "首页 · 设备 · 扫码 · 订单 · 我的", basic = true, icon = GuideIcon.WELCOME),
        GuideTopic("scan-nfc:1", "扫码找到设备", "点击底部扫码，也可手动输入编号。支持的 NFC 卡可进入设备详情并尝试开始，请先核对设备与费用。", "首页 / 底部扫码", icon = GuideIcon.SCAN),
        GuideTopic("session:1", "掌握使用进度", "首页与订单页可查看本机确认的使用记录。结束请进入设备详情，执行结果与费用以服务端为准。", "首页 / 订单 / 设备详情", icon = GuideIcon.SESSION),
        GuideTopic("wallet-cancel:1", "取消未付订单", "钱包创建充值单后，支付面板可取消未付交易。关闭面板会保留订单，可到订单页继续处理；已付、待确认或核算中不能取消。", "钱包 / 订单 → 去付款", icon = GuideIcon.WALLET),
        GuideTopic("preferences:1", "按你的习惯使用", "在“我的”选择启动页面、外观与自动签到。签到与积分以服务端确认为准；随时打开使用指南重看功能。", "我的 → 设置 / 使用指南"),
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
