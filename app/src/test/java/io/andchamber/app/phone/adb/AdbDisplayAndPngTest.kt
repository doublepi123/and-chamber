package io.andchamber.app.phone.adb

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbDisplayAndPngTest {
    @Test
    fun wmSizePrefersOverrideAndSwapsForRotation() {
        assertEquals(1080 to 2400, AdbDisplay.parseSize("Physical size: 1080x2400"))
        assertEquals(
            1080 to 2340,
            AdbDisplay.parseSize("Physical size: 1080x2400\nOverride size: 1080x2340"),
        )
        assertEquals(
            "[1080,2400]",
            AdbDisplay.geometry(1080 to 2400, 0)!!.let {
                "[${it.displayWidth},${it.displayHeight}]"
            },
        )
        val landscape = AdbDisplay.geometry(1080 to 2400, 1)!!
        assertEquals(2400, landscape.displayWidth)
        assertEquals(1080, landscape.displayHeight)
        assertEquals(1, landscape.rotation)
        val landscapeThree = AdbDisplay.geometry(1080 to 2400, 3)!!
        assertEquals(2400, landscapeThree.displayWidth)
        assertEquals(1080, landscapeThree.displayHeight)
    }

    @Test
    fun invalidSizeAndRotationAreRefused() {
        assertNull(AdbDisplay.parseSize(""))
        assertNull(AdbDisplay.parseSize("Physical size: 0x2400"))
        assertNull(AdbDisplay.parseSize("Physical size: 1080x10001"))
        assertNull(AdbDisplay.geometry(1080 to 2400, 4))
        assertNull(AdbDisplay.geometry(0 to 2400, 0))
    }

    @Test
    fun pngHeaderIsValidated() {
        val png = png(1080, 2400)
        assertEquals(1080 to 2400, AdbPng.readSize(png))
        assertTrue(AdbPng.isComplete(png))
        assertFalse(AdbPng.isComplete(png.copyOf(png.size - 1)))
        assertNull(AdbPng.readSize(png.copyOf(20)))
        assertNull(AdbPng.readSize(ByteArray(40)))
        assertNull(AdbPng.readSize(png(0, 2400)))
        assertNull(AdbPng.readSize(png(20_000, 2400)))
    }

    @Test
    fun nonPngAndIncompleteChunksAreRefused() {
        val jpeg = ByteArray(64) { 0xff.toByte() }
        assertNull(AdbPng.readSize(jpeg))
        assertFalse(AdbPng.isPng(jpeg))

        val badType = png(1080, 2400).also { it[12] = 'X'.code.toByte() }
        assertNull(AdbPng.readSize(badType))

        val badLength = png(1080, 2400).also { it[11] = 0 }
        assertNull(AdbPng.readSize(badLength))
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            ),
        )
        out.write(intBytes(13))
        out.write("IHDR".toByteArray())
        out.write(intBytes(width))
        out.write(intBytes(height))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        out.write(intBytes(0))
        out.write(intBytes(0))
        out.write("IEND".toByteArray())
        out.write(byteArrayOf(0xae.toByte(), 0x42, 0x60, 0x82.toByte()))
        return out.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
