package com.smartlife798.app.core

import com.google.gson.annotations.SerializedName

/**
 * Response envelope used by every endpoint of the original service.
 * Observed live: {"code":0,"data":{...},"time":1792512371043}
 * Error example:  {"code":-99,"msg":"登录状态已过期"}
 */
data class ApiResp<T>(
    val code: Int = -1,
    val msg: String? = null,
    val data: T? = null,
    val time: Long = 0L,
    val size: Int? = null,
)

data class User(
    val id: String = "",
    val name: String = "",
    @SerializedName("pn") val phone: String = "",
    val img: String = "",
    val no: String? = null,
    val arrear: Double? = null,
    val useScore: Int? = null,
    val payPwd: String? = null,
    val doorPwd: String? = null,
    val adShow: Int? = null,
)

data class GeoPoint(val lat: Double = 0.0, val lng: Double = 0.0)

data class Address(
    val prov: String? = null,
    val city: String? = null,
    val dist: String? = null,
    val detail: String? = null,
    val marker: String? = null,
    val distance: Double = 0.0,
    val geo: GeoPoint? = null,
) {
    fun display(): String {
        val parts = listOfNotNull(prov, city, dist, detail).filter { it.isNotBlank() }
        return if (parts.isEmpty()) (marker ?: "") else parts.joinToString("")
    }
}

data class Setting(
    val dmode: Int = 0,
    val emode: Int = 0,
    val payPwd: Int = 0,
    val nfcUseOpen: Int = 0,
    val devPreCheck: Int = 0,
    val olcharge: Int = 0,
    val olrefund: Int = 0,
    val olsign: Int = 0,
    val thirdPay: Int = 0,
    val lmtCC: Int = 0,
    val epAttestation: Int = 0,
)

data class Enterprise(
    val id: String = "",
    val name: String = "",
    val abbr: String? = null,
    val img: String? = null,
    val contact: User? = null,
    val addr: Address? = null,
    val setting: Setting? = null,
)

data class DevicePart(
    val mode: Int = 0,
    val name: String = "",
    val rate: Double = 0.0,
    val maxT: Int = 0,
    val whetherOrNotToCheck: Boolean = false,
)

data class DeviceModel(
    val dtype: Int = 0,
    val unit: String? = null,
    val parts: List<DevicePart> = emptyList(),
    val sensors: List<Int> = emptyList(),
)

data class Gene(
    val status: Int = 0,
    val mode: Int? = null,
    val pos: Any? = null,
    val out: Any? = null,
    val err: Int? = null,
    val time: Long = 0L,
    val power: Int = 0,
    val expS: Long? = null,
    val expE: Long? = null,
)

data class DeviceSub(
    val status: Int? = null,
    val mode: Int? = null,
    val err: Int? = null,
    val isSelect: Boolean = false,
)

data class Product(
    val id: String = "",
    val name: String = "",
    val desc: String? = null,
    val curPrice: Double = 0.0,
    val ogiPrice: Double = 0.0,
    val count: Int? = null,
    val imgs: List<String> = emptyList(),
    val params: String? = null,
    val period: Int? = null,
    val type: Int? = null,
    val ptype: List<Int> = emptyList(),
)

data class ShelfItem(
    val pos: Int = 0,
    val out: Int = 0,
    val prd: Product? = null,
)

data class GoodsShelf(val items: List<ShelfItem> = emptyList())

data class DeviceExt(
    val mac: String? = null,
    val bmac: String? = null,
    val startT: String? = null,
    val endT: String? = null,
    val showTempPwd: Int? = null,
)

data class LeaseSetting(
    val balance: Double = 0.0,
    val days: Int = 0,
    val expiry: Long = 0L,
    val mode: Int = 0,
)

data class Device(    val id: String = "",
    val name: String = "",
    val refId: String? = null,
    val status: Int = 0,
    val ntype: Int = 0,
    val gtype: Int = 0,
    val btype: Int = 0,
    val fmv: String? = null,
    val memo: String? = null,
    val adShow: Int = 0,
    val prepay: Boolean? = null,
    val bm: DeviceModel? = null,
    val gene: Gene? = null,
    val ep: Enterprise? = null,
    val addr: Address? = null,
    val subs: List<DeviceSub>? = null,
    val gs: GoodsShelf? = null,
    val ext: DeviceExt? = null,
    val ls: LeaseSetting? = null,
) {
    val dtype: Int get() = bm?.dtype ?: 0
    val isOffline: Boolean get() = status == 0
    val geneStatus: Int get() = gene?.status ?: 0
}

/**
 * `dev/status` 的 `device`：**只有状态字段**的局部对象。
 *
 * 服务端这个端点不返回 `bm`（所以没有 `dtype`）、`name`、`ep` 等设备档案字段。
 * 单独建模成这个类型，是为了让「把它当成完整 [Device] 使用」在编译期就写不出来；
 * 任何页面都必须用 [com.smartlife798.app.core.mergeStatus] 合并到已知的完整设备上。
 *
 * 字段全部可空：响应缺字段时保留调用方已有的值，而不是被 Gson 的默认值（如 `status=0`
 * 被当成离线）覆盖。
 */
data class DeviceStatus(
    val id: String? = null,
    val status: Int? = null,
    val gene: Gene? = null,
    val subs: List<DeviceSub>? = null,
    val ext: DeviceExt? = null,
)

