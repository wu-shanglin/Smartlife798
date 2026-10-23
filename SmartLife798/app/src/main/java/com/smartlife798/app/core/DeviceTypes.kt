package com.smartlife798.app.core

/** Device type catalogue observed in the original app (DeviceType enum). */
object DeviceType {
    const val WATER_METER = 5
    const val WATER_SHOWER = 6
    const val WATER_PURIFIER = 8
    const val WATER_HOST = 9
    const val WASHING_MACHINE = 10
    const val HAIR_DRIER = 20
    const val SHAMPOO_MACHINE = 21
    const val VENDING_MACHINE = 30
    const val CREDIT_MACHINE = 35
    const val TELEPHONE = 45
    const val ACCESS_CONTROL = 50
    const val POS_MACHINE = 60
    const val ACTIVE_CARD = 70
    const val CHARGE_MACHINE = 75
    const val DRY_MACHINE = 80
    const val SHOES_MACHINE = 90
    const val CTL_MACHINE = 95
    const val PICK_UP_MACHINE = 100
    const val SORTING_MACHINE = 110
    const val CHARGING_MACHINE = 120
    const val QUERY_MACHINE = 115
    const val TIME_CTL_MACHINE = 130
    const val TIME_CLOCK = 140

    private val names = mapOf(
        WATER_METER to "直饮水机",
        WATER_SHOWER to "热水淋浴机",
        WATER_PURIFIER to "饮水机",
        WATER_HOST to "净水/热水主机",
        WASHING_MACHINE to "洗衣机",
        HAIR_DRIER to "吹风机",
        SHAMPOO_MACHINE to "洗浴主机",
        VENDING_MACHINE to "售货机",
        CREDIT_MACHINE to "充值机",
        TELEPHONE to "电话机",
        ACCESS_CONTROL to "门禁",
        POS_MACHINE to "消费机",
        ACTIVE_CARD to "开卡器",
        CHARGE_MACHINE to "充电宝",
        DRY_MACHINE to "烘干机",
        SHOES_MACHINE to "洗鞋机",
        CTL_MACHINE to "计量控制器",
        PICK_UP_MACHINE to "取件柜",
        SORTING_MACHINE to "分拣台",
        CHARGING_MACHINE to "充电桩",
        QUERY_MACHINE to "查询屏",
        TIME_CTL_MACHINE to "定时控制器",
        TIME_CLOCK to "打卡机",
    )

    fun name(dtype: Int): String = names[dtype] ?: "智能设备"

    /**
     * Type label, or `null` when [dtype] is not a known type.
     *
     * Use this for payloads that may be partial (`dev/status`-shaped, bill
     * snapshots): a missing `bm` must render as 「设备」/「—」, not 「智能设备」.
     */
    fun nameOrNull(dtype: Int): String? = names[dtype]

    /** Types that expose the generic device detail screen in the original app. */
    fun isGenericDetail(dtype: Int): Boolean =
        dtype != ACCESS_CONTROL && dtype != TIME_CLOCK

    fun isWaterLike(dtype: Int): Boolean = dtype in setOf(WATER_METER, WATER_SHOWER, WATER_PURIFIER)

    fun isWashingLike(dtype: Int): Boolean =
        dtype in setOf(WASHING_MACHINE, DRY_MACHINE, SHOES_MACHINE)

    /** Server DeviceStatus constants. */
    object Status {
        const val OFF = 0
        const val ON = 1
        const val ERR = 2
        const val WORK = 10
        const val FLUSH = 20
        const val PREPARE = 30
        const val LOCKED = 99
        const val FORCE_LOCKED = 98
    }
}

enum class DeviceStateKind { ONLINE, RUNNING, STARTING, OFFLINE, DISABLED, BLUETOOTH }

/** Shelves of the device list screen; shared so Home can deep-link into one. */
enum class DevicesShelf(val label: String) {
    FAVORITE("收藏"),
    COMMON("常用"),
    RECENT("最近"),
}data class DeviceState(val kind: DeviceStateKind, val label: String)

