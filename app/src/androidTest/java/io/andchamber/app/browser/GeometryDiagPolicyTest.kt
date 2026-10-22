package io.andchamber.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision checks for geometry-diag. They run on the instrumentation JVM without a device.
 * An older unknown page must not leak into the selected tab's answer.
 */
class GeometryDiagPolicyTest {
    @Test
    fun earlierUnknownPageDoesNotPoisonSelectedPage() {
        // A (old, unknown) is not selected; B is selected and clean.
        val selected = "tab-b"
        assertEquals(false, GeometryDiagPolicy.outcomeUnknownFor(selected, selected, false))
    }

    @Test
    fun selectedUnknownPageReportsTrue() {
        assertEquals(true, GeometryDiagPolicy.outcomeUnknownFor("tab-b", "tab-b", true))
    }

    @Test
    fun missingSelectedTabIsNullNotFalse() {
        assertNull(GeometryDiagPolicy.outcomeUnknownFor(null, null, null))
    }

    @Test
    fun unreadableFlagIsNullNotFalse() {
        assertNull(GeometryDiagPolicy.outcomeUnknownFor("tab-b", "tab-b", null))
    }

    @Test
    fun boundPageOfAnotherTabIsNullNotFalse() {
        assertNull(GeometryDiagPolicy.outcomeUnknownFor("tab-b", "tab-a", true))
    }

    @Test
    fun grantForSelectedRequiresMatchingTabAndOwner() {
        assertTrue(GeometryDiagPolicy.grantForSelected("tab-b", "tab-b", "ses_x", "ses_x") == true)
        assertFalse(GeometryDiagPolicy.grantForSelected("tab-b", "tab-a", "ses_x", "ses_x") == true)
        assertEquals(false, GeometryDiagPolicy.grantForSelected("tab-b", null, null, "ses_x"))
        assertNull(GeometryDiagPolicy.grantForSelected(null, null, null, null))
    }
}
