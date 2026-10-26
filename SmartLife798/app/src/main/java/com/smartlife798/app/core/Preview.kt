package com.smartlife798.app.core

/**
 * UI preview data. It exists ONLY to review the redesigned screens without a
 * real account; every screen shows a banner while it is active and no value is
 * ever sent to, or read from, the server.
 */
object Preview {
    @Volatile var enabled: Boolean = false

    val profile = StoredProfile(
        id = "preview-user",
        name = "预览用户",
        phone = "13800000000",
        no = "P0001",
        arrear = 0.0,
        totalScore = "120",
        useScore = 0,
    )

    private fun enterprise(name: String, id: String) = Enterprise(
        id = id,
        name = name,
        abbr = name.take(4),
        setting = Setting(dmode = 1, emode = 1, payPwd = 0),
        addr = Address(prov = "湖南省", city = "长沙市", dist = "岳麓区", detail = "XX 大学 3 号宿舍楼"),
    )

    private val shower = Device(
        id = "3049c1f8a7b24e6d",
        name = "3 栋 2 层淋浴机 02",
        status = 1,
        ntype = 1,
        gtype = 0,
        btype = 0,
        fmv = "1.4.2",
        memo = "插卡或扫码后开始计费，使用结束请点击「结束使用并结算」。",
        bm = DeviceModel(
            dtype = DeviceType.WATER_SHOWER,
            parts = listOf(
                DevicePart(mode = 21, name = "标准淋浴", rate = 0.05),
                DevicePart(mode = 22, name = "大水量", rate = 0.07),
            ),
        ),
        gene = Gene(status = DeviceType.Status.ON, mode = 21, time = System.currentTimeMillis() / 1000),
        ep = enterprise("慧生活校园服务", "ep-1"),
        gs = GoodsShelf(
            items = listOf(
                ShelfItem(pos = 0, out = 1, prd = Product(name = "淋浴用水", curPrice = 0.05)),
            ),
        ),
    )

    private val water = Device(
        id = "8842bd01ce9347aa",
        name = "图书馆 1 层直饮水机",
        status = 1,
        ntype = 1,
        bm = DeviceModel(dtype = DeviceType.WATER_PURIFIER, parts = listOf()),
        gene = Gene(status = DeviceType.Status.LOCKED, mode = null),
        ep = enterprise("慧生活校园服务", "ep-1"),
    )

    private val washing = Device(
        id = "5501ee73b1c04d92",
        name = "洗衣房 3 号机",
        status = 1,
        ntype = 1,
        bm = DeviceModel(
            dtype = DeviceType.WASHING_MACHINE,
            parts = listOf(
                DevicePart(mode = 13, name = "标准洗", rate = 3.0),
                DevicePart(mode = 12, name = "快洗", rate = 2.0),
                DevicePart(mode = 14, name = "大物洗", rate = 4.0),
            ),
        ),
        gene = Gene(status = DeviceType.Status.WORK, mode = 13),
        ep = enterprise("慧生活校园服务", "ep-1"),
        gs = GoodsShelf(items = listOf(ShelfItem(pos = 0, out = 1, prd = Product(name = "洗衣液", curPrice = 1.0)))),
    )

    val devices: List<Device> = listOf(shower, water, washing)

    fun detail(id: String): DeviceDetail {
        val device = devices.firstOrNull { it.id == id } ?: shower
        return DeviceDetail(
            device = device,
            deviceShare = if (device.id == shower.id) DeviceShare(id = "share-1", ctime = System.currentTimeMillis() / 1000) else null,
            wallet = wallet(),
            payItems = listOf(PayItem(92), PayItem(91), PayItem(21)),
            user = User(id = "preview-user", name = "预览用户", phone = "13800000000"),
        )
    }

    fun wallet(): Wallet = Wallet(
        total = 36.5,
        ofCash = 30.0,
        ofGift = 6.5,
        auth = true,
        owner = User(id = "preview-user", name = "预览用户", phone = "13800000000"),
        ep = enterprise("慧生活校园服务", "ep-1"),
    )

    fun rechargeProducts(): List<Product> = listOf(
        Product(id = "preview-2", name = "充值 2.0 元", curPrice = 2.0, ogiPrice = 2.0, type = 1),
        Product(id = "preview-5", name = "充值 5.0 元", curPrice = 5.0, ogiPrice = 5.0, type = 1),
        Product(id = "preview-10", name = "充值 10.0 元", curPrice = 10.0, ogiPrice = 10.0, type = 1),
        Product(id = "preview-20", name = "充值 20.0 元", curPrice = 20.0, ogiPrice = 20.0, type = 1),
    )

    fun bills(): List<Bill> = listOf(
        Bill(
            id = "BL20241021001",
            ctime = System.currentTimeMillis() - 3_600_000L * 5,
            utime = System.currentTimeMillis() - 3_600_000L * 5,
            payment = 1.85,
            status = 3,
            type = 6,
            dev = shower,
            ep = shower.ep,
            prds = listOf(Product(name = "淋浴用水 37L", curPrice = 1.85, count = 1)),
            note = "服务端结算",
        ),
        Bill(
            id = "BL20241020007",
            ctime = System.currentTimeMillis() - 86_400_000L,
            utime = System.currentTimeMillis() - 86_400_000L,
            payment = 3.0,
            status = 3,
            type = 6,
            dev = washing,
            ep = washing.ep,
            prds = listOf(Product(name = "标准洗", curPrice = 3.0, count = 1)),
        ),
        Bill(
            id = "BL20241018003",
            ctime = System.currentTimeMillis() - 86_400_000L * 3,
            utime = System.currentTimeMillis() - 86_400_000L * 3,
            payment = 0.6,
            status = 1,
            type = 6,
            dev = water,
            ep = water.ep,
            prds = listOf(Product(name = "直饮水 12L", curPrice = 0.6, count = 1)),
        ),
    )

    fun filterFavorites(): List<Device> = listOf(shower, washing)
}