fun Device.stateLabel(): DeviceState {
    if (isOffline && (ntype == 3 || ntype == 10)) return DeviceState(DeviceStateKind.BLUETOOTH, "蓝牙")
    if (isOffline) return DeviceState(DeviceStateKind.OFFLINE, "离线")
    val g = geneStatus
    val washingLike = DeviceType.isWashingLike(dtype) || dtype == DeviceType.HAIR_DRIER ||
        dtype == DeviceType.CHARGING_MACHINE
    // Water machines (meter / shower / purifier) only report LOCKED while idle.
    // As soon as the server accepts dev/start they leave LOCKED (ON / PREPARE /
    // WORK / FLUSH), so those states are "in use" and the primary action must be
    // STOP. Treating PREPARE as idle is what let users fire dev/start repeatedly
    // and collect the server's "设备准备中，请稍后再试" rejection.
    val waterLike = DeviceType.isWaterLike(dtype)
    return when (g) {
        DeviceType.Status.LOCKED -> DeviceState(DeviceStateKind.ONLINE, "在线")
        DeviceType.Status.FORCE_LOCKED -> DeviceState(DeviceStateKind.DISABLED, "禁用")
        DeviceType.Status.WORK -> DeviceState(DeviceStateKind.RUNNING, "运行中")
        DeviceType.Status.FLUSH -> DeviceState(DeviceStateKind.RUNNING, "冲洗中")
        DeviceType.Status.PREPARE -> when {
            washingLike -> DeviceState(DeviceStateKind.STARTING, "启动中")
            waterLike -> DeviceState(DeviceStateKind.STARTING, "准备中")
            else -> DeviceState(DeviceStateKind.ONLINE, "准备中")
        }
        DeviceType.Status.ON -> when {
            washingLike || waterLike -> DeviceState(DeviceStateKind.STARTING, "启动中")
            else -> DeviceState(DeviceStateKind.ONLINE, "在线")
        }
        DeviceType.Status.ERR -> DeviceState(DeviceStateKind.DISABLED, "故障")
        else -> DeviceState(DeviceStateKind.ONLINE, "在线")
    }
}

/** True while the machine is preparing or running and therefore worth polling. */
val Device.isInUse: Boolean
    get() = stateLabel().kind == DeviceStateKind.STARTING || stateLabel().kind == DeviceStateKind.RUNNING

/** True when the state forbids starting and makes polling pointless. */
val Device.isAbnormal: Boolean
    get() = stateLabel().kind.let {
        it == DeviceStateKind.OFFLINE || it == DeviceStateKind.DISABLED ||
            it == DeviceStateKind.BLUETOOTH
    }

/**
 * True when the machine is reachable and idle (`gene.status == 99`).
 *
 * Only standby machines can be started, and a start is only effective once the
 * device stops reporting standby.
 */
val Device.isStandby: Boolean
    get() = !isOffline && geneStatus == DeviceType.Status.LOCKED

/** How a `dev/status` snapshot reads while waiting for `dev/start` to take effect. */
enum class StartSignal { STARTED, STANDBY, ABNORMAL, UNKNOWN }

/**
 * Watches a freshly started device until the server reports what actually
 * happened.
 *
 * `dev/start` is asynchronous: `code >= 0` only means the server *accepted* the
 * command, not that the machine left standby. Treating the accepted code as
 * success is what produced a phantom 「使用中」 session for a dispenser that
 * never ran (and, twelve seconds later, a silent 「已结束」). The client
 * therefore reads `dev/status` until the device reports an in-use state, stays
 * idle for the whole window, or reports a terminal state, and records the
 * session only for [StartSignal.STARTED].
 */
object StartWatch {
    /** First status read after the command; the server needs a moment to relay it. */
    const val FIRST_DELAY_MS = 1_000L

    /** Spacing between status reads. */
    const val INTERVAL_MS = 2_000L

    /** Status reads per start; 1 s + 5 × 2 s ≈ 11 s ceiling. */
    const val ATTEMPTS = 5

    /** Window quoted to the user when a start never leaves standby. */
    val ceilingSeconds: Int get() = ((FIRST_DELAY_MS + ATTEMPTS * INTERVAL_MS) / 1000).toInt()

