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
        val nullFocus = assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse(
                "mCurrentFocus=null\nmFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}",
            )
        }
        assertEquals(AdbWindowStateFailure.NOT_READY, nullFocus.failure)
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
    fun aNamedNonAppFocusWindowIsTypedNotReadyNotAMalformedDump() {
        // Real focus titles without `pkg/activity`: input method, starting window, system bars.
        listOf("InputMethod", "Splash Screen com.example.app", "NotificationShade").forEach { title ->
            val error = assertThrows(AdbWindowStateException::class.java) {
                AdbWindowStateParser.parse(
                    "mCurrentFocus=Window{7068ee4 u0 $title}\n" +
                        "mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}",
                )
            }
            assertEquals(title, AdbWindowStateFailure.NOT_READY, error.failure)
        }
    }

    @Test
    fun aCompleteNullCurrentFocusLineIsTypedNotReady() {
        val error = assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse(
                "  mCurrentFocus=null  \n" +
                    "mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}",
            )
        }
        assertEquals(AdbWindowStateFailure.NOT_READY, error.failure)
    }

    @Test
    fun aCompleteNullFocusedAppLineIsTypedNotReady() {
        val error = assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse(
                "mCurrentFocus=Window{7068ee4 u0 com.example.app/.MainActivity}\n" +
                    "mFocusedApp=null\r\n",
            )
        }
        assertEquals(AdbWindowStateFailure.NOT_READY, error.failure)
    }

    @Test
    fun bothNullFieldLinesFailClosedAsNotReady() {
        val error = assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse("mCurrentFocus=null\nmFocusedApp=null")
        }
        assertEquals(AdbWindowStateFailure.NOT_READY, error.failure)
    }

    @Test
    fun nullWordsOutsideACompleteFieldLineAreNeverNotReady() {
        fun failure(text: String): AdbWindowStateFailure =
            assertThrows(AdbWindowStateException::class.java) {
                AdbWindowStateParser.parse(text)
            }.failure

        // A suffix or title text is not the official complete field line.
        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure(
                "mCurrentFocus=null (title)\n" +
                    "mFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}",
            ),
        )
        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure(
                "mCurrentFocus=nullx\n" +
                    "mFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}",
            ),
        )
        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure(
                "mCurrentFocus=Window{1 u0 com.example.app/.Main}\n" +
                    "mFocusedApp=null t1",
            ),
        )

        // A real field line wins; the same words inside another line are not a null field.
        val parsed = AdbWindowStateParser.parse(
            "mCurrentFocus=Window{7068ee4 u0 com.example.app/.MainActivity}\n" +
                "mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}\n" +
                "  mAttrs={... title=mCurrentFocus=null}",
        )
        assertEquals("com.example.app", parsed.currentFocusPackage)
    }

    @Test
    fun missingTruncatedOrMalformedOutputStaysInvalid() {
        fun failure(text: String): AdbWindowStateFailure =
            assertThrows(AdbWindowStateException::class.java) {
                AdbWindowStateParser.parse(text)
            }.failure

        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure("mFocusedApp=ActivityRecord{1 u0 com.example.app/.Main t1}"),
        )
        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure("mCurrentFocus=Window{1 u0 com.example.app/.Main}"),
        )
        // A null focus alone is not a complete dump when the focused-app field is missing.
        assertEquals(AdbWindowStateFailure.INVALID, failure("mCurrentFocus=null\n"))
        // A malformed present field is INVALID even when the other one is explicitly null.
        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure(
                "mCurrentFocus=null\n" +
                    "mFocusedApp=ActivityRecord{1 u0 not_a_package/Main t1}",
            ),
        )
        assertEquals(AdbWindowStateFailure.INVALID, failure("garbage output"))
        assertEquals(
            AdbWindowStateFailure.INVALID,
            failure("x".repeat(AdbWindowStateParser.MAX_BYTES + 1)),
        )
    }

    @Test
    fun aStableWindowParsesAfterANullTransition() {
        assertThrows(AdbWindowStateException::class.java) {
            AdbWindowStateParser.parse(
                "mCurrentFocus=null\n" +
                    "mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}",
            )
        }

        val parsed = AdbWindowStateParser.parse(
            """
            mCurrentFocus=Window{7068ee4 u0 com.example.app/.MainActivity}
            mFocusedApp=ActivityRecord{312191b u0 com.example.app/.MainActivity t16}
            """.trimIndent(),
        )
        assertEquals("7068ee4", parsed.currentFocusToken)
        assertEquals("com.example.app", parsed.currentFocusPackage)
        assertEquals("16", parsed.focusedAppTask)
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
