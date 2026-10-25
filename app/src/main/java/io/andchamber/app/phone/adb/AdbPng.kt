// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneLimits

/** Minimal PNG header validation for `exec:screencap -p` bytes. */
object AdbPng {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    )

    /**
     * Returns (width, height) only for a complete PNG signature plus a 13-byte IHDR chunk.
     * The caller still enforces its own byte cap; this only refuses a non-PNG or an unreasonable
     * dimension instead of guessing.
     */
    fun readSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 33) return null
        for (index in SIGNATURE.indices) {
            if (bytes[index] != SIGNATURE[index]) return null
        }
        val ihdrLength = readInt(bytes, 8)
        if (ihdrLength != 13) return null
        if (bytes[12] != 'I'.code.toByte() ||
            bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() ||
            bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        if (width !in 1..PhoneLimits.MAX_DISPLAY_PX ||
            height !in 1..PhoneLimits.MAX_DISPLAY_PX
        ) {
            return null
        }
        return width to height
    }

    fun isPng(bytes: ByteArray): Boolean = readSize(bytes) != null

    /** A capture is only returned when the PNG IEND chunk is present, never a truncated image. */
    fun isComplete(bytes: ByteArray): Boolean {
        if (readSize(bytes) == null || bytes.size < IEND.size) return false
        val offset = bytes.size - IEND.size
        for (index in IEND.indices) {
            if (bytes[offset + index] != IEND[index]) return false
        }
        return true
    }

    private val IEND = byteArrayOf(
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4e,
        0x44,
        0xae.toByte(),
        0x42,
        0x60,
        0x82.toByte(),
    )

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
