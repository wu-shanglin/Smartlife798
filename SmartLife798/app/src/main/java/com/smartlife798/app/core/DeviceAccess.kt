package com.smartlife798.app.core

/** A machine's running state is shared; it is not proof that this account owns its session. */
data class DeviceAccess(val action: PrimaryAction?, val notice: String? = null, val label: String? = null)

fun DeviceDetail.access(uid: String?, ownDeviceId: String?): DeviceAccess {
    val dev = device ?: return DeviceAccess(null, "设备信息不可用，请刷新后重试")
    val owner = user?.id?.takeIf { it.isNotBlank() }
    if (owner != null && (uid.isNullOrBlank() || owner != uid)) {
        return DeviceAccess(null, "设备正由他人使用，请等待对方结束后再使用", "他人使用中")
    }
    val action = dev.primaryAction()
    if (action == PrimaryAction.STOP && owner == null && ownDeviceId != dev.id) {
        return DeviceAccess(null, "设备使用中，暂无法确认使用者，请稍后刷新", "设备使用中")
    }
    if (action == PrimaryAction.START && StartWatch.isObservable(dev) && !dev.isStandby)
        return DeviceAccess(null, "设备尚未就绪，请稍后刷新", "设备尚未就绪")
    return DeviceAccess(action)
}