    /**
     * Device families whose `dev/start` is documented to move `gene.status` out
     * of standby (`ON` / `PREPARE` / `WORK` / `FLUSH`), so a status read can
     * confirm the start.
     *
     * Other families (POS, lockers, door locks…) can stay at `99` while being
     * usable, so there the accepted command remains the only evidence and the
     * legacy semantics apply — the client must not report them as failed just
     * because it cannot observe them.
     */
    fun isObservable(device: Device): Boolean =
        DeviceType.isWaterLike(device.dtype) || DeviceType.isWashingLike(device.dtype) ||
            device.dtype == DeviceType.HAIR_DRIER || device.dtype == DeviceType.CHARGING_MACHINE

    fun classify(device: Device?): StartSignal = when {
        device == null -> StartSignal.UNKNOWN
        device.isAbnormal -> StartSignal.ABNORMAL
        device.isInUse -> StartSignal.STARTED
        device.isStandby -> StartSignal.STANDBY
        // Reachable but neither in use nor idle (e.g. no `gene` yet).
        else -> StartSignal.UNKNOWN
    }
}

/**
 * True when the server no longer reports a recorded session as running.
 *
 * The local 「进行中」 record is only a bookmark; the machine (or another
 * client) ends a session at any time. An idle (`99`) or abnormal device means
 * the session is over — or never started — so the card must stop claiming it.
 */
fun activeSessionOver(device: Device): Boolean = device.isAbnormal || device.isStandby

/**
 * Merges a `dev/status` snapshot into the device it describes.
 *
 * `dev/status` answers with a *partial* device (see [DeviceStatus]): no `bm`,
 * `name`, `ep`… — merging keeps the known full record. Never assign the snapshot
 * itself to a `Device` slot: `dtype` would read as 0 and the machine type/name
 * would collapse to 「智能设备」.
 */
fun Device.mergeStatus(snapshot: DeviceStatus?): Device = if (snapshot == null) this else copy(
    status = snapshot.status ?: status,
    gene = snapshot.gene ?: gene,
    subs = snapshot.subs ?: subs,
    ext = snapshot.ext ?: ext,
)

/** Projects the mutable state of a device into the `dev/status` shape (for merges). */
fun Device.statusOnly(): DeviceStatus = DeviceStatus(
    id = id,
    status = status,
    gene = gene,
    subs = subs,
    ext = ext,
)

/**
 * Post-`dev/start` button lock.
 *
 * The command is asynchronous, so a second tap can arrive before the device
 * reports `ON/PREPARE/WORK`. The lock is deliberately *not* a plain timeout:
 * it is only allowed while
 *  - the pending window has not expired,
 *  - the device still reports standby (`ONLINE`, i.e. `gene.status == 99`),
 *  - it has not reported an in-use state since this start, and
 *  - our own usage record for this device still exists.
 *
 * Ending the session (from Home, another client or the machine itself), the
 * device leaving standby, or the usage record disappearing therefore unlocks
 * the button immediately. A timer alone would keep "立即使用" disabled for up
 * to [WINDOW_MS] after the order was already ended.
 */
object StartCommandLock {
    const val WINDOW_MS = 20_000L

    fun isLocked(
        state: DeviceStateKind,
        seenInUse: Boolean,
        pendingUntil: Long,
        now: Long,
        activeDeviceId: String?,
        deviceId: String,
    ): Boolean = now < pendingUntil &&
        !seenInUse &&
        state == DeviceStateKind.ONLINE &&
        activeDeviceId == deviceId
}

/** Mirrors the action decision table of the original detail screen. */
enum class PrimaryAction { START, STOP, SETTLE, OFFLINE, DISABLED, LINKING }

fun Device.primaryAction(): PrimaryAction = when (stateLabel().kind) {
    DeviceStateKind.OFFLINE -> PrimaryAction.OFFLINE
    DeviceStateKind.DISABLED -> PrimaryAction.DISABLED
    DeviceStateKind.BLUETOOTH -> PrimaryAction.LINKING
    // STARTING covers ON/PREPARE: the command was accepted, so offering START
    // again would submit a second dev/start while the first one is settling.
    DeviceStateKind.STARTING, DeviceStateKind.RUNNING -> PrimaryAction.STOP
    DeviceStateKind.ONLINE -> PrimaryAction.START
}

/** Exact mirror of the QR payload parser in the original client. */
object QrParser {
    data class Parsed(val type: Int, val id: String)

