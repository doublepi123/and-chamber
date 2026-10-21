package io.andchamber.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure product command grammar; no device or CookieManager. */
class LocalProviderProductProtocolTest {
    @Test
    fun presentationAndProfileCommandsAreFixed() {
        for (text in listOf(
            "presentation-observe",
            "site-state-read",
            "site-state-clear",
            "profile-isolation",
            "native-blocked-input",
            "chat-browser-state",
            "a11y-off:begin",
            "a11y-off:end",
        )) {
            assertTrue(text, LocalProviderRegressionProtocol.deviceAllowed(text))
        }
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("profile-isolation-other"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("native-blocked-input:extra"))
        for (text in listOf("a11y-off", "a11y-off:on", "a11y-off:begin:extra", "a11y-off:begin ", "a11y-off:BEGIN")) {
            assertTrue(text, !LocalProviderRegressionProtocol.deviceAllowed(text))
        }
    }

    @Test
    fun siteStateMarkerIsBoundedHexOnlyOnTheWriteCommand() {
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("site-state-write:" + "a".repeat(16)))
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("site-state-write:" + "A1".repeat(32)))
        for (text in listOf(
            "site-state-write:" + "a".repeat(15),
            "site-state-write:" + "g".repeat(16),
            "site-state-write:" + "m1abc2def3",
            "site-state-write:" + "a".repeat(16) + "; path=/",
            "site-state-write:" + "a".repeat(16) + " extra",
        )) {
            assertTrue(text, !LocalProviderRegressionProtocol.deviceAllowed(text))
        }
    }

    @Test
    fun focusedFixtureLabelsAndChatTargetsAreFinite() {
        for (label in listOf("note", "Apply")) {
            assertTrue(LocalProviderRegressionProtocol.deviceAllowed("tap-label:$label"))
            assertTrue(LocalProviderRegressionProtocol.deviceAllowed("fixture-field:$label"))
        }
        for (label in LocalProviderRegressionProtocol.CHAT_UI_LABELS) {
            assertTrue(label, LocalProviderRegressionProtocol.deviceAllowed("ui-target-chat:$label"))
            assertTrue(label, LocalProviderRegressionProtocol.deviceAllowed("ui-target:$label"))
        }
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("ui-target-chat:Delete"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("tap-label:Delete"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("fixture-field:password"))
    }

    @Test
    fun drawerDomTargetsAreTheFixedSelectorsOnly() {
        val expected = mapOf(
            "Browser address" to "[data-testid=\"android-browser-address\"]",
            "Open" to "[data-testid=\"android-browser-open\"]",
            "Browser" to "[data-testid=\"mobile-workspace-tab-browser\"]",
        )
        assertEquals(expected.keys, LocalProviderRegressionProtocol.CHAT_DOM_TARGETS.keys)
        for ((label, target) in LocalProviderRegressionProtocol.CHAT_DOM_TARGETS) {
            assertTrue(label, LocalProviderRegressionProtocol.deviceAllowed("chat-dom-target:$label"))
            assertTrue(label, LocalProviderRegressionProtocol.deviceAllowed("ui-target-chat:$label"))
            assertEquals(expected[label], target.selector)
            assertEquals(label == "Browser address", target.editable)
        }
        for (text in listOf(
            "chat-dom-target:New chat",
            "chat-dom-target:Delete",
            "chat-dom-target:",
        )) {
            assertTrue(text, !LocalProviderRegressionProtocol.deviceAllowed(text))
        }
    }

    @Test
    fun profileCheckReportsOwnAndOtherStoresWithoutFakeCookieManager() {
        val isolated = ProfileIsolationEvidence.check(
            "browser",
            ownPresent = true,
            otherWriters = listOf("console", "other"),
            otherPresent = listOf(false, false),
        )
        assertEquals("browser", isolated.optString("writer"))
        assertTrue(isolated.optBoolean("ownPresent"))
        assertEquals("console", isolated.getJSONArray("otherWriters").getString(0))
        assertTrue(!isolated.getJSONArray("otherPresent").getBoolean(0))
        assertTrue(!isolated.getJSONArray("otherPresent").getBoolean(1))
        assertTrue(ProfileIsolationEvidence.cleaned(false))
        assertTrue(!ProfileIsolationEvidence.cleaned(true))
    }
}
