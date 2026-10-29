package io.andchamber.app.phone.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLaunchAutoConnectTest {
    private fun allowed(
        enabled: Boolean = true,
        available: Boolean = true,
        hasPairingReceipt: Boolean = true,
        hasStandingScope: Boolean = true,
        localRuntimeReady: Boolean = true,
        connected: Boolean = false,
        connecting: Boolean = false,
        attemptedThisRun: Boolean = false,
    ) = shouldAutoConnectOnLaunch(
        enabled = enabled,
        available = available,
        hasPairingReceipt = hasPairingReceipt,
        hasStandingScope = hasStandingScope,
        localRuntimeReady = localRuntimeReady,
        connected = connected,
        connecting = connecting,
        attemptedThisRun = attemptedThisRun,
    )

    @Test
    fun `a configured link reconnects in the background`() {
        assertTrue(allowed())
    }

    @Test
    fun `a disabled or unsupported experiment never reconnects`() {
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(available = false))
    }

    @Test
    fun `a background connect needs a saved pairing and standing scope`() {
        // No receipt: pairing is the person's explicit action, never a background one.
        assertFalse(allowed(hasPairingReceipt = false))
        // No scope: there is nothing to reconnect for, so the manual page stays the only path.
        assertFalse(allowed(hasStandingScope = false))
    }

    @Test
    fun `a stopped or failed local runtime does not reconnect`() {
        assertFalse(allowed(localRuntimeReady = false))
    }

    @Test
    fun `an existing connection or search is left alone`() {
        assertFalse(allowed(connected = true))
        assertFalse(allowed(connecting = true))
    }

    @Test
    fun `one attempt per app run, never a retry loop`() {
        assertFalse(allowed(attemptedThisRun = true))
    }

    @Test
    fun `a read-timeout disconnect re-arms exactly one background attempt`() {
        assertTrue(
            shouldRearmAfterReadTimeout(
                enabled = true,
                available = true,
                pending = false,
                searching = false,
            ),
        )
        // Disabled, unsupported, a pending device operation or a running search stays closed.
        assertFalse(shouldRearmAfterReadTimeout(enabled = false, available = true, pending = false, searching = false))
        assertFalse(shouldRearmAfterReadTimeout(enabled = true, available = false, pending = false, searching = false))
        assertFalse(shouldRearmAfterReadTimeout(enabled = true, available = true, pending = true, searching = false))
        assertFalse(shouldRearmAfterReadTimeout(enabled = true, available = true, pending = false, searching = true))
    }
}