/** `GET /ui/app/dev/status` 的信封数据（`more=true` 时会多出无关字段，忽略即可）。 */
data class StatusSnapshot(
    val device: DeviceStatus? = null,
)

/** `POST /api/v1/bill/save` 的充值单请求体（自原应用 `RechargeBillParm` 还原）。 */
data class RechargeBillBody(
    val ep: IdCata,
    val prds: List<IdCount>,
    val owner: IdCata,
    val contact: IdCata,
    val cata: Int = 1,
    val note: String,
)

/** 账单主体的引用：`cata=1` 表示用户/企业（原应用固定传 1）。 */
data class IdCata(val id: String = "", val cata: Int = 1)

/** 商品行：`count` 为数量（充值固定 1 份）。 */
data class IdCount(val id: String = "", val count: Int = 1)

data class LockDoorExt(
    val begin: Long? = null,
    val end: Long? = null,
    val pwdPrefix: Int? = null,
)

data class DeviceShare(
    val id: String = "",
    val ctime: Long = 0L,
    val type: Int = 0,
    val lockDoorExt: LockDoorExt? = null,
)

data class Wallet(
    val total: Double = 0.0,
    val ofCash: Double = 0.0,
    val ofGift: Double = 0.0,
    val olCash: Double = 0.0,
    val olGift: Double = 0.0,
    val auth: Boolean = false,
    val owner: User? = null,
    val ep: Enterprise? = null,
    val utime: Long = 0L,
    val perms: Map<String, Long>? = null,
)

/** GET /acc/wallet/owner 的信封：钱包在 `aw`，`eps` 为企业钱包列表。 */
data class WalletOwner(val aw: Wallet? = null, val eps: List<Wallet> = emptyList())

data class PayItem(val type: Int = 0)

/** `GET /ui/app/sign-pga?type=21` — 支付宝免密签约参数（`code` 即 `pgacode`）。 */
data class AccountSignData(
    val sign: String? = null,
    val status: Int? = null,
    val type: Int? = null,
    val code: String? = null,
    @SerializedName("appid") val appId: String? = null,
)

data class DevicePartsInfo(
    val status: Int? = null,
    val gene: Gene? = null,
)

data class DeviceDetail(
    val device: Device? = null,
    val deviceShare: DeviceShare? = null,
    val wallet: Wallet? = null,
    val payItems: List<PayItem> = emptyList(),
    val parts: DevicePartsInfo? = null,
    val user: User? = null,
    val ads: List<Any> = emptyList(),
    val showAd: Int? = null,
    val prepay: Boolean? = null,
    val encrypt: String? = null,
    val lockDoorExt: LockDoorExt? = null,
    val attendance: Any? = null,
)

data class QrExtra(val pos: Int? = null)

data class QrInfo(
    val id: String = "",
    val pid: Any? = null,
    val type: Int = -1,
    val data: QrExtra? = null,
    val useds: Int? = null,
    val total: Int? = null,
    val oper: User? = null,
    val utime: Long? = null,
    val no: String? = null,
    val gtype: Int? = null,
)

data class QrData(
    val qr: QrInfo? = null,
    val dev: Device? = null,
    val card: Any? = null,
    val wallet: Wallet? = null,
)

data class Master(
    val account: User? = null,
    val ads: List<Any> = emptyList(),
    val favos: List<Device> = emptyList(),
    val pltTotalScore: String? = null,
)

data class LoginInfo(
    val oid: String? = null,
    val token: String? = null,
    val uid: String? = null,
)

data class LoginConfig(
    val showAd: Int? = null,
    val types: List<Int> = emptyList(),
)

data class LoginData(
    val al: LoginInfo? = null,
    val ar: LoginConfig? = null,
)

/** Billing / consumption record (/api/v1/bill/lst-owner). */
data class Bill(
    val id: String = "",
    val ctime: Long = 0L,
    val utime: Long = 0L,
    val payment: Double? = null,
    val discount: Double = 0.0,
    val status: Int = 0,
    val type: Int = 0,
    val cata: Int = 0,
    val dir: Int = 0,
    val note: String? = null,
    val msg: String? = null,
    val dev: Device? = null,
    val ep: Enterprise? = null,
    val owner: User? = null,
    val contact: User? = null,
    val prds: List<Product> = emptyList(),
)

data class BillDetail(
    val bill: Bill? = null,
    val cnt: String? = null,
)

/** Payment channel returned by /api/v1/bill/pay/channels. */
data class PayChannel(
    val type: Int = 0,
    val name: String? = null,
    val desc: String? = null,
    val activity: String? = null,
)

data class PayChannelData(
    val channels: List<PayChannel> = emptyList(),
)

/** Service order (/api/v1/ord/lst-owner). */
data class WorkOrder(
    val id: String = "",
    val code: String? = null,
    val did: String? = null,
    val devName: String? = null,
    val bm: DeviceModel? = null,
    val mode: Int? = null,
    val btype: Int? = null,
    val type: Int = 0,
    val status: Int = 0,
    val ctime: Long = 0L,
    val utime: Long = 0L,
    val stime: Long? = null,
    val ptime: Long? = null,
    val rtime: Long? = null,
    val note: String? = null,
    val ep: Enterprise? = null,
    val owner: User? = null,
    val prds: List<Product> = emptyList(),
    val station: Any? = null,
    val addr: Address? = null,
)

data class WorkOrderDetail(
    val ord: WorkOrder? = null,
    val bil: Bill? = null,
)

data class NearDevice(
    val name: String? = null,
    val distance: String? = null,
)
