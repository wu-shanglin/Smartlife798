package com.smartlife798.app.core

/** Stable topic + content revision, independent of app release numbers. Never reuse a retired key. */
data class GuideTopic(val key: String, val title: String, val body: String, val location: String)

object FeatureGuide {
    val topics = listOf(
        GuideTopic("scan-nfc:1", "扫码找到设备", "点击底部扫码，也可手动输入编号。支持的 NFC 卡可进入设备详情并尝试开始，请先核对设备与费用。", "首页 / 底部扫码"),
        GuideTopic("session:1", "掌握使用进度", "首页与订单页可查看本机确认的使用记录。结束请进入设备详情，执行结果与费用以服务端为准。", "首页 / 订单 / 设备详情"),
        GuideTopic("wallet-cancel:1", "取消未付订单", "钱包创建充值单后，支付面板可取消未付交易。关闭面板会保留订单，可到订单页继续处理；已付、待确认或核算中不能取消。", "钱包 / 订单 → 去付款"),
        GuideTopic("preferences:1", "按你的习惯使用", "在“我的”选择启动页面、外观与自动签到。签到与积分以服务端确认为准；随时打开使用指南重看功能。", "我的 → 设置 / 使用指南"),
    )
    /** A fixed learning budget; newly added release topics do not lengthen first-use. */
    fun basics(catalog: List<GuideTopic> = topics): List<GuideTopic> =
        listOf("scan-nfc", "session", "wallet-cancel", "preferences").mapNotNull { id ->
            catalog.firstOrNull { it.key.substringBefore(':') == id }
        }
    private fun topicId(key: String): String = key.substringBeforeLast(':')
    private fun revision(key: String): Int = key.substringAfterLast(':').toIntOrNull() ?: 0
    fun isSeen(key: String, seen: Set<String>): Boolean = key in seen || seen.any {
        topicId(it) == topicId(key) && revision(it) >= revision(key)
    }
    fun pending(seen: Set<String>, catalog: List<GuideTopic> = topics): List<GuideTopic> =
        catalog.groupBy { topicId(it.key) }.values
            .mapNotNull { revisions -> revisions.maxByOrNull { revision(it.key) } }
            .filterNot { isSeen(it.key, seen) }
}
