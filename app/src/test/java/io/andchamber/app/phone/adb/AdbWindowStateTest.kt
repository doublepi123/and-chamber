package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneNode
import io.andchamber.app.phone.core.PhoneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbWindowStateTest {
    private val geometry = PhoneGeometry(1080, 2400, 0)
    private val focus = AdbWindowStateParser.parse(
        """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          mCurrentFocus=Window{7068ee4 u0 com.example.app/com.example.app.MainActivity}
          mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}
        """.trimIndent(),
    )
    private val nodes = listOf(
        PhoneNode(
            ref = "n0",
            role = "EditText",
            label = "",
            bounds = PhoneRect(10, 20, 500, 120),
            clickable = true,
            editable = true,
            focused = true,
        ),
    )

    @Test
    fun parsesCurrentFocusAndFocusedAppTokens() {
        assertEquals("7068ee4", focus.currentFocusToken)
        assertEquals("com.example.app", focus.currentFocusPackage)
        assertEquals("com.example.app.MainActivity", focus.currentFocusActivity)
        assertEquals("312191b", focus.focusedAppToken)
        assertEquals("com.example.app", focus.focusedAppPackage)
        assertEquals(".MainActivity", focus.focusedAppActivity)
        assertEquals("16", focus.focusedAppTask)
    }

    @Test
    fun missingNullAndMalformedFocusFailClosed() {
        assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse("mFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}")
        }
        assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse(
                "mCurrentFocus=null\nmFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}",
            )
        }
        assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse(
                "mCurrentFocus=Window{1 u0 not_a_package/Main}\n" +
                    "mFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}",
            )
        }
        assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse("")
        }
    }

    @Test
    fun samePackageDialogChangesTheWindowDigestButNotThePackage() {
        val dialog = AdbWindowStateParser.parse(
            """
            mCurrentFocus=Window{9999999 u0 com.example.app/com.example.app.SomeDialog}
            mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}
            """.trimIndent(),
        )
        assertEquals(focus.focusedAppPackage, dialog.focusedAppPackage)
        assertNotEquals(
            AdbWindowIdentity.witness(focus, "com.example.app", geometry, nodes).windowDigest,
            AdbWindowIdentity.witness(dialog, "com.example.app", geometry, nodes).windowDigest,
        )
    }

    @Test
    fun contentChangeChangesTheContentDigest() {
        val moved = nodes.map { it.copy(bounds = it.bounds.copy(top = 40, bottom = 140)) }
        val changedLabel = nodes.map { it.copy(label = "changed") }
        val base = AdbWindowIdentity.witness(focus, "com.example.app", geometry, nodes)
        assertNotEquals(base.contentDigest, AdbWindowIdentity.witness(focus, "com.example.app", geometry, moved).contentDigest)
        assertNotEquals(base.contentDigest, AdbWindowIdentity.witness(focus, "com.example.app", geometry, changedLabel).contentDigest)
        assertEquals(base.windowDigest, AdbWindowIdentity.witness(focus, "com.example.app", geometry, moved).windowDigest)
    }

    @Test
    fun witnessRoundTripsAndRejectsMalformedValues() {
        val witness = AdbWindowIdentity.witness(focus, "com.example.app", geometry, nodes)
        val encoded = witness.encode()
        assertTrue(encoded.length <= 128)
        assertEquals(witness, AdbWindowWitness.parse(encoded))
        assertNull(AdbWindowWitness.parse("other-window"))
        assertNull(AdbWindowWitness.parse("w1.short.short"))
        assertNull(AdbWindowWitness.parse(witness.encode() + "x"))
    }

    @Test
    fun geometryChangeChangesTheWindowDigest() {
        val rotated = geometry.copy(rotation = 1, displayWidth = 2400, displayHeight = 1080)
        assertNotEquals(
            AdbWindowIdentity.witness(focus, "com.example.app", geometry, nodes).windowDigest,
            AdbWindowIdentity.witness(focus, "com.example.app", rotated, nodes).windowDigest,
        )
    }
}