    private val digits = Regex("^\\d+$")
    private val deviceB = Regex("^(http|https):\\/\\/.+\\/q\\/\\d+\\/b\\?id=\\w+.*")
    private val shower = Regex("^(http|https):\\/\\/.+\\/q\\/\\d+\\/s\\?id=\\w+.*")
    private val billing = Regex("^(http|https):\\/\\/.+\\/q\\/\\d+\\/billing\\?id=\\w+.*")
    private val vip = Regex("^(http|https):\\/\\/.+\\/q\\/\\d+\\/vip\\?id=\\w+.*")
    private val wallet = Regex("^(http|https):\\/\\/.+\\/q\\/\\d+\\/wallet\\?id=\\w+.*")
    private val doorLock = Regex("^(http|https):\\/\\/.+\\/q\\/\\d+\\/bm\\?/[a-zA-Z0-9-]+.*")
    private val generic = Regex("^(http|https)://.+/q/\\d+/[a-zA-Z0-9-]+.*")

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        // Vendors print links such as https://i.hnkzy.com/q/1/861089059737339 and
        // sometimes add a trailing slash, `?from=…` or `#…`. The id is the last
        // path segment; a stray separator must not turn a device code into
        // 「无法识别」 or send a query string to `qr/use`.
        val url = normalize(text)
        if (digits.matches(text)) return Parsed(1, text)
        if (deviceB.matches(url)) return Parsed(2, url.queryId())
        if (shower.matches(url)) return Parsed(3, url.queryId())
        if (billing.matches(url)) return Parsed(5, url.queryId())
        if (vip.matches(url)) return Parsed(6, url.queryId())
        if (wallet.matches(url)) return Parsed(7, url.queryId())
        if (doorLock.matches(url)) return Parsed(8, url.substringAfter("q/1/").trim())
        if (generic.matches(url)) return Parsed(4, url.pathId())
        return Parsed(-1, text)
    }

    /** Lower-cases the scheme and drops a trailing slash so every rule matches alike. */
    private fun normalize(text: String): String {
        val url = when {
            text.startsWith("https://", ignoreCase = true) -> "https://" + text.substring(8)
            text.startsWith("http://", ignoreCase = true) -> "http://" + text.substring(7)
            else -> return text
        }
        return url.trimEnd('/')
    }

    /** `?id=…` value without a following query or fragment. */
    private fun String.queryId(): String =
        substringAfter("?id=").substringBefore('&').substringBefore('#').trim()

    /** Last path segment without a query or fragment. */
    private fun String.pathId(): String =
        substringAfterLast('/').substringBefore('?').substringBefore('#').trim()

    fun qrTypeName(type: Int): String = when (type) {
        1 -> "充值卡"
        2 -> "IC 卡"
        3 -> "设备"
        4 -> "消费券"
        5 -> "云袋"
        6 -> "洗衣洗鞋"
        7 -> "账单 / VIP"
        8 -> "门锁"
        else -> "未知类型"
    }
}

object Args {
    /** {"mode":13,"ext":[{"pos":0,"out":1}]} — same serialization as the original client. */
    fun modeWithGoods(mode: Int, items: List<ShelfItem>): String {
        if (items.isEmpty()) return "{\"mode\":$mode}"
        val ext = items.joinToString(",") { "{\"pos\":${it.pos},\"out\":${it.out}}" }
        return "{\"mode\":$mode,\"ext\":[$ext]}"
    }

    /** {"pos":0,"mode":13} for devices with channels/parts. */
    fun posAndMode(pos: Int, mode: Int?): String =
        if (mode == null) "" else "{\"pos\":$pos,\"mode\":$mode}"

    fun touch(): String = "{\"touch\":1}"

    /**
     * `dev/start` args, mirroring `DeviceCommonDetailActivity.o()`:
     * a mode is only sent when the device exposes that mode as a selectable
     * part. Water machines have no parts, so they must get an empty payload
     * rather than a leftover `gene.mode`.
     */
    fun forDevice(device: Device, mode: Int?): String {
        val parts = device.bm?.parts.orEmpty().filter { it.mode > 0 }
        if (mode == null || parts.none { it.mode == mode }) return ""
        return modeWithGoods(mode, device.gs?.items.orEmpty())
    }
}
