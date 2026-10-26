// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

/** The operations the driver needs from one ADB transport. No arbitrary command is accepted. */
interface AdbDevice {
    val transportSecurity: AdbTransportSecurity

    fun connect(deadlineMs: Long)

    fun disconnect()

    fun isConnected(): Boolean

    fun hierarchyXml(deadlineMs: Long, maxBytes: Int = AdbHierarchyParser.MAX_XML_BYTES): ByteArray

    fun windowStateText(deadlineMs: Long, maxBytes: Int = AdbWindowStateParser.MAX_BYTES): String

    /** Full state of one window token, including its real `Frames:` line. */
    fun windowDetailText(
        windowToken: String,
        deadlineMs: Long,
        maxBytes: Int = AdbWindowStateParser.MAX_BYTES,
    ): String

    fun displaySizeText(deadlineMs: Long, maxBytes: Int = MAX_DISPLAY_SIZE_BYTES): String

    fun capturePng(deadlineMs: Long, maxBytes: Int = io.andchamber.app.phone.core.PhoneLimits.MAX_CAPTURE_BYTES): ByteArray

    /** Injects one typed mutation. Throws [AdbDeviceException] with `afterWrite` when uncertain. */
    fun inject(command: AdbInputCommand, deadlineMs: Long): AdbInjection

    companion object {
        const val MAX_DISPLAY_SIZE_BYTES: Int = 4 * 1024
    }
}

class AdbInjection(val written: Boolean)
