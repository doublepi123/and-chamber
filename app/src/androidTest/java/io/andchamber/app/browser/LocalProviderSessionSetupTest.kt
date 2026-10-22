package io.andchamber.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure guards for the production notification/deep-link console setup; no device. */
class LocalProviderSessionSetupTest {
    private val base = "http://127.0.0.1:3000"
    private val session = "ses_abc123"

    @Test
    fun linkGuardAcceptsOnlyARealSessionAndTheActualRootDirectory() {
        assertTrue(LocalProviderConsoleSetup.validLink(session, "/root"))
        assertFalse(LocalProviderConsoleSetup.validLink(session, null))
        assertFalse(LocalProviderConsoleSetup.validLink(session, "/root/project"))
        assertFalse(LocalProviderConsoleSetup.validLink("not-a-session", "/root"))
        assertFalse(LocalProviderConsoleSetup.validLink("ses_", "/root"))
        assertFalse(LocalProviderConsoleSetup.validLink("ses_abc 123", "/root"))
        assertFalse(LocalProviderConsoleSetup.validLink("ses_" + "a".repeat(250), "/root"))
    }

    @Test
    fun consoleQueryNeedsTheExactLocalOriginPathAndExpectedSession() {
        assertEquals(session, LocalProviderConsoleSetup.consoleSession("$base/?session=$session", base, session))
        assertEquals(session, LocalProviderConsoleSetup.consoleSession("$base/?foo=1&session=$session", base, session))
        assertEquals(session, LocalProviderConsoleSetup.consoleSession("$base/?session=$session&foo=1", base, session))

        assertNull(LocalProviderConsoleSetup.consoleSession("http://127.0.0.1:3001/?session=$session", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("http://localhost:3000/?session=$session", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("https://127.0.0.1:3000/?session=$session", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("$base/other?session=$session", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("$base/?session=ses_other", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("$base/", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("$base/?session=$session&session=$session", base, session))
        assertNull(LocalProviderConsoleSetup.consoleSession("$base/?session=$session", base, "ses_other"))
    }

    @Test
    fun readyContractRequiresEveryReadonlyCondition() {
        fun ready(
            activityResumed: Boolean = true,
            documentReady: String? = "complete",
            presenterEligible: Boolean = true,
            presenterConnected: Boolean = true,
            targetViewSeparate: Boolean = true,
            frameStable: Boolean = true,
            consoleQuerySession: String? = session,
            expectedSession: String = session,
        ) = LocalProviderConsoleSetup.readyContract(
            activityResumed,
            documentReady,
            presenterEligible,
            presenterConnected,
            targetViewSeparate,
            frameStable,
            consoleQuerySession,
            expectedSession,
        )

        assertTrue(ready())
        assertFalse(ready(activityResumed = false))
        assertFalse(ready(documentReady = "interactive"))
        assertFalse(ready(documentReady = null))
        assertFalse(ready(presenterEligible = false))
        assertFalse(ready(presenterConnected = false))
        assertFalse(ready(targetViewSeparate = false))
        assertFalse(ready(frameStable = false))
        assertFalse(ready(consoleQuerySession = null))
        assertFalse(ready(consoleQuerySession = "ses_other"))
        assertFalse(ready(expectedSession = "ses_other"))
    }
}
