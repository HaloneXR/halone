package dev.halone.shell

import org.json.JSONObject

/**
 * The host side of `sdk/packages/sdk/src/protocol.ts` (the halone-sdk submodule), which is the contract.
 * Messages are JSON text because a `WebMessagePort` carries strings only.
 */
object Protocol {
    const val VERSION = 1

    fun connectMessage(windowId: String): String = JSONObject()
        .put("halone", "connect")
        .put("protocolVersion", VERSION)
        .put("windowId", windowId)
        .toString()
}

class HaloneException(val code: String, message: String) : Exception(message)

/** Compass-style, as on the wire: positive yaw is to the right, positive pitch is up. */
data class HeadPose(val yawDegrees: Double, val pitchDegrees: Double) {
    fun toJson(): JSONObject = JSONObject().put("yawDegrees", yawDegrees).put("pitchDegrees", pitchDegrees)
}

data class SystemInfo(
    val version: String,
    val deviceName: String,
    val verticalFovDegrees: Double,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val platform: String = "android",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform)
        .put("version", version)
        .put("protocolVersion", Protocol.VERSION)
        .put(
            "device",
            JSONObject()
                .put("name", deviceName)
                .put("verticalFovDegrees", verticalFovDegrees)
                .put("displayWidthPx", displayWidthPx)
                .put("displayHeightPx", displayHeightPx),
        )
}
