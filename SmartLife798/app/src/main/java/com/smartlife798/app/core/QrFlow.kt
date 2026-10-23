package com.smartlife798.app.core

/**
 * What a `qr/use` response means for the scan flow.
 *
 * The original client only opens a device when the *server* reports type 3
 * (device) or 8 (door lock) and still renders the device payload. Vendor
 * deployments do not always follow that shape: a dispenser QR such as
 * `https://i.hnkzy.com/q/1/861089059737339` is resolved by id and can come back
 * with `qr` omitted (or `type` missing) while `dev` is populated. Rejecting such
 * a response left users staring at 「该二维码类型为消费券」 for a device the
 * server had just resolved.
 */
sealed interface QrResolution {
    /** A real device was resolved; the caller may open it. */
    data class Resolved(val device: Device) : QrResolution

    /** The server says this is a device/lock code, but no usable device came back. */
    data object NoDevice : QrResolution

    /** A valid code of another kind (recharge card, promo, VIP…). */
    data class OtherType(val type: Int) : QrResolution
}

/**
 * Classifies one resolution.
 *
 * @param device `dev` from the response (null when absent).
 * @param serverType `qr.type` from the response (null when the server omitted it).
 * @param localType the type guessed from the scanned text, used only for the
 *   user-facing message when the server said nothing at all.
 */
fun resolveQr(device: Device?, serverType: Int?, localType: Int): QrResolution {
    val type = serverType ?: localType
    val looksLikeDevice = type == QR_TYPE_DEVICE || type == QR_TYPE_DOOR_LOCK
    return when {
        // The server handing back a concrete device is the strongest signal it
        // has; a missing `type` must not hide it. A *different* declared type
        // still wins, because then the payload is not a device code at all.
        device != null && device.id.isNotBlank() && (serverType == null || looksLikeDevice) ->
            QrResolution.Resolved(device)
        looksLikeDevice -> QrResolution.NoDevice
        else -> QrResolution.OtherType(type)
    }
}

const val QR_TYPE_DEVICE = 3
const val QR_TYPE_DOOR_LOCK = 8

/**
 * True when the local 「进行中」 bookmark belongs to the device being ended.
 *
 * Ending device B must not clear a bookmark for device A: A would keep running
 * (and billing) with no way to end it from the Home card.
 */
fun activeBelongsTo(activeDeviceId: String?, endedDeviceId: String): Boolean =
    activeDeviceId != null && activeDeviceId == endedDeviceId

/**
 * True when another device already holds this install's single session slot.
 *
 * The client stores one bookmark, so starting a second machine would silently
 * orphan the first (still running) session; the caller refuses instead.
 */
fun blockedByOtherSession(activeDeviceId: String?, targetDeviceId: String): Boolean =
    activeDeviceId != null && activeDeviceId != targetDeviceId
